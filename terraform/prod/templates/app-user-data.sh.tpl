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
# 템플릿 규약: Terraform이 치환하는 것은 달러+중괄호 형태뿐이고, 이스케이프는 달러를 두 번
# 겹친 뒤 중괄호를 붙인 형태에만 적용된다(이 주석에 그 표기를 그대로 쓰면 templatefile이
# 보간으로 파싱해 apply가 깨지므로 말로 적는다 — 실제로 한 번 깨뜨렸다).
#
# ⚠️ 달러 두 개만 단독으로 쓰는 것은 이스케이프가 아니다. 그대로 렌더링되는데 bash에서 그건
#    현재 셸의 PID다. 실제로 IFS와 값 참조에 그렇게 썼다가 IFS가 깨지고 값이 PID+문자열이
#    되어, SSM에서 받은 시크릿이 .env에 한 줄도 들어가지 않았다(앱은 JWT_SECRET 누락으로
#    기동 실패). 중괄호 없는 bash 변수는 $VAR 그대로 쓰면 된다.
#
# 진단 메시지 규약: 주석은 한국어로 쓰되 stderr로 나가는 메시지(echo ... >&2)는 영문으로
# 쓴다. 이 로그를 읽는 자리가 대개 `aws ssm send-command`의 출력인데, 콘솔 코드페이지가
# cp949인 환경에서는 한글이 물음표로 깨진다. 하필 부팅 실패를 진단하는 바로 그 자리라
# 원인 파악이 늦어진다(#121 검증 중 실제로 겪었다).

set -euo pipefail

# 부팅 로그를 남긴다. 아래에서 시크릿을 다루는 구간만 -x를 끈다.
set -x

# Alloy는 여기서 설치하지 않는다. Grafana 저장소가 잠깐 안 되는 것이 서비스 장애가 되면
# 안 되므로, set -e가 JAR 다운로드 전에 부팅을 죽이지 않도록 6번 섹션으로 미룬다.
dnf install -y java-21-amazon-corretto-headless
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
  | while IFS=$'\t' read -r name value; do
      printf '%s=%s\n' "$${name##*/}" "$value"
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
# 키를 여기 상수로 박지 않고 SSM에서 받아온다. 상수로 박으면 키를 바꿀 때마다 Launch
# Template 새 버전이 필요하고, 그건 terraform apply를 거쳐야만 새 JAR을 내보낼 수 있다는
# 뜻이다. 이 저장소는 원격 backend가 없어 CI 러너에서 apply를 돌릴 수 없으므로, JAR 버전만
# terraform 밖으로 빼서 배포가 SSM 값 하나만 바꾸면 되게 했다(#120).
#
# 키 자체는 여전히 커밋 SHA로 고정된다(app/<sha>.jar) — 스케일아웃으로 뜬 인스턴스도 지금
# 돌고 있는 것과 정확히 같은 바이트를 받는다. app/app.jar 같은 가변 키를 쓰면 배포 중
# 스케일아웃이 일어났을 때 혼종 fleet이 된다. 바뀐 것은 그 키가 정해지는 시점뿐이다:
# apply 시점(tfvars) → 배포 시점(SSM).
#
# 조회에 실패하면 set -e가 여기서 부팅을 멈춘다. 앱이 뜨지 않으면 타깃 그룹 헬스체크가
# 실패해 ASG가 그 인스턴스를 InService로 승격하지 않으므로, 잘못된 버전이 조용히 서비스에
# 들어가는 경로가 없다.
#
# ARTIFACT_KEY는 중괄호 없이 쓴다 — terraform이 치환하는 것은 달러+중괄호 형태뿐이라 이
# 변수는 그대로 남아 bash가 해석한다. 이 파일 맨 위의 이스케이프 규약 참고.
# ------------------------------------------------------------
ARTIFACT_KEY=$(aws ssm get-parameter \
  --name "${ssm_path}/artifact_key" \
  --query 'Parameter.Value' \
  --output text)

# set -e는 조회 자체가 실패한 경우(ParameterNotFound)만 잡는다. 빈 값이 조회에 '성공'하면
# s3 cp가 버킷 루트를 받으려다 엉뚱하게 실패하므로, 여기서 이유를 남기고 멈춘다.
if [ -z "$ARTIFACT_KEY" ]; then
  echo "ERROR: artifact_key parameter is empty: ${ssm_path}/artifact_key" >&2
  exit 1
