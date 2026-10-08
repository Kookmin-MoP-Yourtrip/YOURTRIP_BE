#!/usr/bin/env bash
# 장애 격리 주실험(#197) — 회차 하나를 처음부터 끝까지 돌린다. 개발 PC(Git Bash)에서 실행한다.
#
# 설계: docs/tasks/ai-bulkhead-loadtest/README.md 10-4절. 순서:
#   1. 토큰 발급(서버와 같은 1시간짜리 — 회차마다 새로)
#   2. SSM artifact_key 를 그 회차 버전으로 → instance refresh → Successful · 타깃 healthy 대기
#      (매 회차 새 인스턴스. 같은 버전이 연속돼도 교체한다 — 5-6절)
#   3. 새 인스턴스 점검: 의도한 JAR 이 떴는가, 관리 포트(8081)가 따로 떴는가, ReplaceUnhealthy 가 멈춰 있는가
#   4. 캐시 키만 삭제 → 서버 쪽 수집 시작
#   5. k6 EC2 에서 회차 실행(약 15분)
#   6. 수집 종료 → 자료 회수 → ALB CloudWatch 지표 → 집계·판정
#
# 사용:  bash scripts/loadtest/bulkhead-round.sh <A|B|C|D> <분당 도착률> <반복 번호>
#   예:  bash scripts/loadtest/bulkhead-round.sh A 40 1      → results/ai-bulkhead/r40-A1/
#   D 는 일수 가중 입장(#200)이다. 여행 일수를 섞으려면 DAYS_MIX 를 환경변수로 넘긴다(비우면 3일 고정):
#        DAYS_MIX=1:20,2:20,3:20,4:20,5:20 bash scripts/loadtest/bulkhead-round.sh D 40 m1
# 설정:  results/ai-bulkhead/config.env (scripts/loadtest/bulkhead.env.example 참고)
#
# 이 스크립트가 하지 않는 것: 인프라 생성·철거, SSM env/ 설정, health_check_path 변경, ASG 프로세스 중지.
# 그것들은 회차가 아니라 측정 전체의 앞뒤에 한 번씩 하는 일이다(10-2·10-5절).
set -euo pipefail

ARM="${1:?A|B|C|D}"
RATE="${2:?분당 도착률}"
REP="${3:?반복 번호}"
case "$ARM" in A|B|C|D) ;; *) echo "arm 은 A·B·C·D 중 하나(C 는 비교 실험 #201, D 는 일수 가중 입장 #200)" >&2; exit 2 ;; esac
# 본 도착의 여행 일수 분포(ai-bulkhead.js 의 DAYS_MIX). 같은 시드면 일수 순서도 같아 arm 끼리 그대로 비교된다.
DAYS_MIX="${DAYS_MIX:-}"

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
CONFIG="$REPO/results/ai-bulkhead/config.env"
[ -f "$CONFIG" ] || { echo "설정 파일이 없다: $CONFIG" >&2; exit 2; }
# shellcheck disable=SC1090
set -a; . "$CONFIG"; set +a
# Git Bash 의 HOME 이 실제 사용자 홈과 다를 수 있어 AWS 설정 파일 위치를 명시한다.
export AWS_CONFIG_FILE="${AWS_CONFIG_FILE:-$USERPROFILE/.aws/config}"
export AWS_SHARED_CREDENTIALS_FILE="${AWS_SHARED_CREDENTIALS_FILE:-$USERPROFILE/.aws/credentials}"
export AWS_PROFILE AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION"
# Git Bash 는 '/' 로 시작하는 인자(와 'Key=/...' 의 값)를 Windows 경로로 바꿔 Windows 실행 파일에 넘긴다.
# 그러면 aws 가 SSM 이름 /yourtrip/prod/... 를 'C:/Program Files/Git/yourtrip/...' 로 받아 ParameterNotFound 가
# 난다(실제로 겪었다). 그래서 변환을 끄고, Windows 실행 파일(python·aws file://)에 넘기는 로컬 경로만 win() 으로
# 직접 바꾼다. ssh·scp·tar 는 Git Bash 자체 도구라 /c/... 를 그대로 이해한다 — 오히려 C:/... 로 넘기면
# 'C:' 를 원격 호스트로 읽는다.
export MSYS_NO_PATHCONV=1
win() { cygpath -m "$1"; }

