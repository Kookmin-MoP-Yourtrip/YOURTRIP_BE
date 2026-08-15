#!/bin/bash
# App EC2 부트스트랩 — Amazon Linux 2023
# 이 파일은 Terraform templatefile()로 렌더링된다. 중괄호로 감싼 변수 참조는 전부
# Terraform 변수 치환 대상이고, 순수 bash 변수는 $VAR(중괄호 없이)로만 참조해 충돌을 피한다.
#
# 이 인스턴스에서는 애플리케이션을 빌드하지 않는다 — JAR는 로컬(또는 CI)에서 미리
# 빌드해 apply 이후 별도로 scp 전달한다(README.md "실행 순서" 참고). t3.micro(vCPU 2개지만
# 물리 코어는 1개인 SMT/하이퍼스레딩 버스터블) 위에서 직접 git clone + Gradle 빌드를 했을 때
# 실측으로 확인된 문제들 때문이다:
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
# (docs/guide/PROFILE-DEPLOYMENT.md 참고).
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
ENVEOF
chmod 600 /opt/app/.env

# --- systemd 서비스 등록 ---
# app.jar/cloudfront_private_key.pem은 아직 이 시점엔 없다(scp로 나중에 도착) — 그래도
# 등록 자체는 문제없다. ExecStart 대상이 없어 최초 시작은 실패하지만 Restart=on-failure가
# 계속 재시도하다가, 두 파일이 scp로 도착하는 순간 다음 재시도에서 자연스럽게 기동한다
# (실측으로 검증됨 — 별도의 수동 restart 없이도 파일 도착 후 수 초 내 자동 기동).
#
# -Xmx448m: 1GB 중 OS(~150~200MB)+메타스페이스+Tomcat maxThreads=200 스레드 스택
# 여유분을 남기고 힙 상한을 명시적으로 통제한다.
# -Xss512k: 기본 1MB 대비 스레드 스택 메모리 절반(200스레드 기준 이론상 최대 200MB->100MB).
# 너무 작으면 Hibernate처럼 콜스택이 깊은 코드에서 StackOverflowError 위험이 있으니
# 부하테스트 중 애플리케이션 로그를 함께 확인한다.
cat > /etc/systemd/system/yourtrip-app.service <<'SERVICEEOF'
[Unit]
Description=YOURTRIP Spring Boot App (loadtest)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/app
EnvironmentFile=/opt/app/.env
ExecStart=/usr/bin/java -Xmx448m -Xss512k -jar /opt/app/app.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
SERVICEEOF

systemctl daemon-reload
systemctl enable --now yourtrip-app.service

# --- CloudWatch Agent: 메모리 지표 수집 ---
# 기본 CloudWatch 지표는 EC2 메모리를 노출하지 않는다. mem_used_percent가 이번
# 실험의 핵심 검증 항목(t3.micro 1GB가 실제로 버티는가) 중 하나라 필수로 켠다.
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
