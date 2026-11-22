#!/usr/bin/env bash
# 헬스체크 결함 주입 측정(#216) — 회차 하나를 처음부터 끝까지 돌린다. 개발 PC(Git Bash)에서 실행한다.
#
# 설계: docs/tasks/health-check-progress/fault-scenarios.md
#   C1 = 관리 포트만 분리, C2 = 채택안. 구성은 health-config.sh 로 먼저 고른다(이 스크립트는 바꾸지 않고 확인만 한다).
#
# 순서:
#   1. 전제 점검 — 인스턴스 1대가 healthy 인가, 의도한 구성(liveness 그룹)으로 떴는가, 결함 주입이 켜졌고 깨끗한가,
#      ReplaceUnhealthy 가 켜져 있는가(이 측정은 교체를 보는 것이 목적이다), AlarmNotification 이 멈췄는가
#   2. 관측 시작 — 타깃 헬스 5초 폴링(개발 PC), 배경 k6(초당 20건, 개발 PC). S1 은 k6 EC2 의 계단 부하가 트래픽이다
#   3. 기준선 2분 → 결함 주입(t0) → 증거(t0+25초의 liveness 응답, S2 스레드 덤프)
#   4. 관찰 — 교체를 기다리거나(정답이 교체인 칸), 교체가 없는지 지켜본다(정답이 유지인 칸, C1 의 미탐 판정 10분)
#   5. 복구 — 교체가 없었으면 systemctl restart 로 되돌리고 healthy 를 기다린다(다음 회차가 깨끗하게 시작하도록)
#   6. 수집 — ASG 활동 기록, CloudWatch, 배경 k6 결과 → aggregate-health.py 로 판정
#
# 사용:  bash scripts/loadtest/health-round.sh <C1|C2> <S1|S2|S4|S5|S6|S7> [반복 번호]
#   예:  bash scripts/loadtest/health-round.sh C2 S2      → results/health-check/h-C2-S2/
# 설정:  results/ai-bulkhead/config.env
#
# 종료 코드: 0 판정 완료(예측과 같든 다르든) / 3 전제·인프라 문제로 판정 불가(원인 확인 후 다시)
#
# 이 스크립트가 하지 않는 것: 인프라 생성·철거, 구성 전환(health-config.sh), ASG 프로세스 조작, 시드.
set -euo pipefail

CFG="${1:?C1|C2}"
SCN="${2:?S1|S2|S4|S5|S6|S7}"
REP="${3:-1}"
case "$CFG" in C1|C2) ;; *) echo "구성은 C1, C2 중 하나" >&2; exit 2 ;; esac
case "$SCN" in S1|S2|S4|S5|S6|S7) ;; *) echo "시나리오는 S1, S2, S4, S5, S6, S7 중 하나(S3 은 측정 제외)" >&2; exit 2 ;; esac
# 측정하지 않기로 한 칸(fault-scenarios.md 5절)은 실수로 돌지 않게 막는다.
case "$CFG-$SCN" in C1-S5|C1-S6|C1-S7) echo "$CFG × $SCN 은 측정하지 않는 칸이다(fault-scenarios.md 5절)" >&2; exit 2 ;; esac

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck disable=SC1091
set -a; . "$REPO/results/ai-bulkhead/config.env"; set +a
export AWS_CONFIG_FILE="${AWS_CONFIG_FILE:-$USERPROFILE/.aws/config}" AWS_SHARED_CREDENTIALS_FILE="${AWS_SHARED_CREDENTIALS_FILE:-$USERPROFILE/.aws/credentials}"
export AWS_PROFILE AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION" MSYS_NO_PATHCONV=1
win() { cygpath -m "$1"; }

LABEL="h-$CFG-$SCN"; [ "$REP" = 1 ] || LABEL="${LABEL}r$REP"
OUT="$REPO/results/health-check/$LABEL"
[ ! -e "$OUT" ] || { echo "이미 있다: $OUT — 다시 재려면 디렉터리 이름을 바꿔 보관한 뒤 실행한다" >&2; exit 2; }
mkdir -p "$OUT"
LOG="$OUT/round.log"
MARKS="$OUT/marks.tsv"
log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }
mark() { printf '%s\t%s\n' "$(date +%s)" "$1" >> "$MARKS"; log "표시: $1"; }