LABEL="r${RATE}-${ARM}${REP}"
# 같은 단계의 A·B 는 같은 시드 — 정확히 같은 도착을 받는다.
SEED=$((197000 + RATE))
OUT="$REPO/results/ai-bulkhead/$LABEL"
[ ! -e "$OUT" ] || { echo "이미 있다: $OUT — 다시 재려면 디렉터리 이름을 바꿔 보관한 뒤 실행한다" >&2; exit 2; }
mkdir -p "$OUT"
LOG="$OUT/round.log"
log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }

case "$ARM" in
  A) KEY="$ARTIFACT_KEY_A" ;;
  B) KEY="$ARTIFACT_KEY_B" ;;
  C) KEY="${ARTIFACT_KEY_C:?config.env 에 ARTIFACT_KEY_C 가 없다(비교 실험 #201)}" ;;
  D) KEY="${ARTIFACT_KEY_D:?config.env 에 ARTIFACT_KEY_D 가 없다(일수 가중 입장 #200)}" ;;
esac
KNOWN="$OUT/known_hosts"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$KNOWN" -o ConnectTimeout=30 -o ServerAliveInterval=30)
# 앱 인스턴스는 공인 IP·보안그룹이 아니라 SSM 세션으로 SSH 를 터널링한다(호스트 자리에 인스턴스 ID).
# 운영 보안그룹의 SSH 허용 IP 를 바꾸려면 terraform apply 가 필요한데, 그 apply 는 CLI 로 멈춘
# ASG 프로세스(ReplaceUnhealthy·AlarmNotification)를 drift 로 보고 되살린다 — 측정 중에는 운영 apply 를 하지 않는다.
APP_PROXY=(-o "ProxyCommand=aws ssm start-session --target %h --document-name AWS-StartSSHSession --parameters portNumber=%p")
app_ssh() { ssh "${SSH_OPTS[@]}" "${APP_PROXY[@]}" -i "$REPO/$PROD_SSH_KEY" "$SSH_USER@$INSTANCE_ID" "$@"; }
# SSM 터널 위 scp 는 가끔 전송 도중 멈춘 채 끝나지 않는다(r40-A1: 9.8MB 중 4.1MB 에서 45분 정지, 다시 받으니
# 수 초 만에 끝났다). ServerAlive 는 터널 프로세스가 살아 있어 이를 못 잡는다. 그래서 시간 제한을 두고 다시 받는다.
app_scp() {
  local i
  for i in 1 2 3; do
    timeout 180 scp -q "${SSH_OPTS[@]}" "${APP_PROXY[@]}" -i "$REPO/$PROD_SSH_KEY" "$@" && return 0
    echo "scp 실패·시간 초과(시도 $i/3) — 다시 받는다" >&2
  done
  return 1
}
k6_ssh() { ssh "${SSH_OPTS[@]}" -i "$REPO/$K6_SSH_KEY" "$SSH_USER@$K6_HOST" "$@"; }
k6_scp() { scp -q "${SSH_OPTS[@]}" -i "$REPO/$K6_SSH_KEY" "$@"; }

log "회차 $LABEL 시작 — arm $ARM ($KEY), 분당 $RATE 건, seed $SEED, 일수 분포 ${DAYS_MIX:-3일 고정}"

