#!/usr/bin/env bash
# 인증 메일 발송 측정(#221) 회차 하나를 처음부터 끝까지 돌린다 — 로컬 Windows(Git Bash) 기준.
#
# 설계: docs/tasks/mail-async/measurement-design.md (2-2절 앱 설정, 2-4절 시간표, 7절 절차)
#
# 순서: 앱 재기동 → 로그인(JWT) → Mailpit 비우기 → 독성 설정 → 지표·덤프 수집 시작 → k6 → 수집 중지
#       → Mailpit 수신 수 기록 → 앱 종료 → 집계
# 회차마다 앱을 새로 띄운다. 무응답 회차는 재기동 없이는 회복하지 않는다고 예측했기 때문이다(설계 7절).
#
# 사용:  mail-round.sh <회차> <jar> <분당 메일> <latency|hang> <출력 디렉터리>
#   필요한 환경변수: LOADTEST_EMAIL, LOADTEST_PASSWORD (측정용 계정 — 커밋하지 않는다)
#                   JAVA_HOME_21 (기본 C:/Program Files/Java/jdk-21)
#   선택: K6_EXTRA="-e MAIL_SEC=60 ..." — 도구 점검용으로 시간표를 줄일 때만 쓴다
#   예: mail-round.sh A-L3 app-A.jar 144 latency out/
set -euo pipefail

LABEL="${1:?회차}"
JAR="${2:?jar}"
MAIL_PER_MIN="${3:?분당 메일}"
MODE="${4:?latency|hang}"
OUT="${5:?출력 디렉터리}"
: "${LOADTEST_EMAIL:?}" "${LOADTEST_PASSWORD:?}"

LATENCY_MS="${LATENCY_MS:-500}"   # 사전 점검 G3 보정값 — SMTP 시도 약 5초
JAVA_HOME_21="${JAVA_HOME_21:-C:/Program Files/Java/jdk-21}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$OUT"
P="$OUT/$LABEL"

app_pid() {
  powershell -NoProfile -Command \
    "(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue).OwningProcess" | tr -d '\r'
}
stop_app() {
  local pid; pid="$(app_pid)"
  [ -n "$pid" ] && powershell -NoProfile -Command "Stop-Process -Id $pid -Force" || true
}

# ── 1. 앱 재기동 (설계 2-2절의 덮어쓰기) ─────────────────────────────────────
stop_app
(
  cd "$ROOT"   # .env는 작업 디렉터리에서 읽힌다. OS 환경변수가 .env보다 우선한다
  export DB_DDL_AUTO=update MAIL_EMAIL=loadtest@example.com MAIL_PASSWORD=dummy MANAGEMENT_SERVER_PORT=8081
  nohup "$JAVA_HOME_21/bin/java" -jar "$JAR" \
    --spring.mail.host=localhost --spring.mail.port=2526 --server.tomcat.threads.max=32 \
    --logging.level.org.hibernate.SQL=WARN --logging.level.org.hibernate.orm.jdbc.bind=WARN \
    --logging.level.backend.yourtrip=INFO > "$P.app.log" 2>&1 &
)
for _ in $(seq 1 90); do
  grep -q "Started YourtripApplication" "$P.app.log" 2>/dev/null && break
  grep -q "APPLICATION FAILED" "$P.app.log" 2>/dev/null && { echo "앱 기동 실패: $P.app.log"; exit 1; }
  sleep 1
done
grep -q "Started YourtripApplication" "$P.app.log" || { echo "앱 기동 시간 초과"; exit 1; }
PID="$(app_pid)"
echo "[$LABEL] 앱 pid $PID"

# ── 2. 로그인 — 토큰 수명이 60분이라 회차마다 새로 받는다 ───────────────────
JWT="$(curl -sf -X POST localhost:8080/api/users/login -H 'Content-Type: application/json' \
  --data-binary "{\"email\":\"$LOADTEST_EMAIL\",\"password\":\"$LOADTEST_PASSWORD\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)"
[ -n "$JWT" ] || { echo "로그인 실패"; exit 1; }

# ── 3. Mailpit 비우기, 독성 설정 ────────────────────────────────────────────
curl -sf -X DELETE localhost:8025/api/v1/messages > /dev/null
case "$MODE" in
  latency) "$ROOT/scripts/loadtest/mail-toxic.sh" latency "$LATENCY_MS" > /dev/null; CLEAR_AFTER=0 ;;
  hang)    "$ROOT/scripts/loadtest/mail-toxic.sh" hang > /dev/null; CLEAR_AFTER=1 ;;
  *) echo "모드는 latency|hang"; exit 2 ;;
esac

# ── 4. 수집 시작 — 관리 포트(8081)라 워커가 고갈돼도 끊기지 않는다 ──────────
"$ROOT/scripts/loadtest/poll-metrics.sh" http://localhost:8081 "$P.metrics.txt" 1 &
POLL=$!
(
  while true; do
    echo "# ts=$(date +%s.%N)"
    "$JAVA_HOME_21/bin/jcmd" "$PID" Thread.print 2>/dev/null || echo "# dump-failed"
    sleep 10
  done
) > "$P.threads.txt" &
DUMP=$!
trap 'kill $POLL $DUMP 2>/dev/null || true' EXIT

# ── 5. 부하 ─────────────────────────────────────────────────────────────────
k6 run -q -e JWT="$JWT" -e LABEL="$LABEL" -e MAIL_PER_MIN="$MAIL_PER_MIN" -e CLEAR_AFTER="$CLEAR_AFTER" \
  ${K6_EXTRA:-} --log-output=file="$P.k6.log" --summary-export="$P.summary.json" \
  "$ROOT/scripts/k6/mail-bulkhead.js" > "$P.k6.stdout" 2>&1 || echo "[$LABEL] k6 종료 코드 $? (임계값 없음 — 로그로 판단)"

# ── 6. 정리 ─────────────────────────────────────────────────────────────────
kill $POLL $DUMP 2>/dev/null || true
curl -sf 'localhost:8025/api/v1/messages?limit=1' | grep -o '"total":[0-9]*' > "$P.mailpit.txt" || true
"$ROOT/scripts/loadtest/mail-toxic.sh" clear > /dev/null
stop_app

python -I -X utf8 "$ROOT/scripts/loadtest/aggregate-mail.py" "$P.k6.log" --metrics "$P.metrics.txt" \
  --summary "$P.summary.json" > "$P.result.md"
echo "Mailpit 수신: $(cat "$P.mailpit.txt")" >> "$P.result.md"
cat "$P.result.md"