# 정답(fault-scenarios.md 3절)과 예측(4절). 집계기가 이 둘로 판정한다.
case "$SCN" in S2|S4|S5) TRUTH=replace ;; S6) TRUTH=restart ;; *) TRUTH=keep ;; esac
case "$CFG-$SCN" in C1-S2|C1-S4) PREDICT=keep ;; *) PREDICT=$TRUTH ;; esac

KNOWN="$OUT/known_hosts"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$KNOWN" -o ConnectTimeout=30 -o ServerAliveInterval=30)
APP_PROXY=(-o "ProxyCommand=aws ssm start-session --target %h --document-name AWS-StartSSHSession --parameters portNumber=%p")
app_ssh_to() { local id="$1"; shift; ssh "${SSH_OPTS[@]}" "${APP_PROXY[@]}" -i "$REPO/$PROD_SSH_KEY" "$SSH_USER@$id" "$@"; }
app_ssh() { app_ssh_to "$INSTANCE_ID" "$@"; }
k6_ssh() { ssh "${SSH_OPTS[@]}" -i "$REPO/$K6_SSH_KEY" "$SSH_USER@$K6_HOST" "$@"; }
k6_scp() { scp -q "${SSH_OPTS[@]}" -i "$REPO/$K6_SSH_KEY" "$@"; }
# 관리 포트 결함 장치. actuator 쓰기 동작은 본문이 비어 있어도 JSON Content-Type 이 필요하다(없으면 415).
fault() {
  local body="${2:-}"
  [ -n "$body" ] || body='{}'
  app_ssh "curl -s -m 5 -X POST -H 'Content-Type: application/json' -d '$body' http://localhost:8081/actuator/fault/$1"
}
inservice_ids() {
  aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
    --query "AutoScalingGroups[0].Instances[?LifecycleState=='InService'].InstanceId" --output text
}
target_state() {
  aws elbv2 describe-target-health --target-group-arn "$TG_ARN" \
    --query "TargetHealthDescriptions[?Target.Id=='$1'].TargetHealth.State | [0]" --output text 2>/dev/null || echo "?"
}

# ── 1. 전제 점검 ──────────────────────────────────────────────────────────────────────
log "회차 $LABEL 시작 — 정답 $TRUTH, 예측 $PREDICT"
SUSPENDED=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query 'AutoScalingGroups[0].SuspendedProcesses[].ProcessName' --output text)
# 이 측정은 교체를 관측한다. 앞선 측정들은 ReplaceUnhealthy 를 멈췄으므로 남아 있으면 모든 칸이 "유지"로 나온다.
if grep -qw ReplaceUnhealthy <<< "$SUSPENDED"; then log "중단: ReplaceUnhealthy 가 일시 중지돼 있다 — 교체를 볼 수 없다"; exit 3; fi
# 요청 수 기반 확장이 걸리면 "서버 1대" 전제가 깨지고, 교체와 확장이 섞인다. s-T 회차와 같은 조건으로 멈춘다.
grep -qw AlarmNotification <<< "$SUSPENDED" || { log "중단: AlarmNotification 이 일시 중지돼 있지 않다"; exit 3; }

TG_ARN=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query 'AutoScalingGroups[0].TargetGroupARNs[0]' --output text)
read -r -a IDS <<< "$(inservice_ids)"
[ "${#IDS[@]}" -eq 1 ] || { log "중단: InService 인스턴스가 1대가 아니다(${IDS[*]:-없음})"; exit 3; }
INSTANCE_ID="${IDS[0]}"
[ "$(target_state "$INSTANCE_ID")" = healthy ] || { log "중단: $INSTANCE_ID 가 healthy 가 아니다"; exit 3; }
echo "$INSTANCE_ID" > "$OUT/instance-before.txt"

LIVE=$(app_ssh "curl -s -m 5 http://localhost:8081/actuator/health/liveness" || true)
echo "$LIVE" > "$OUT/liveness-before.json"
case "$CFG" in
  C2) grep -q requestProgress <<< "$LIVE" || { log "중단: C2 인데 liveness 에 requestProgress 가 없다 — $LIVE"; exit 3; } ;;
  C1) ! grep -q requestProgress <<< "$LIVE" || { log "중단: C1 인데 liveness 에 requestProgress 가 있다 — health-config.sh C1 을 먼저"; exit 3; } ;;