# ── 0. 전제 확인 ─────────────────────────────────────────────────────────────
ASG_COUNT=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query 'length(AutoScalingGroups)' --output text)
[ "$ASG_COUNT" = "1" ] || { log "중단: 운영 ASG($ASG_NAME)가 없다 — terraform/prod 를 먼저 올린다"; exit 3; }
SUSPENDED=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query 'AutoScalingGroups[0].SuspendedProcesses[].ProcessName' --output text)
# ReplaceUnhealthy: 헬스체크 실패로 인스턴스가 교체되면 회차가 끊긴다(교체는 시연 측정에서만 본다).
# AlarmNotification: 요청 수 기반 확장 정책(aws_autoscaling_policy.request_count)이 부하에 반응해 2대로 늘리면
# '서버 1대' 전제가 측정 도중에 깨진다. instance refresh 는 이 둘과 무관하게 동작한다.
for proc in ReplaceUnhealthy AlarmNotification; do
  grep -qw "$proc" <<< "$SUSPENDED" \
    || { log "중단: $proc 가 일시 중지돼 있지 않다(본측정 전제, 설계 문서 10-2)"; exit 3; }
done

# ── 1. 토큰 ──────────────────────────────────────────────────────────────────
# 비밀키는 SSM 에서 읽어 gradle 프로세스 환경에만 넘긴다. 화면·파일·로그에 남기지 않는다.
log "토큰 발급(사용자 $LOADTEST_USER_ID)"
TOKEN_FILE="$OUT/jwt.txt"
( cd "$REPO" && JWT_SECRET="$(aws ssm get-parameter --name "$SSM_JWT_SECRET_PARAM" --with-decryption \
      --query Parameter.Value --output text)" \
    LOADTEST_TOKEN_FILE="$(win "$TOKEN_FILE")" \
    ./gradlew benchmarkTest --tests '*LoadTestTokenIssuer*' --rerun -q ) >> "$LOG" 2>&1
[ -s "$TOKEN_FILE" ] || { log "중단: 토큰 파일이 비었다"; exit 3; }
chmod 600 "$TOKEN_FILE" 2>/dev/null || true

# ── 2. 배포 ──────────────────────────────────────────────────────────────────
log "artifact_key → $KEY, instance refresh 시작"
aws ssm put-parameter --name "$SSM_ARTIFACT_KEY_PARAM" --value "$KEY" --type String --overwrite >/dev/null
REFRESH_ID=$(aws autoscaling start-instance-refresh --auto-scaling-group-name "$ASG_NAME" \
  --preferences "file://$(win "$REPO/deploy/prod/instance-refresh-preferences.json")" \
  --query InstanceRefreshId --output text)
# 진행 중인 refresh 는 취소하지 않는다(docs/guide/cd.md — 취소가 정상 인스턴스를 죽인 사고가 있었다).
DEADLINE=$(( $(date +%s) + 1500 ))
while :; do
  STATUS=$(aws autoscaling describe-instance-refreshes --auto-scaling-group-name "$ASG_NAME" \
    --instance-refresh-ids "$REFRESH_ID" --query 'InstanceRefreshes[0].Status' --output text)
  case "$STATUS" in
    Successful) break ;;
    Failed|Cancelled|RollbackFailed|RollbackSuccessful) log "중단: instance refresh $STATUS"; exit 3 ;;
  esac
  [ "$(date +%s)" -lt "$DEADLINE" ] || { log "중단: refresh 25분 초과(취소하지 않았다 — 상태를 직접 확인한다)"; exit 3; }
  sleep 15
done
log "instance refresh Successful"

INSTANCE_ID=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query "AutoScalingGroups[0].Instances[?LifecycleState=='InService'].InstanceId | [0]" --output text)
TG_ARN=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query 'AutoScalingGroups[0].TargetGroupARNs[0]' --output text)
# refresh 가 끝나도 타깃이 healthy 가 못 되면(앱 기동 실패 등) 회차를 멈춘다 — 무한 대기하면 plan 도 같이 멈춘다
HEALTH_DEADLINE=$(( $(date +%s) + 600 ))
until [ "$(aws elbv2 describe-target-health --target-group-arn "$TG_ARN" \
    --query "TargetHealthDescriptions[?Target.Id=='$INSTANCE_ID'].TargetHealth.State | [0]" --output text)" = healthy ]; do
  [ "$(date +%s)" -lt "$HEALTH_DEADLINE" ] || { log "중단: 타깃 healthy 대기 10분 초과 — $INSTANCE_ID"; exit 3; }
  sleep 10
