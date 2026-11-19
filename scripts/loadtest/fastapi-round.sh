#!/usr/bin/env bash
# 빠른 API 계단 부하 회차 하나를 돌린다. 개발 PC(Git Bash)에서 실행한다.
#
# 설계: docs/tasks/ai-bulkhead-loadtest/async-variant.md 3-7절
#   T = 플랫폼 스레드(Tomcat 워커 32), V = 가상 스레드. 같은 JAR 에 환경변수만 다르다. AI 요청은 보내지 않는다.
#   W = 플랫폼 스레드(Tomcat 워커 200, Spring Boot 기본값) — 상한이 크면 무엇이 달라지는지 본다(3-8절).
#   WS2·WS8 = 워커 200 + Redis 공유 연결 2·8개(WS8 은 Lettuce I/O 스레드도 8) — 루프 수의 효과를 본다(3-9절).
#             라벨은 s-WS2·s-WS8 이고, 같은 구성을 다시 재면 s-WS2r2 처럼 반복 번호를 붙인다.
#
# 사용:  bash scripts/loadtest/fastapi-round.sh <T|V|W|WS2|WS8> <반복 번호>   → results/ai-bulkhead/s-T1/
# 설정:  results/ai-bulkhead/config.env. SSM 관리 포트 설정·헬스체크 경로·ASG 프로세스 중지는 측정 전체의 앞뒤에서 한다.
set -euo pipefail

ARM="${1:?T|V|W|WS2|WS8}"
REP="${2:?반복 번호}"
case "$ARM" in T|V|W|WS2|WS8) ;; *) echo "arm 은 T, V, W, WS2, WS8 중 하나" >&2; exit 2 ;; esac
# WS 계열은 이름에 공유 연결 수가 들어 있어 반복 번호를 그대로 붙이면 헷갈린다(s-WS21).
SHARED=""; IO_THREADS=""
case "$ARM" in WS2) SHARED=2 ;; WS8) SHARED=8; IO_THREADS=8 ;; esac

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck disable=SC1091
set -a; . "$REPO/results/ai-bulkhead/config.env"; set +a
export AWS_CONFIG_FILE="${AWS_CONFIG_FILE:-$USERPROFILE/.aws/config}" AWS_SHARED_CREDENTIALS_FILE="${AWS_SHARED_CREDENTIALS_FILE:-$USERPROFILE/.aws/credentials}"
export AWS_PROFILE AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION" MSYS_NO_PATHCONV=1
win() { cygpath -m "$1"; }

LABEL="s-$ARM$REP"
if [ -n "$SHARED" ]; then LABEL="s-$ARM"; [ "$REP" = 1 ] || LABEL="s-${ARM}r$REP"; fi
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

# 세 arm 은 스레드 모델·워커 수만 다르다. 입장 제한·비동기 값은 AI 요청이 없어 결과에 영향이 없다.
# W 의 워커 수는 SSM 환경변수가 application-prod.yml 의 max: 32 를 덮어써 정한다(OS 환경변수가 우선한다).
case "$ARM" in
  T) set_arm_env false 100000 100000 false ;;
  V) set_arm_env false 100000 100000 true ;;
  W) set_arm_env false 100000 100000 false 200 ;;
  WS2|WS8) set_arm_env false 100000 100000 false 200 "$SHARED" "$IO_THREADS" ;;
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
  # 관리 포트(8081)의 Tomcat 도 같은 지표를 내므로 앱 커넥터(8080) 줄만 본다.
  WANT=32.0; [ "$ARM" = T ] || WANT=200.0
  MAXT=$(app_ssh "curl -s http://localhost:8081/actuator/prometheus | awk '/^tomcat_threads_config_max_threads/ && /8080/{print \$2; exit}'")
  [ "$MAXT" = "$WANT" ] || { log "중단: Tomcat 워커가 $WANT 이 아니다($MAXT)"; exit 3; }
fi
log "Tomcat 최대 스레드 지표 $MAXT"

# Redis 공유 연결 구성 확인(3-9절). 연결은 처음 쓰일 때 만들어지므로, 캐시를 건드리지 않는 헬스 엔드포인트로
# Redis 연결을 한 번 끌어낸 뒤 앱 로그와 맺힌 연결 수를 본다. W 는 토글이 꺼져 있어야 한다.
app_ssh "curl -s -o /dev/null http://localhost:8081/actuator/health" || true
SHARED_LOG=$(app_ssh "sudo journalctl -u yourtrip-app --no-pager | grep -o 'Redis 공유 연결 [0-9]*개 생성' | tail -1" || true)
REDIS_CONNS=$(app_ssh "ss -Htn state established '( dport = :6379 )' | wc -l" || echo "?")
if [ -n "$SHARED" ]; then
  [ "$SHARED_LOG" = "Redis 공유 연결 ${SHARED}개 생성" ] || { log "중단: 공유 연결 $SHARED 개가 만들어지지 않았다 — '$SHARED_LOG'"; exit 3; }
  [ "$REDIS_CONNS" -ge "$SHARED" ] 2>/dev/null || { log "중단: Redis 연결이 $REDIS_CONNS 개뿐이다(기대 $SHARED 이상)"; exit 3; }
  if [ -n "$IO_THREADS" ]; then
    PICKED=$(app_ssh "sudo journalctl -u yourtrip-app --no-pager | grep -m1 'Picked up JAVA_TOOL_OPTIONS'" || true)
    case "$PICKED" in *"eventLoopThreads=$IO_THREADS"*) log "I/O 스레드 수 적용 확인" ;; *) log "중단: I/O 스레드 옵션이 실리지 않았다 — $PICKED"; exit 3 ;; esac
  fi