esac
STATUS=$(fault status || true)
grep -q '"hangWaiting":0' <<< "$STATUS" || { log "중단: 결함 주입이 꺼졌거나 앞 회차의 주입이 남았다 — $STATUS"; exit 3; }
grep -q '"leakRetainedMb":0' <<< "$STATUS" || { log "중단: 앞 회차의 누수가 남았다 — $STATUS"; exit 3; }
DEPLOYED=$(app_ssh "sudo grep -h 'downloaded artifact:' /var/log/cloud-init-output.log | tail -1")
log "전제 점검 통과 — $INSTANCE_ID, $DEPLOYED"

# ── 2. 관측 시작 ──────────────────────────────────────────────────────────────────────
TG_LOG="$OUT/tg.log"
( while :; do
    printf '%s\t%s\n' "$(date +%s)" "$(aws elbv2 describe-target-health --target-group-arn "$TG_ARN" \
      --query 'TargetHealthDescriptions[].[Target.Id,TargetHealth.State,TargetHealth.Reason]' --output text 2>/dev/null \
      | tr '\t\n' ' ;')" >> "$TG_LOG"
    sleep 5
  done ) &
TG_POLL_PID=$!
BG_PID=""
cleanup() {
  kill "$TG_POLL_PID" 2>/dev/null || true
  [ -z "$BG_PID" ] || kill "$BG_PID" 2>/dev/null || true
  rm -f "$OUT/jwt.txt"
}
trap cleanup EXIT

start_background() {
  # 끝까지 다 쓰지 않아도 된다 — 회차가 끝나면 멈춘다. 상한은 회차가 가장 길어질 때(미탐 10분 + 복구)보다 길게.
  k6 run -q --out "csv=$(win "$OUT/bg.csv")" -e BASE_URL="$BASE_URL" -e DURATION=40m \
    -e DETAIL_ID_MIN="$DETAIL_ID_MIN" -e DETAIL_ID_MAX="$DETAIL_ID_MAX" \
    --summary-export="$(win "$OUT/bg-summary.json")" "$(win "$REPO/scripts/k6/health-background.js")" \
    > "$OUT/bg.stdout" 2>&1 &
  BG_PID=$!
  log "배경 k6 시작(초당 20건)"
}

if [ "$SCN" = S7 ]; then
  # S7 은 "한가한 서버에 긴 요청 하나"다. 배경 트래픽이 있으면 그 요청들이 계속 끝나 완료 기록이 끊기지 않으므로,
  # 판정이 recent-completion 으로 끝나 이 시나리오가 확인하려는 8080 직접 확인 경로를 한 번도 타지 않는다
  # (첫 h-C2-S7 이 그랬다 — h-C2-S7.with-background 로 보관). 그래서 S7 만 배경 트래픽 없이 돌린다.
  # 임계값(10초)이 지나도록 기다려 완료 기록을 끊은 뒤 주입한다.
  log "S7 — 배경 트래픽 없이, 완료 기록이 끊기도록 30초 대기"
  sleep 30
elif [ "$SCN" != S1 ]; then
  start_background
  log "기준선 2분"
  sleep 120
fi

# ── 3. 결함 주입 ──────────────────────────────────────────────────────────────────────
wait_hang() {
  local want="$1" i
  for i in $(seq 1 20); do
    grep -q "\"hangWaiting\":$want" <<< "$(fault status || true)" && return 0
    sleep 1
  done
  return 1
}