done
log "타깃 healthy — $INSTANCE_ID"

# ── 3. 새 인스턴스 점검 ──────────────────────────────────────────────────────
DEPLOYED=$(app_ssh "sudo grep -h 'downloaded artifact:' /var/log/cloud-init-output.log | tail -1")
case "$DEPLOYED" in
  *"$KEY"*) log "배포 확인: $DEPLOYED" ;;
  *) log "중단: 의도한 JAR 이 아니다 — $DEPLOYED"; exit 3 ;;
esac
# 헬스체크 경로(8080 /actuator)가 liveness 만 담는지까지 본다 — 전체 health 면 DB 순간 장애에 교체 폭풍이 된다.
LIVENESS=$(app_ssh "curl -sf http://localhost:8080/actuator" || true)
app_ssh "curl -sf -o /dev/null http://localhost:8081/actuator/prometheus" \
  && [ "$LIVENESS" = '{"status":"UP","components":{"livenessState":{"status":"UP"}}}' ] \
  || { log "중단: 관리 포트(8081) 또는 헬스체크 경로(8080 /actuator)가 기대와 다르다: $LIVENESS (설계 문서 6-2)"; exit 3; }

# ── 4. 캐시 삭제 · 수집 시작 ─────────────────────────────────────────────────
app_ssh "mkdir -p /tmp/bulkhead"
app_scp "$REPO/scripts/loadtest/bulkhead-collect.sh" "$REPO/scripts/loadtest/poll-metrics.sh" \
  "$REPO/scripts/loadtest/bulkhead-cache-evict.py" "$SSH_USER@$INSTANCE_ID:/tmp/bulkhead/"
app_ssh "sudo python3 /tmp/bulkhead/bulkhead-cache-evict.py" | tee -a "$LOG"
REMOTE_OUT="/var/tmp/bulkhead/$LABEL"
app_ssh "sudo mkdir -p /var/tmp/bulkhead && sudo bash /tmp/bulkhead/bulkhead-collect.sh start $REMOTE_OUT" | tee -a "$LOG"

# ── 5. k6 ────────────────────────────────────────────────────────────────────
# k6 EC2 의 저장소를 이 회차의 스크립트 버전으로 맞춘다. 부팅 때 한 번 clone 한 뒤로는 갱신되지 않아,
# 측정 도중 스크립트를 고치면 k6 EC2 만 옛 버전으로 돌게 된다.
k6_ssh "cd /opt/app && sudo git fetch -q --depth 1 origin '$K6_GIT_REF' && sudo git checkout -q FETCH_HEAD && sudo git log -1 --format='k6 스크립트 버전 %h'" | tee -a "$LOG"
log "k6 실행(약 12분)"
k6_scp "$TOKEN_FILE" "$SSH_USER@$K6_HOST:/tmp/$LABEL.jwt"
k6_ssh "chmod 600 /tmp/$LABEL.jwt"
START_EPOCH=$(date +%s)
set +e
k6_ssh "cd /opt/app && k6 run -q -e BASE_URL='$BASE_URL' -e JWT=\"\$(cat /tmp/$LABEL.jwt)\" \
  -e RATE_PER_MIN=$RATE -e SEED=$SEED -e LABEL=$LABEL -e DAYS_MIX='$DAYS_MIX' \
  -e DETAIL_ID_MIN=$DETAIL_ID_MIN -e DETAIL_ID_MAX=$DETAIL_ID_MAX \
  --summary-export=/tmp/$LABEL.summary.json scripts/k6/ai-bulkhead.js 2> /tmp/$LABEL.k6.log > /dev/null"
