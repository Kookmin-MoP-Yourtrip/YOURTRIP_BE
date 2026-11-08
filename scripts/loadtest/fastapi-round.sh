#!/usr/bin/env bash
# 빠른 API 계단 부하 회차 하나를 돌린다. 개발 PC(Git Bash)에서 실행한다.
#
# 설계: docs/tasks/ai-bulkhead-loadtest/async-variant.md 3-7절
#   T = 플랫폼 스레드(Tomcat 워커 32), V = 가상 스레드. 같은 JAR 에 환경변수만 다르다. AI 요청은 보내지 않는다.
#
# 사용:  bash scripts/loadtest/fastapi-round.sh <T|V> <반복 번호>   → results/ai-bulkhead/s-T1/
# 설정:  results/ai-bulkhead/config.env. SSM 관리 포트 설정·헬스체크 경로·ASG 프로세스 중지는 측정 전체의 앞뒤에서 한다.
set -euo pipefail

ARM="${1:?T|V}"
REP="${2:?반복 번호}"
case "$ARM" in T|V) ;; *) echo "arm 은 T 또는 V" >&2; exit 2 ;; esac

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck disable=SC1091
set -a; . "$REPO/results/ai-bulkhead/config.env"; set +a
export AWS_CONFIG_FILE="${AWS_CONFIG_FILE:-$USERPROFILE/.aws/config}" AWS_SHARED_CREDENTIALS_FILE="${AWS_SHARED_CREDENTIALS_FILE:-$USERPROFILE/.aws/credentials}"
export AWS_PROFILE AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION" MSYS_NO_PATHCONV=1
win() { cygpath -m "$1"; }

LABEL="s-$ARM$REP"
OUT="$REPO/results/ai-bulkhead/$LABEL"
[ ! -e "$OUT" ] || { echo "이미 있다: $OUT" >&2; exit 2; }
mkdir -p "$OUT"
LOG="$OUT/round.log"
log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }
KEY="${ARTIFACT_KEY_ASYNC:?config.env 에 ARTIFACT_KEY_ASYNC 가 없다}"

# shellcheck disable=SC1091
. "$REPO/scripts/loadtest/ssm-arm-env.sh"

KNOWN="$OUT/known_hosts"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$KNOWN" -o ConnectTimeout=30 -o ServerAliveInterval=30)
APP_PROXY=(-o "ProxyCommand=aws ssm start-session --target %h --document-name AWS-StartSSHSession --parameters portNumber=%p")
app_ssh() { ssh "${SSH_OPTS[@]}" "${APP_PROXY[@]}" -i "$REPO/$PROD_SSH_KEY" "$SSH_USER@$INSTANCE_ID" "$@"; }
app_scp() {
  local i
  for i in 1 2 3; do
    timeout 180 scp -q "${SSH_OPTS[@]}" "${APP_PROXY[@]}" -i "$REPO/$PROD_SSH_KEY" "$@" && return 0
    echo "scp 실패·시간 초과(시도 $i/3)" >&2
  done
  return 1
}
k6_ssh() { ssh "${SSH_OPTS[@]}" -i "$REPO/$K6_SSH_KEY" "$SSH_USER@$K6_HOST" "$@"; }
k6_scp() { scp -q "${SSH_OPTS[@]}" -i "$REPO/$K6_SSH_KEY" "$@"; }

log "회차 $LABEL 시작 — JAR $KEY"
SUSPENDED=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query 'AutoScalingGroups[0].SuspendedProcesses[].ProcessName' --output text)
for proc in ReplaceUnhealthy AlarmNotification; do
  grep -qw "$proc" <<< "$SUSPENDED" || { log "중단: $proc 가 일시 중지돼 있지 않다"; exit 3; }
done

TOKEN_FILE="$OUT/jwt.txt"
( cd "$REPO" && JWT_SECRET="$(aws ssm get-parameter --name "$SSM_JWT_SECRET_PARAM" --with-decryption \
      --query Parameter.Value --output text)" \
    LOADTEST_TOKEN_FILE="$(win "$TOKEN_FILE")" \
    ./gradlew benchmarkTest --tests '*LoadTestTokenIssuer*' --rerun -q ) >> "$LOG" 2>&1
[ -s "$TOKEN_FILE" ] || { log "중단: 토큰 파일이 비었다"; exit 3; }
trap 'rm -f "$TOKEN_FILE"' EXIT

# 두 arm 은 스레드 모델만 다르다. 입장 제한·비동기 값은 AI 요청이 없어 결과에 영향이 없다.
case "$ARM" in
  T) set_arm_env false 100000 100000 false ;;
  V) set_arm_env false 100000 100000 true ;;
