#!/bin/bash
# 운영 앱 인스턴스 부팅 스크립트 (ASG Launch Template의 user_data).
#
# terraform/loadtest/templates/app-user-data.sh.tpl에서 출발했지만 세 가지가 다르다:
#   1) 시크릿을 여기 렌더링하지 않고 SSM Parameter Store에서 부팅 시 받아온다.
#      user_data는 인스턴스 안에서 인증 없이 읽히는데(169.254.169.254), 이 앱은 외부 API를
#      호출하므로 SSRF 표면이 실재하고 운영은 0.0.0.0/0에 노출된다.
#   2) JAR을 scp로 받지 않고 S3에서 내려받는다. ASG는 사람 개입 없이 인스턴스를 띄우므로
#      scp가 성립하지 않는다.
#   3) systemd 유닛과 JVM 옵션을 여기 다시 쓰지 않고 deploy/prod/의 정본을 주입받는다.
#
# 템플릿 규약: 중괄호로 감싼 변수 참조는 전부 Terraform 치환 대상이다. 순수 bash 변수는
# $$VAR(달러 두 개)로 써야 terraform이 건드리지 않는다.

set -euo pipefail

# 부팅 로그를 남긴다. 아래에서 시크릿을 다루는 구간만 -x를 끈다.
set -x

dnf install -y java-21-amazon-corretto-headless amazon-cloudwatch-agent
mkdir -p /opt/app

# ------------------------------------------------------------
# 1) 비밀이 아닌 환경변수
#
# DB_DDL_AUTO가 update인 이유는 variables.tf에 적어뒀다 — 요약하면 create는 인스턴스가 뜰
# 때마다 스키마를 DROP+CREATE하는데, ASG 스케일아웃으로 두 번째 인스턴스가 뜨는 순간
# 첫 번째가 쓰던 테이블이 통째로 날아가기 때문이다.
# ------------------------------------------------------------
cat > /opt/app/.env <<'ENVEOF'
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://${db_host}:5432/${db_name}
DB_USERNAME=${db_username}
DB_DDL_AUTO=${db_ddl_auto}
REDIS_HOST=${redis_host}
REDIS_PORT=${redis_port}
S3_BUCKET=${s3_bucket}
CLOUDFRONT_DOMAIN=${cloudfront_domain}
CLOUDFRONT_KEY_PAIR_ID=${cloudfront_key_pair_id}
CLOUDFRONT_DISTRIBUTION_ID=${cloudfront_distribution_id}
CLOUDFRONT_PRIVATE_KEY_PATH=/opt/app/cloudfront_private_key.pem
ENVEOF

# ------------------------------------------------------------
# 2) 시크릿을 SSM에서 받아 .env에 덧붙인다
#
# -x를 끄는 이유: 켜둔 채로 두면 복호화된 값이 그대로 cloud-init 로그에 찍혀,
# user_data에서 시크릿을 걷어낸 의미가 사라진다.
#
# env/ 하위만 조회한다. cloudfront_private_key는 여러 줄 PEM이라 .env에 넣으면 파일이
# 깨지므로 경로를 분리해 두고 아래에서 따로 처리한다.
# ------------------------------------------------------------
set +x
aws ssm get-parameters-by-path \
  --path "${ssm_path}/env" \
  --with-decryption \
  --query 'Parameters[].[Name,Value]' \
  --output text \
  | while IFS=$$'\t' read -r name value; do
      printf '%s=%s\n' "$${name##*/}" "$$value"
    done >> /opt/app/.env

# CloudFront Signed URL 서명용 개인키. 파일로 떨어져야 하고 앱이 위 .env의
# CLOUDFRONT_PRIVATE_KEY_PATH로 이 경로를 읽는다.
aws ssm get-parameter \
  --name "${ssm_path}/cloudfront_private_key" \
  --with-decryption \
  --query 'Parameter.Value' \
  --output text > /opt/app/cloudfront_private_key.pem
set -x

chmod 600 /opt/app/.env /opt/app/cloudfront_private_key.pem
chown root:root /opt/app/.env /opt/app/cloudfront_private_key.pem

# ------------------------------------------------------------
# 3) JVM 기동 옵션 — deploy/prod/jvm-opts.env의 정본을 그대로 쓴다
#
# systemd 유닛이 EnvironmentFile로 이 경로를 읽는다. .env와 분리해 두는 이유는
# 전자가 git 밖에 있어야 하고 후자는 레포가 산정 근거와 함께 들고 있어야 하기 때문이다.
# ------------------------------------------------------------
cat > /opt/app/jvm-opts.env <<'JVMEOF'
${jvm_opts_env}
JVMEOF
chmod 644 /opt/app/jvm-opts.env

# ------------------------------------------------------------
# 4) 배포 JAR을 S3에서 내려받는다
#
# 키가 커밋 SHA로 고정돼 있어(app/<sha>.jar) 스케일아웃으로 뜬 인스턴스도 지금 돌고 있는
# 것과 정확히 같은 바이트를 받는다. app/app.jar 같은 가변 키를 쓰면 배포 중 스케일아웃이
# 일어났을 때 혼종 fleet이 된다.
# ------------------------------------------------------------
aws s3 cp "s3://${artifact_bucket}/${artifact_key}" /opt/app/app.jar
chmod 644 /opt/app/app.jar

# ------------------------------------------------------------
# 5) systemd 유닛 — deploy/prod/yourtrip-app.service의 정본을 주입받는다
#
# templatefile()은 주입된 값을 재스캔하지 않으므로 유닛 안의 $JVM_OPTS는 terraform이
# 건드리지 않고 그대로 남는다. systemd는 중괄호 없는 참조만 공백으로 단어 분리하므로
# 이 형태가 유지돼야 "-Xmx768m -Xss512k"가 인자 두 개로 전달된다.
# ------------------------------------------------------------
cat > /etc/systemd/system/yourtrip-app.service <<'SERVICEEOF'
${service_unit}
SERVICEEOF

systemctl daemon-reload
systemctl enable --now yourtrip-app.service

# ------------------------------------------------------------
# 6) CloudWatch Agent — 메모리 사용률
#
# 기본 CloudWatch 지표는 EC2 메모리를 노출하지 않는다. -Xmx768m이 2GB 박스에서 실제로
# 버티는지 보려면 이 지표가 필요하다(docs/tasks/jvm-heap-sizing/).
# ------------------------------------------------------------
mkdir -p /opt/aws/amazon-cloudwatch-agent/etc
cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json <<'CWEOF'
{
  "metrics": {
    "namespace": "${cloudwatch_namespace}",
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