K6_EXIT=$?
set -e
END_EPOCH=$(date +%s)
k6_ssh "rm -f /tmp/$LABEL.jwt"
log "k6 종료(exit $K6_EXIT)"

# ── 6. 회수 · 판정 ───────────────────────────────────────────────────────────
app_ssh "sudo bash /tmp/bulkhead/bulkhead-collect.sh stop $REMOTE_OUT" | tee -a "$LOG"
app_scp "$SSH_USER@$INSTANCE_ID:$REMOTE_OUT.tar.gz" "$OUT/server.tar.gz"
tar -xzf "$OUT/server.tar.gz" -C "$OUT"
k6_scp "$SSH_USER@$K6_HOST:/tmp/$LABEL.k6.log" "$SSH_USER@$K6_HOST:/tmp/$LABEL.summary.json" "$OUT/"
rm -f "$TOKEN_FILE"

# ALB 지표(1분 단위). 집계기가 아니라 사람이 보는 보조 자료다 — 헬스체크 판정(J5)의 근거.
LB_DIM=$(aws elbv2 describe-target-groups --target-group-arns "$TG_ARN" \
  --query 'TargetGroups[0].LoadBalancerArns[0]' --output text | sed 's#.*:loadbalancer/##')
TG_DIM=$(echo "$TG_ARN" | sed 's#.*:##')
CW_START=$(date -u -d "@$((START_EPOCH - 60))" +%Y-%m-%dT%H:%M:%SZ)
CW_END=$(date -u -d "@$((END_EPOCH + 120))" +%Y-%m-%dT%H:%M:%SZ)
for spec in "UnHealthyHostCount Maximum tg" "HealthyHostCount Minimum tg" \
            "HTTPCode_ELB_5XX_Count Sum lb" "HTTPCode_Target_5XX_Count Sum tg"; do
  set -- $spec
  DIMS="Name=LoadBalancer,Value=$LB_DIM"
  [ "$3" = tg ] && DIMS="$DIMS Name=TargetGroup,Value=$TG_DIM"
  aws cloudwatch get-metric-statistics --namespace AWS/ApplicationELB --metric-name "$1" \
    --dimensions $DIMS --start-time "$CW_START" --end-time "$CW_END" --period 60 --statistics "$2" \
    --output json > "$OUT/cw-$1.json"
done

set +e
python "$(win "$REPO/scripts/loadtest/aggregate-bulkhead.py")" \
  --k6-log "$(win "$OUT/$LABEL.k6.log")" --k6-summary "$(win "$OUT/$LABEL.summary.json")" \
  --poll "$(win "$OUT/$LABEL/metrics.prom")" --dumps-dir "$(win "$OUT/$LABEL/dumps")" \
  --journal "$(win "$OUT/$LABEL/app.log")" --label "$LABEL" --out-json "$(win "$OUT/result.json")" | tee -a "$LOG"
AGG_EXIT=${PIPESTATUS[0]}
set -e
UNHEALTHY_MAX=$(python -c "import json,sys;d=json.load(open(sys.argv[1]));print(max([p['Maximum'] for p in d['Datapoints']] or [0]))" "$(win "$OUT/cw-UnHealthyHostCount.json")")
log "UnHealthyHostCount 최대 $UNHEALTHY_MAX (J5)"
printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$(date '+%F %T')" "$LABEL" "$ARM" "$RATE" "$AGG_EXIT" "$UNHEALTHY_MAX" \
  >> "$REPO/results/ai-bulkhead/index.tsv"
if [ "$AGG_EXIT" -ne 0 ]; then
  log "회차 $LABEL 무효 — 같은 자리에서 다시 잰다(설계 문서 9-1)"
  exit 4
fi
log "회차 $LABEL 완료"