fi

# --quiet로 진행률 바를 끈다. 이 출력은 journald로 그대로 들어가는데, 이제 그 로그가
# Alloy를 거쳐 Loki로 전송되므로 인스턴스가 교체될 때마다 캐리지리턴 범벅인 진행률이
# 보존 용량을 먹는다. 대신 다운로드 확인은 아래 한 줄로 남긴다(실패는 set -e가 잡는다).
aws s3 cp "s3://${artifact_bucket}/$ARTIFACT_KEY" /opt/app/app.jar --quiet
chmod 644 /opt/app/app.jar
echo "downloaded artifact: $ARTIFACT_KEY ($(stat -c%s /opt/app/app.jar) bytes)"

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
# 6) Grafana Alloy — 앱 지표 + 호스트 지표 + journald 로그
#
# 설정 정본은 deploy/prod/config.alloy이고 asg.tf가 file()로 읽어 주입한다.
# 설계 근거는 docs/tasks/monitoring-config/README.md에 있다.
#
# 앱(5번)보다 뒤에 두는 것이 의도다. 관측 실패가 서비스 실패가 되면 안 된다.
# 아래 구간은 실패해도 부팅을 멈추지 않되, 실패를 로그에 크게 남긴다 — 그리고 판정 기준
# P1이 Alloy 생존을 따로 확인하므로 "조용히 안 도는" 상태로 넘어가는 경로는 없다.
#
# 앱 로그를 놓치지도 않는다: Alloy가 늦게 떠도 journald가 이미 갖고 있고 config.alloy의
# max_age가 12시간이라 소급해 읽는다. 잃는 것은 앱 기동 직후 몇십 초의 '지표'뿐이다.
# ------------------------------------------------------------
set +e
(
  # 서브셸 안에서 -e를 다시 켠다. 밖에서 +e만 하면 서브셸이 그것을 상속해 첫 실패에서
  # 멈추지 않고 끝까지 흘러가, 아래 rc 검사가 의미를 잃는다.
  set -e

  # ⚠️ repo_gpgcheck는 0이다. Grafana 공식 문서는 1을 적고 있지만 AL2023에서 그대로 쓰면
  #    저장소 자체가 무시돼 설치가 실패한다 — #121에서 실측했다:
  #      Failed to download metadata for repo 'grafana':
  #        repomd.xml GPG signature verification error: Bad GPG signature
  #      Ignoring repositories: grafana / No match for argument: alloy-...
  #    검증 대상이 다르다는 점이 중요하다. repo_gpgcheck는 저장소 메타데이터(repomd.xml)의
  #    서명을, gpgcheck는 패키지 자체의 서명을 본다. 후자는 1로 유지하므로 설치되는 RPM의
  #    무결성은 그대로 검증된다 — 끄는 것은 메타데이터 서명 확인뿐이다.
  rpm --import https://rpm.grafana.com/gpg.key
  cat > /etc/yum.repos.d/grafana.repo <<'REPOEOF'
[grafana]
name=grafana
baseurl=https://rpm.grafana.com
repo_gpgcheck=0
enabled=1
gpgcheck=1
gpgkey=https://rpm.grafana.com/gpg.key
sslverify=1
sslcacert=/etc/pki/tls/certs/ca-bundle.crt
REPOEOF

  # 버전을 핀하는 이유는 재현성이다 — 같은 커밋을 다른 날 apply했을 때 다른 에이전트가
  # 뜨면 메모리 실측(P2)의 비교 대상이 사라진다. AMI를 var.app_ami_id로 핀할 수 있게 해 둔
  # 것과 같은 이유다(asg.tf).
  #
  # RPM이 유닛(/usr/lib/systemd/system/alloy.service)·설정 경로(/etc/alloy/)·alloy 사용자와
  # adm·systemd-journal 그룹 가입까지 만든다. 그래서 deploy/prod/에 유닛 파일을 두지 않고
  # 설정 하나만 소유한다 — journald 읽기 권한도 이 그룹 가입으로 이미 갖춰진다.
  dnf install -y "alloy-${alloy_version}"

  cat > /etc/alloy/config.alloy <<'ALLOYEOF'
${alloy_config}
ALLOYEOF
  chmod 644 /etc/alloy/config.alloy

  # 접속 정보와 토큰. env/ 하위가 아니라 grafana/ 하위인 이유는 2번 섹션에 있다 —
  # env/는 일괄 조회돼 앱 .env에 KEY=VALUE로 들어가므로 앱 환경변수를 오염시킨다.
  # artifact_key를 env/ 밖에 둔 것과 같은 기준이다.
  set +x
  GRAFANA_PROM_URL=$(aws ssm get-parameter --name "${ssm_path}/grafana/prometheus_url" --query 'Parameter.Value' --output text)
  GRAFANA_PROM_USER=$(aws ssm get-parameter --name "${ssm_path}/grafana/prometheus_username" --query 'Parameter.Value' --output text)
  GRAFANA_LOKI_URL=$(aws ssm get-parameter --name "${ssm_path}/grafana/loki_url" --query 'Parameter.Value' --output text)
  GRAFANA_LOKI_USER=$(aws ssm get-parameter --name "${ssm_path}/grafana/loki_username" --query 'Parameter.Value' --output text)

  # heredoc이 아니라 printf를 쓴다. 2번 섹션의 .env 생성과 같은 형태이고, 따옴표 없는
  # heredoc이 값 안의 특수문자를 전개하는 경로를 아예 만들지 않는다.
  {
    printf 'GRAFANA_CLOUD_PROM_URL=%s\n'  "$GRAFANA_PROM_URL"
    printf 'GRAFANA_CLOUD_PROM_USER=%s\n' "$GRAFANA_PROM_USER"
    printf 'GRAFANA_CLOUD_LOKI_URL=%s\n'  "$GRAFANA_LOKI_URL"
    printf 'GRAFANA_CLOUD_LOKI_USER=%s\n' "$GRAFANA_LOKI_USER"
  } > /etc/alloy/endpoints.env

  # 토큰만 별도 파일이다. config.alloy가 password_file로 이 경로를 읽으므로 설정에 평문이
  # 남지 않고, 읽는 쪽이 TrimSpace를 하므로 --output text가 붙이는 끝 개행을 지울 필요가
  # 없다. CloudFront 개인키를 파일로 떨어뜨리는 2번 섹션과 같은 형태다.
  aws ssm get-parameter --name "${ssm_path}/grafana/token" --with-decryption \
    --query 'Parameter.Value' --output text > /etc/alloy/grafana-cloud.token
  set -x

  chmod 644 /etc/alloy/endpoints.env
  chmod 640 /etc/alloy/grafana-cloud.token
  chown root:alloy /etc/alloy/grafana-cloud.token

  # 패키지가 소유한 /etc/sysconfig/alloy를 건드리지 않고 드롭인으로 덧붙인다. 그래야
  # "우리가 넣은 값"과 "패키지 기본값(CONFIG_FILE 등)"이 파일 단위로 갈려, 어느 쪽이
  # 무엇을 정했는지 헷갈리지 않는다.
  #
  # GOMEMLIMIT은 사전 등록한 축소안 3번이다. Go 런타임은 상한을 주지 않으면 GC를 미루고
  # 반환도 늦춰서, RSS가 "실제로 필요한 양"이 아니라 "아직 돌려주지 않은 양"이 된다.
  # #121 실측에서 Alloy RSS가 248.5MB였는데(기각 구간), Grafana 공식 자원 추정으로는
  # 1,291 시리즈의 기인분이 15MiB 안팎이라 대부분이 런타임 여유분으로 보였다.
  #
  # 이 값은 하드 리밋이 아니라 GC 목표다 — 넘으면 죽는 것이 아니라 GC가 더 공격적으로
  # 돈다. 대가는 CPU를 조금 더 쓰는 것이고, 이 박스에서 Alloy의 CPU는 무시할 수준이다.
  mkdir -p /etc/systemd/system/alloy.service.d
  cat > /etc/systemd/system/alloy.service.d/10-yourtrip.conf <<'DROPINEOF'
[Service]
EnvironmentFile=/etc/alloy/endpoints.env
Environment=GOMEMLIMIT=100MiB
DROPINEOF

  systemctl daemon-reload
  systemctl enable --now alloy.service
)
ALLOY_RC=$?
set -e

if [ "$ALLOY_RC" -ne 0 ]; then
  echo "!!! Alloy setup failed (rc=$ALLOY_RC). App already started in step 5; boot continues." >&2
  echo "!!! Diagnose: systemctl status alloy / journalctl -u alloy / earlier lines in this log" >&2
fi
