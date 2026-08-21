#!/bin/bash
# App EC2 부트스트랩 — Amazon Linux 2023
# 이 파일은 Terraform templatefile()로 렌더링된다. 중괄호로 감싼 변수 참조는 전부
# Terraform 변수 치환 대상이고, 순수 bash 변수는 $VAR(중괄호 없이)로만 참조해 충돌을 피한다.
#
# 이 인스턴스에서는 애플리케이션을 빌드하지 않는다 — JAR는 로컬(또는 CI)에서 미리
# 빌드해 apply 이후 별도로 scp 전달한다(README.md "실행 순서" 참고). 이 인스턴스가 아직
# t3.micro(1GB)이던 시절 — vCPU 2개지만 물리 코어는 1개인 SMT/하이퍼스레딩 버스터블은 지금의
# t3.small도 동일하다 — 직접 git clone + Gradle 빌드를 했을 때 실측으로 확인된 문제들 때문이다:
# (1) 빌드가 3~4분간 CPU를 거의 100% 태워 부하테스트 시작 시점의 CPU 크레딧 잔액을
#     이미 갉아먹는다 — 측정 시작 조건이 매번 달라지는 변수가 된다.
# (2) 1GB RAM에서 Gradle 빌드가 OOM 없이 끝나려면 임시 스왑이 필요했다(빌드 후 해제).
# (3) 서버에서 직접 빌드하는 방식 자체가 재현 불가능한 아티팩트·롤백 불가 같은
#     문제를 안고 있어 일반적으로 권장되지 않는다 — 로컬 빌드 + 불변 아티팩트 전달이
#     측정 정합성과 실무 배포 관행 양쪽에 더 맞는다.
set -euxo pipefail

# javac 등 개발 도구가 필요 없으므로 headless JRE만 설치한다 — devel 패키지보다 가볍고
# git도 더 이상 필요 없다(이 인스턴스에서 소스를 받지 않으므로).
dnf install -y java-21-amazon-corretto-headless amazon-cloudwatch-agent

mkdir -p /opt/app

# --- 런타임 환경변수 ---
# CloudFront 개인키는 기존 terraform/README.md와 동일한 원칙으로 Terraform state에
# 절대 넣지 않는다. app.jar와 마찬가지로 apply 이후 `scp`로 전달해야 한다
# (README.md "실행 순서" 참고).
#
# SPRING_PROFILES_ACTIVE=prod: 이 줄이 없으면 앱이 local 프로필로 떠서(application.yml의
# spring.profiles.default) DEBUG + SQL을 전량 로깅한다. 부하테스트에서는 그 로깅 비용이
# 측정값 자체를 오염시키므로 반드시 필요하다. 아래 .env는 systemd EnvironmentFile로 읽히니
# 실제 OS 환경변수로 주입되고, spring-dotenv의 로딩 시점 문제를 타지 않는다
# (docs/guide/profile.md 참고).
cat > /opt/app/.env <<'ENVEOF'
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://${db_host}:5432/${db_name}
DB_USERNAME=${db_username}
DB_PASSWORD=${db_password}
DB_DDL_AUTO=create
JWT_SECRET=${jwt_secret}
MAIL_EMAIL=${mail_email}
MAIL_PASSWORD=${mail_password}
KAKAO_API_KEY=${kakao_api_key}
S3_BUCKET=${s3_bucket}
AWS_ACCESS_KEY=${aws_access_key}
AWS_SECRET_KEY=${aws_secret_key}
CLOUDFRONT_DOMAIN=${cloudfront_domain}
CLOUDFRONT_KEY_PAIR_ID=${cloudfront_key_pair_id}
CLOUDFRONT_PRIVATE_KEY_PATH=/opt/app/cloudfront_private_key.pem
CLOUDFRONT_DISTRIBUTION_ID=${cloudfront_distribution_id}
GEMINI_API_KEY=${gemini_api_key}
REDIS_HOST=${redis_host}
REDIS_PORT=6379
JVM_OPTS=-Xmx768m -Xss512k
ENVEOF
chmod 600 /opt/app/.env