esac
aws ssm put-parameter --name "$SSM_ARTIFACT_KEY_PARAM" --value "$KEY" --type String --overwrite >/dev/null
REFRESH_ID=$(aws autoscaling start-instance-refresh --auto-scaling-group-name "$ASG_NAME" \
  --preferences "file://$(win "$REPO/deploy/prod/instance-refresh-preferences.json")" \
  --query InstanceRefreshId --output text)
log "instance refresh 시작 $REFRESH_ID"
DEADLINE=$(( $(date +%s) + 1500 ))
while :; do
  STATUS=$(aws autoscaling describe-instance-refreshes --auto-scaling-group-name "$ASG_NAME" \
    --instance-refresh-ids "$REFRESH_ID" --query 'InstanceRefreshes[0].Status' --output text)
  case "$STATUS" in
    Successful) break ;;
    Failed|Cancelled|RollbackFailed|RollbackSuccessful) log "중단: instance refresh $STATUS"; exit 3 ;;
  esac
  [ "$(date +%s)" -lt "$DEADLINE" ] || { log "중단: refresh 25분 초과(취소하지 않았다)"; exit 3; }
  sleep 15
done
INSTANCE_ID=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query "AutoScalingGroups[0].Instances[?LifecycleState=='InService'].InstanceId | [0]" --output text)
TG_ARN=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query 'AutoScalingGroups[0].TargetGroupARNs[0]' --output text)
log "교체 완료 — $INSTANCE_ID"

DEPLOYED=$(app_ssh "sudo grep -h 'downloaded artifact:' /var/log/cloud-init-output.log | tail -1")
case "$DEPLOYED" in *"$KEY"*) log "배포 확인: $DEPLOYED" ;; *) log "중단: 의도한 JAR 이 아니다 — $DEPLOYED"; exit 3 ;; esac
app_ssh "curl -sf -o /dev/null http://localhost:8081/actuator/prometheus" || { log "중단: 관리 포트 8081 이 없다"; exit 3; }
if [ "$ARM" = V ]; then
  PICKED=$(app_ssh "sudo journalctl -u yourtrip-app --no-pager | grep -m1 'Picked up JAVA_TOOL_OPTIONS'" || true)
  case "$PICKED" in *tracePinnedThreads*) log "피닝 추적 적용 확인" ;; *) log "중단: 피닝 추적이 실리지 않았다 — $PICKED"; exit 3 ;; esac
  MAXT=$(app_ssh "curl -s http://localhost:8081/actuator/prometheus | awk '/^tomcat_threads_config_max_threads/{print \$2; exit}'")
  [ "$MAXT" = "-1.0" ] || { log "중단: 가상 스레드가 켜지지 않았다(Tomcat 최대 $MAXT)"; exit 3; }
else
  MAXT=$(app_ssh "curl -s http://localhost:8081/actuator/prometheus | awk '/^tomcat_threads_config_max_threads/{print \$2; exit}'")
  [ "$MAXT" = "32.0" ] || { log "중단: Tomcat 워커가 32 가 아니다($MAXT)"; exit 3; }
fi
log "Tomcat 최대 스레드 지표 $MAXT"

app_ssh "mkdir -p /tmp/bulkhead"
app_scp "$REPO/scripts/loadtest/bulkhead-collect.sh" "$REPO/scripts/loadtest/poll-metrics.sh" \
  "$REPO/scripts/loadtest/bulkhead-cache-evict.py" "$SSH_USER@$INSTANCE_ID:/tmp/bulkhead/"
app_ssh "sudo python3 /tmp/bulkhead/bulkhead-cache-evict.py" | tee -a "$LOG"
REMOTE_OUT="/var/tmp/bulkhead/$LABEL"
app_ssh "sudo mkdir -p /var/tmp/bulkhead && sudo bash /tmp/bulkhead/bulkhead-collect.sh start $REMOTE_OUT" | tee -a "$LOG"

# k6 스크립트는 push 없이 로컬 사본을 쓴다
# 빠르게 이어지는 SSH 직후 scp 가 'Connection closed' 로 끊긴 적이 있어(s-T1 첫 시도) 다시 시도한다
for attempt in 1 2 3; do
  k6_ssh "rm -rf /tmp/k6-local" && k6_scp -r "$REPO/scripts/k6" "$SSH_USER@$K6_HOST:/tmp/k6-local" && break
  [ "$attempt" -lt 3 ] || { log "중단: k6 스크립트 복사 실패"; exit 3; }
  sleep 10