run_s1() {
  # s-T2 와 같은 계단(fast-api-ramp.js 기본값: 8계단 × 90초 + 회복 2분). 캐시를 비워 같은 냉간 조건에서 시작한다.
  ( cd "$REPO" && JWT_SECRET="$(aws ssm get-parameter --name "$SSM_JWT_SECRET_PARAM" --with-decryption \
        --query Parameter.Value --output text)" \
      LOADTEST_TOKEN_FILE="$(win "$OUT/jwt.txt")" \
      ./gradlew benchmarkTest --tests '*LoadTestTokenIssuer*' --rerun -q ) >> "$LOG" 2>&1
  [ -s "$OUT/jwt.txt" ] || { log "중단: 토큰 파일이 비었다"; exit 3; }
  app_ssh "mkdir -p /tmp/health"
  scp -q "${SSH_OPTS[@]}" "${APP_PROXY[@]}" -i "$REPO/$PROD_SSH_KEY" \
    "$REPO/scripts/loadtest/bulkhead-cache-evict.py" "$SSH_USER@$INSTANCE_ID:/tmp/health/"
  app_ssh "sudo python3 /tmp/health/bulkhead-cache-evict.py" | tee -a "$LOG"
  local attempt
  for attempt in 1 2 3; do
    k6_ssh "rm -rf /tmp/k6-local" && k6_scp -r "$REPO/scripts/k6" "$SSH_USER@$K6_HOST:/tmp/k6-local" && break
    [ "$attempt" -lt 3 ] || { log "중단: k6 스크립트 복사 실패"; exit 3; }
    sleep 10
  done
  k6_scp "$OUT/jwt.txt" "$SSH_USER@$K6_HOST:/tmp/$LABEL.jwt"
  mark inject
  set +e
  k6_ssh "ulimit -n 65535 2>/dev/null; cd /tmp/k6-local && chmod 600 /tmp/$LABEL.jwt && k6 run -q --log-output=none \
    -e BASE_URL='$BASE_URL' -e JWT=\"\$(cat /tmp/$LABEL.jwt)\" -e LABEL=$LABEL \
    -e DETAIL_ID_MIN=$DETAIL_ID_MIN -e DETAIL_ID_MAX=$DETAIL_ID_MAX \
    --summary-export=/tmp/$LABEL.summary.json fast-api-ramp.js > /tmp/$LABEL.k6.stdout 2>&1"
  local k6_exit=$?
  set -e
  k6_ssh "rm -f /tmp/$LABEL.jwt"
  k6_scp "$SSH_USER@$K6_HOST:/tmp/$LABEL.summary.json" "$OUT/ramp-summary.json" || true
  mark ramp-end
  log "계단 부하 종료(exit $k6_exit)"
}

case "$SCN" in
  S1) run_s1 ;;
  S2)
    mark inject
    # 워커(32)보다 많이 보낸다 — 남은 요청은 accept 큐에서 기다리고, 워커가 하나라도 풀리면 바로 다시 묶인다.
    app_ssh "for i in \$(seq 1 40); do nohup curl -s -m 7200 -o /dev/null http://localhost:8080/internal/fault/hang >/dev/null 2>&1 & done"
    wait_hang 32 || { log "주의: 20초 안에 워커 32개가 묶이지 않았다 — $(fault status)"; }
    log "주입 상태 $(fault status)" ;;
  S4)
    mark inject
    log "커넥터 정지 $(fault pause-connector)" ;;
  S5)
    mark inject
    app_ssh "sudo kill -STOP \$(systemctl show -p MainPID --value yourtrip-app)"
    log "JVM 정지(SIGSTOP)" ;;
  S6)
    PID_BEFORE=$(app_ssh "systemctl show -p MainPID --value yourtrip-app")
    mark inject
    log "누수 시작 $(fault leak '{"mbPerSecond":10}')" ;;
  S7)
    mark inject
    app_ssh "nohup curl -s -m 7200 -o /dev/null http://localhost:8080/internal/fault/hang >/dev/null 2>&1 &"
    wait_hang 1 || { log "중단: 워커 1개가 묶이지 않았다"; exit 3; }
    log "주입 상태 $(fault status)" ;;
esac
T0=$(awk -F'\t' '$2=="inject"{print $1; exit}' "$MARKS")

# ── 4. 증거 ──────────────────────────────────────────────────────────────────────────
if [ "$SCN" != S1 ] && [ "$SCN" != S6 ]; then
  sleep $(( T0 + 25 - $(date +%s) > 0 ? T0 + 25 - $(date +%s) : 0 ))
  # 판정이 어느 분기로 내려졌는지(check). S5 는 JVM 이 멈춰 응답이 없어야 한다(http_code 000).
  app_ssh "curl -s -m 5 -w '\nhttp_code=%{http_code}\n' http://localhost:8081/actuator/health/liveness" \
    > "$OUT/liveness-t25.txt" 2>&1 || true
  log "t0+25초 liveness: $(tr '\n' ' ' < "$OUT/liveness-t25.txt" | cut -c1-200)"
