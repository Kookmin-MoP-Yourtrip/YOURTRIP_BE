#!/bin/bash
# App EC2 부트스트랩 — Amazon Linux 2023
# 이 파일은 Terraform templatefile()로 렌더링된다. 중괄호로 감싼 변수 참조는 전부
# Terraform 변수 치환 대상이고, 순수 bash 변수는 $VAR(중괄호 없이)로만 참조해 충돌을 피한다.
set -euxo pipefail

dnf install -y java-21-amazon-corretto-devel git amazon-cloudwatch-agent

# --- 빌드 시 메모리 스파이크 대비 임시 스왑 ---
# t3.micro는 RAM 1GB뿐이라 Gradle 빌드(의존성 다운로드+컴파일)가 OOM으로 죽을 위험이 있다.
# 빌드 동안만 스왑을 켜고, 실제 부하테스트를 시작하기 전에 반드시 끈다 — 스왑을 켜둔 채
# 측정하면 디스크 I/O 지연이 섞여 "1GB로 버티는가"라는 이번 실험의 핵심 질문이 왜곡된다.
fallocate -l 1G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile

# --- 앱 체크아웃 & 빌드 ---
# app_git_ref는 브랜치명이 아니라 커밋 해시일 수 있다. `git clone --branch --depth 1`은
# 브랜치/태그 이름만 지원하고 임의 커밋 SHA는 실패한다(실측으로 확인된 버그 —
# "Remote branch <sha> not found in upstream origin"). init+remote+fetch by SHA는
# 브랜치명/SHA 둘 다 지원하고, GitHub은 공개 저장소에 대해 임의 SHA fetch를 허용한다.
mkdir -p /opt/app
cd /opt/app
git init -q
git remote add origin "${app_repo_url}"
git fetch --depth 1 origin "${app_git_ref}"
git checkout -q FETCH_HEAD
chmod +x gradlew
./gradlew bootJar -x test --no-daemon

JAR_PATH=$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n1)
mv "$JAR_PATH" /opt/app/app.jar

# --- 빌드 완료, 스왑 해제 (실측 왜곡 방지) ---
swapoff /swapfile
rm -f /swapfile

# --- 런타임 환경변수 ---
# CloudFront 개인키는 기존 terraform/README.md와 동일한 원칙으로 Terraform state에
# 절대 넣지 않는다. apply 이후 `scp`로 로컬의 cloudfront_private_key.pem을 아래 경로로
# 직접 옮겨야 한다 (README.md "실행 순서" 참고).
cat > /opt/app/.env <<'ENVEOF'
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

touch /opt/app/cloudfront_private_key.pem
chmod 600 /opt/app/cloudfront_private_key.pem

# --- systemd 서비스 등록 ---
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