else
  [ -z "$SHARED_LOG" ] || { log "중단: 대조 arm 인데 공유 연결 토글이 켜져 있다 — $SHARED_LOG"; exit 3; }
fi
log "Redis 연결 ${REDIS_CONNS}개(공유 연결 설정 ${SHARED:-1})"

# Lettuce 이벤트 루프 스레드별 실행 시간 — k6 앞뒤 차이로 루프 분산을 확인한다(3-9절 유효성 게이트).
# comm 은 15자로 잘려 루프끼리 이름이 같으므로 tid 로 구분한다.
lettuce_sched() {
  app_ssh "PID=\$(systemctl show -p MainPID --value yourtrip-app); for t in /proc/\$PID/task/*; do c=\$(cat \$t/comm 2>/dev/null); case \"\$c\" in lettuce-epoll*|lettuce-nioEv*) echo \"\${t##*/} \$c \$(cat \$t/schedstat)\" ;; esac; done" > "$1" || true
}

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
k6_ssh "rm -f /tmp/$LABEL.k6.log /tmp/$LABEL.summary.json"
k6_scp "$TOKEN_FILE" "$SSH_USER@$K6_HOST:/tmp/$LABEL.jwt"
k6_ssh "chmod 600 /tmp/$LABEL.jwt; nohup vmstat 5 > /tmp/$LABEL.vmstat 2>&1 < /dev/null & echo \$! > /tmp/$LABEL.vmstat.pid"
lettuce_sched "$OUT/lettuce-sched-before.txt"
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
lettuce_sched "$OUT/lettuce-sched-after.txt"
set +e
python "$(win "$REPO/scripts/loadtest/lettuce-loop-gate.py")" "$(win "$OUT/lettuce-sched-before.txt")"   "$(win "$OUT/lettuce-sched-after.txt")" "${SHARED:-1}" | tee -a "$LOG"
GATE_EXIT=${PIPESTATUS[0]}
set -e
# 게이트 결과를 회차 디렉터리에 남겨 집계가 무효로 처리하게 한다 — 로그에만 남기면 무효 회차가 정상 회차처럼 집계된다.
# 공유 연결 1개(대조)는 기록만 하는 게이트라 무효 대상이 아니다.
GATE_FILE="$OUT/loop-gate-invalid.txt"
rm -f "$GATE_FILE"
if [ "${SHARED:-1}" -gt 1 ]; then
  case "$GATE_EXIT" in
    0) ;;
    2) echo "루프 분산 게이트 실패 — 공유 연결이 루프에 나뉘지 않았다(async-variant.md 3-9절)" > "$GATE_FILE" ;;
    *) echo "루프 분산 게이트 판정 불가(exit $GATE_EXIT) — schedstat 을 읽지 못했다" > "$GATE_FILE" ;;
  esac
fi
[ "$K6_EXIT" -eq 0 ] || { log "중단: k6 가 비정상 종료했다 — $(k6_ssh "tail -2 /tmp/$LABEL.k6.stderr" | cut -c1-200)"; app_ssh "sudo bash /tmp/bulkhead/bulkhead-collect.sh stop $REMOTE_OUT" >/dev/null || true; exit 3; }

# 부하가 끝난 뒤 앱이 돌아오는지 본다(3-8절 K16). s-V1 은 이 시점에 API·관리 포트 모두 응답이 없었다.
# 인스턴스 안에서 앱 포트로 직접 묻는다 — ALB 를 거치면 헬스체크·연결 재사용이 섞인다.
# 경과 시간은 시도 횟수가 아니라 실제 시각으로 잰다 — SSH 터널과 curl 대기도 5분 안에 들어가야 한다.
POST_DEADLINE=300
# 기준은 k6 가 끝난 시각(END_EPOCH)이다 — k6 EC2 정리 작업이 늦어져도 그 시간까지 5분에 넣는다.
POST_START=$END_EPOCH
POST_OK=""
CODE="(시도 없음)"
while :; do
  LEFT=$(( POST_DEADLINE - ($(date +%s) - POST_START) ))
  [ "$LEFT" -gt 0 ] || break
  TRY_TIMEOUT=$(( LEFT < 5 ? LEFT : 5 ))
  CODE=$(app_ssh "curl -s -m $TRY_TIMEOUT -o /dev/null -w '%{http_code}' http://localhost:8080/api/upload-courses/$DETAIL_ID_MIN" || true)
  ELAPSED=$(( $(date +%s) - POST_START ))
  if [ "$CODE" = 200 ] && [ "$ELAPSED" -le "$POST_DEADLINE" ]; then POST_OK="$ELAPSED"; break; fi
  LEFT=$(( POST_DEADLINE - ELAPSED ))
  [ "$LEFT" -gt 0 ] || break
  sleep $(( LEFT < 15 ? LEFT : 15 ))
done
if [ -n "$POST_OK" ]; then log "부하 종료 뒤 API 응답 정상 — ${POST_OK}초 만에 200(실제 경과)"; else log "부하 종료 뒤 ${POST_DEADLINE}초 안에 API 가 200 을 돌려주지 않았다(마지막 $CODE)"; fi

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
  --k6-vmstat "$(win "$OUT/$LABEL.vmstat")" --invalid-file "$(win "$GATE_FILE")" --label "$LABEL" --out-json "$(win "$OUT/result.json")" | tee -a "$LOG"
AGG_EXIT=${PIPESTATUS[0]}
set -e
log "UnHealthyHostCount 최대 $(python -c "import json,sys;d=json.load(open(sys.argv[1]));print(max([p['Maximum'] for p in d['Datapoints']] or [0]))" "$(win "$OUT/cw-UnHealthyHostCount.json")")"
log "회차 $LABEL 완료(집계 exit $AGG_EXIT)"
exit "$AGG_EXIT"