fi
if [ "$SCN" = S2 ]; then
  # 스레드 덤프 — 워커가 소켓 읽기에 묶였다는 증거. SIGQUIT 은 JVM 을 멈추지 않고 덤프를 표준 출력(journal)에 쓴다.
  app_ssh "sudo kill -3 \$(systemctl show -p MainPID --value yourtrip-app); sleep 3; \
    sudo journalctl -u yourtrip-app --no-pager --since '-20s' -o cat" > "$OUT/threaddump.txt" 2>&1 || true
  log "스레드 덤프: http-nio-8080-exec 중 NioSocketImpl 읽기 대기 $(grep -c 'NioSocketImpl.read\|SocketInputStream.read\|NioSocketImpl.park' "$OUT/threaddump.txt" || true)줄"
fi

# ── 5. 관찰 ──────────────────────────────────────────────────────────────────────────
# 정답·예측별 관찰 창. 교체를 기다리는 칸은 감지(약 1~2분) + 교체(약 6분)에 여유를 둔다.
case "$CFG-$SCN" in
  C1-S2|C1-S4) WATCH=600 ;;   # 미탐 판정 창 10분(fault-scenarios.md 6절)
  *-S2|*-S4|*-S5) WATCH=900 ;;
  *-S6) WATCH=360 ;;          # OOM → 종료 → 재기동에 여유. 교체가 없는지까지 본다
  *-S7) WATCH=180 ;;          # 긴 요청 3분 동안 오판이 없는가
  *-S1) WATCH=120 ;;          # 계단 부하가 끝난 뒤 2분 더
esac
log "관찰 ${WATCH}초"
WATCH_END=$(( $(date +%s) + WATCH ))
REPLACED=""
NEW_ID=""
FIRST_UNHEALTHY=""
while [ "$(date +%s)" -lt "$WATCH_END" ]; do
  ST=$(target_state "$INSTANCE_ID")
  if [ -z "$FIRST_UNHEALTHY" ] && [ "$ST" = unhealthy ]; then FIRST_UNHEALTHY=$(date +%s); mark "unhealthy $INSTANCE_ID"; fi
  read -r -a NOW_IDS <<< "$(inservice_ids)"
  for id in "${NOW_IDS[@]}"; do
    if [ "$id" != "$INSTANCE_ID" ] && [ "$(target_state "$id")" = healthy ]; then NEW_ID="$id"; fi
  done
  if [ -n "$NEW_ID" ]; then REPLACED=yes; mark "new-healthy $NEW_ID"; break; fi
  if [ "$SCN" = S7 ]; then
    # 배경 트래픽이 없어 8080 의 완료는 직접 확인(/internal/ping)뿐이다. 그래서 이 구간의 UP 은 recent-completion 이든
    # probe-ok 든 모두 직접 확인 경로에서 나온 것이다 — 워커 1개가 묶인 동안 그 경로가 계속 통하는지 시계열로 남긴다.
    printf '%s\t%s\n' "$(date +%s)" "$(app_ssh "curl -s -m 5 -w ' %{http_code}' http://localhost:8081/actuator/health/liveness" 2>/dev/null \
      | grep -o '\"check\":\"[a-z-]*\"\|msSinceLastCompletion\":[0-9]*\| [0-9][0-9][0-9]$' | tr '\n' ' ')" >> "$OUT/liveness-series.tsv"
  fi
  sleep 10
done
[ -n "$REPLACED" ] || REPLACED=no
log "관찰 종료 — 교체 $REPLACED${NEW_ID:+ ($NEW_ID)}"

if [ "$SCN" = S6 ] && [ "$REPLACED" = no ]; then
  # OOM 으로 JVM 이 끝나고 systemd 가 다시 띄웠는가 — 종료 코드, 재시작 시각, 기동 소요.
  app_ssh "sudo journalctl -u yourtrip-app --no-pager --since '@$T0' -o short-iso | grep -E 'OutOfMemoryError|Terminating due to|Main process exited|Scheduled restart|Started yourtrip|Started YourtripApplication'" \
    > "$OUT/oom-journal.txt" 2>&1 || true
  PID_AFTER=$(app_ssh "systemctl show -p MainPID --value yourtrip-app" || echo "?")
  echo "pid_before=$PID_BEFORE pid_after=$PID_AFTER" >> "$OUT/oom-journal.txt"
  log "OOM 기록: $(tr '\n' '|' < "$OUT/oom-journal.txt" | cut -c1-300)"