# --- systemd 서비스 등록 ---
# app.jar/cloudfront_private_key.pem은 아직 이 시점엔 없다(scp로 나중에 도착) — 그래도
# 등록 자체는 문제없다. ExecStart 대상이 없어 최초 시작은 실패하지만 Restart=on-failure가
# 계속 재시도하다가, 두 파일이 scp로 도착하는 순간 다음 재시도에서 자연스럽게 기동한다
# (실측으로 검증됨 — 별도의 수동 restart 없이도 파일 도착 후 수 초 내 자동 기동).
#
# JVM 옵션은 위 .env의 JVM_OPTS에 있다. ExecStart에서 중괄호 없는 $JVM_OPTS로 참조하면
# systemd가 공백으로 단어 분리해 넘긴다 — 중괄호로 감싸면 인자 하나로 뭉쳐서 깨지고,
# 애초에 이 파일에서 중괄호 참조는 전부 Terraform 치환 대상이라 쓸 수 없다(상단 주석 참고).
# 값을 .env에 둔 이유는 측정 중 arm을 바꾸기 위해서다 — 이 파일을 고치면
# ec2_app.tf의 user_data_replace_on_change = true 때문에 인스턴스가 교체되고
# scp로 올린 app.jar와 CloudFront 개인키가 함께 사라진다.
#
# -Xmx768m 산정 근거 (2026-08-21 실측, docs/tasks/jvm-heap-sizing/):
#   MemTotal 1,913MB
#   - OS+CloudWatch/SSM 에이전트 321MB (앱 정지 상태의 MemTotal-MemAvailable)
#   - 논힙 committed 192MB x1.2 (메타스페이스 141MB가 지배하며 스레드 수와 무관하게 일정)
#   - direct buffer 8.4MB x3
#   - 힙 밖 잔여 171MB x1.3 (심볼 54MB + G1 자료구조 43MB + 스레드 스택 7MB 등, NMT 실측)
#   - 안전 여유 10%
#   = 923MB. 여기서 'OS 실측이 300MB를 넘으면 한 단계 내린다'는 사전 등록 규칙에 따라 768m.
#   퍼센트로 환산하면 -XX:MaxRAMPercentage=40.1에 해당한다(컨테이너로 옮길 때의 매핑).
#   고정값을 쓰는 이유: 부하테스트와 배포 타겟이 둘 다 t3.small이라 비율 지정의 이점이 없고,
#   MemTotal이 AMI/커널에 따라 흔들리면 문서에 적은 값과 실제가 어긋나기 때문이다.
#
# 힙 상한을 448m에서 올렸지만 성능이 좋아지는 것은 아니다 — G1은 GC 오버헤드가
# GCTimeRatio 목표(약 7.7%)를 넘을 때만 힙을 넓히는데 실측 점유는 1% 안팎이라,
# 천장을 올려도 실제 커밋량은 227MB 부근에서 움직이지 않았다. 448m은 t3.micro(1GB)
# 전제로 잡힌 값이라 근거를 잃었을 뿐 아니라 JVM 기본값(480MB)의 93%에 불과해
# 사실상 아무 통제도 하지 않고 있었다. 이번 변경은 그 근거를 2GB 기준으로 복원한 것이다.
#
# -Xss512k: 기본 1MB의 절반. NMT 실측으로는 스레드당 실제 커밋이 112KB(65스레드 7.1MB)라
# 이 플래그가 아끼는 RSS는 사실상 0이고(스택은 요구 페이징이다) 줄어드는 것은 가상 주소
# 공간뿐이다. 반대로 Hibernate처럼 콜스택이 깊은 코드에서 StackOverflowError 위험은 남는다.
# 200스레드 시절의 근거(최대 100MB 절약)는 maxThreads=32에서 무너졌다 — 제거 여부는
# 별도 판단으로 남겨 뒀다(docs/tasks/jvm-heap-sizing/memory-map.md).
#
# GC는 일부러 지정하지 않는다 — ergonomics에 맡겨야 배포 타겟과 같은 조건을 재현한다.
# t3.small(MemTotal 1,913MB)은 server-class 문턱 1,792MB 바로 위라 G1으로 뜨지만,
# 인스턴스를 한 단계라도 내리면 SerialGC로 조용히 바뀐다. 명시 대신 감시로 대응한다 —
# 집계기의 gc_names 열과 LoadtestMetricsExposureTest가 그 역할이다
# (근거 전문: docs/tasks/jvm-heap-sizing/memory-map.md '1. GC와 기본 힙').
cat > /etc/systemd/system/yourtrip-app.service <<'SERVICEEOF'
[Unit]
Description=YOURTRIP Spring Boot App (loadtest)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/app
EnvironmentFile=/opt/app/.env
ExecStart=/usr/bin/java $JVM_OPTS -jar /opt/app/app.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
SERVICEEOF

systemctl daemon-reload
systemctl enable --now yourtrip-app.service

# --- CloudWatch Agent: 메모리 지표 수집 ---
# 기본 CloudWatch 지표는 EC2 메모리를 노출하지 않는다. mem_used_percent가 이번
# 실험의 핵심 검증 항목(배포 타겟 스펙 t3.small 2GB가 실제로 버티는가) 중 하나라 필수로 켠다.
mkdir -p /opt/aws/amazon-cloudwatch-agent/etc
cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json <<'CWEOF'
{
  "metrics": {
    "namespace": "YourtripLoadtest",
    "metrics_collected": {
      "mem": {
        "measurement": ["mem_used_percent"],
        "metrics_collection_interval": 30
      }
    }
  }
}
CWEOF

/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config -m ec2 -s \
  -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json