done
k6_ssh "sudo cp -r /tmp/k6-local/. /opt/app/scripts/k6/"
k6_scp "$TOKEN_FILE" "$SSH_USER@$K6_HOST:/tmp/$LABEL.jwt"
k6_ssh "chmod 600 /tmp/$LABEL.jwt; nohup vmstat 5 > /tmp/$LABEL.vmstat 2>&1 < /dev/null & echo \$! > /tmp/$LABEL.vmstat.pid"
log "k6 실행(계단 8개 12분 + 회복 2분)"
START_EPOCH=$(date +%s)
set +e
k6_ssh "ulimit -n 65535 2>/dev/null; cd /opt/app && k6 run -q --log-output=none --console-output=/tmp/$LABEL.k6.log -e BASE_URL='$BASE_URL' -e JWT=\"\$(cat /tmp/$LABEL.jwt)\" \
  -e LABEL=$LABEL -e DETAIL_ID_MIN=$DETAIL_ID_MIN -e DETAIL_ID_MAX=$DETAIL_ID_MAX -e STEPS='${STEPS:-200,400,700,1000,1400,1800,2300,2800}' \
  --summary-export=/tmp/$LABEL.summary.json scripts/k6/fast-api-ramp.js 2> /tmp/$LABEL.k6.stderr > /dev/null"
K6_EXIT=$?
set -e
END_EPOCH=$(date +%s)
k6_ssh "kill \$(cat /tmp/$LABEL.vmstat.pid) 2>/dev/null; rm -f /tmp/$LABEL.jwt"
log "k6 종료(exit $K6_EXIT)"
[ "$K6_EXIT" -eq 0 ] || { log "중단: k6 가 비정상 종료했다 — $(k6_ssh "tail -2 /tmp/$LABEL.k6.stderr" | cut -c1-200)"; app_ssh "sudo bash /tmp/bulkhead/bulkhead-collect.sh stop $REMOTE_OUT" >/dev/null || true; exit 3; }

app_ssh "sudo bash /tmp/bulkhead/bulkhead-collect.sh stop $REMOTE_OUT" | tee -a "$LOG"
app_scp "$SSH_USER@$INSTANCE_ID:$REMOTE_OUT.tar.gz" "$OUT/server.tar.gz"
tar -xzf "$OUT/server.tar.gz" -C "$OUT"
k6_scp "$SSH_USER@$K6_HOST:/tmp/$LABEL.k6.log" "$SSH_USER@$K6_HOST:/tmp/$LABEL.summary.json" "$SSH_USER@$K6_HOST:/tmp/$LABEL.vmstat" "$OUT/"
rm -f "$TOKEN_FILE"

LB_DIM=$(aws elbv2 describe-target-groups --target-group-arns "$TG_ARN" \
  --query 'TargetGroups[0].LoadBalancerArns[0]' --output text | sed 's#.*:loadbalancer/##')
TG_DIM=$(echo "$TG_ARN" | sed 's#.*:##')
CW_START=$(date -u -d "@$((START_EPOCH - 60))" +%Y-%m-%dT%H:%M:%SZ)
CW_END=$(date -u -d "@$((END_EPOCH + 120))" +%Y-%m-%dT%H:%M:%SZ)
sleep 90
for spec in "UnHealthyHostCount Maximum tg" "HTTPCode_ELB_5XX_Count Sum lb" "HTTPCode_Target_5XX_Count Sum tg" "RequestCount Sum lb" "RejectedConnectionCount Sum lb"; do
  set -- $spec
  DIMS="Name=LoadBalancer,Value=$LB_DIM"
  [ "$3" = tg ] && DIMS="$DIMS Name=TargetGroup,Value=$TG_DIM"
  aws cloudwatch get-metric-statistics --namespace AWS/ApplicationELB --metric-name "$1" \
    --dimensions $DIMS --start-time "$CW_START" --end-time "$CW_END" --period 60 --statistics "$2" \
    --output json > "$OUT/cw-$1.json"
done

set +e
python "$(win "$REPO/scripts/loadtest/aggregate-fastapi.py")" \
  --k6-log "$(win "$OUT/$LABEL.k6.log")" --k6-summary "$(win "$OUT/$LABEL.summary.json")" \
  --poll "$(win "$OUT/$LABEL/metrics.prom")" --journal "$(win "$OUT/$LABEL/app.log")" \
  --k6-vmstat "$(win "$OUT/$LABEL.vmstat")" --label "$LABEL" --out-json "$(win "$OUT/result.json")" | tee -a "$LOG"
AGG_EXIT=${PIPESTATUS[0]}
set -e
log "UnHealthyHostCount 최대 $(python -c "import json,sys;d=json.load(open(sys.argv[1]));print(max([p['Maximum'] for p in d['Datapoints']] or [0]))" "$(win "$OUT/cw-UnHealthyHostCount.json")")"
log "회차 $LABEL 완료(집계 exit $AGG_EXIT)"
exit "$AGG_EXIT"