fi

# ── 6. 복구 — 다음 회차가 깨끗한 인스턴스 1대에서 시작하도록 ──────────────────────────────
if [ "$REPLACED" = no ]; then
  case "$SCN" in
    S2|S4)
      mark recover-restart
      app_ssh "sudo systemctl restart yourtrip-app" || true ;;
    S5)
      # 예측(교체)이 틀린 경우다. SIGSTOP 은 재시작으로 풀리지 않는다 — systemd 의 stop 도 멈춘 프로세스에는
      # SIGTERM 이 전달되지 않아 타임아웃까지 기다린다. 먼저 깨운다.
      mark recover-cont
      app_ssh "sudo kill -CONT \$(systemctl show -p MainPID --value yourtrip-app)" || true ;;
    S7)
      log "해제 $(fault release-hang)" ;;
  esac
fi
# 교체가 일어났다면 새 인스턴스, 아니면 원래 인스턴스가 healthy 로 서고 InService 가 1대가 될 때까지 기다린다.
RECOVER_END=$(( $(date +%s) + 600 ))
while :; do
  read -r -a NOW_IDS <<< "$(inservice_ids)"
  if [ "${#NOW_IDS[@]}" -eq 1 ] && [ "$(target_state "${NOW_IDS[0]}")" = healthy ]; then break; fi
  [ "$(date +%s)" -lt "$RECOVER_END" ] || { log "주의: 10분 안에 healthy 1대로 돌아오지 않았다 — 다음 회차 전에 확인"; break; }
  sleep 10
done
mark recovered
echo "${NOW_IDS[*]:-}" > "$OUT/instance-after.txt"
# 복구 뒤 1분 더 배경 트래픽을 받아 "다시 성공하기 시작한 시각"을 잡는다.
[ "$SCN" = S1 ] || sleep 60
mark end
[ -z "$BG_PID" ] || { kill "$BG_PID" 2>/dev/null || true; wait "$BG_PID" 2>/dev/null || true; BG_PID=""; }
kill "$TG_POLL_PID" 2>/dev/null || true

# ── 7. 수집 ──────────────────────────────────────────────────────────────────────────
END=$(date +%s)
aws autoscaling describe-scaling-activities --auto-scaling-group-name "$ASG_NAME" --max-items 20 \
  --output json > "$OUT/asg-activities.json"
LB_DIM=$(aws elbv2 describe-target-groups --target-group-arns "$TG_ARN" \
  --query 'TargetGroups[0].LoadBalancerArns[0]' --output text | sed 's#.*:loadbalancer/##')
TG_DIM=$(echo "$TG_ARN" | sed 's#.*:##')
CW_START=$(date -u -d "@$((T0 - 300))" +%Y-%m-%dT%H:%M:%SZ)
CW_END=$(date -u -d "@$((END + 60))" +%Y-%m-%dT%H:%M:%SZ)
for spec in "UnHealthyHostCount Maximum tg" "HealthyHostCount Minimum tg" "HTTPCode_ELB_5XX_Count Sum lb" "RequestCount Sum lb"; do
  set -- $spec
  DIMS="Name=LoadBalancer,Value=$LB_DIM"
  [ "$3" = tg ] && DIMS="$DIMS Name=TargetGroup,Value=$TG_DIM"
  aws cloudwatch get-metric-statistics --namespace AWS/ApplicationELB --metric-name "$1" \
    --dimensions $DIMS --start-time "$CW_START" --end-time "$CW_END" --period 60 --statistics "$2" \
    --output json > "$OUT/cw-$1.json"
done

python "$(win "$REPO/scripts/loadtest/aggregate-health.py")" --dir "$(win "$OUT")" --label "$LABEL" \
  --config "$CFG" --scenario "$SCN" --truth "$TRUTH" --predict "$PREDICT" \
  --instance-before "$INSTANCE_ID" --replaced "$REPLACED" | tee -a "$LOG"
log "회차 $LABEL 완료"
