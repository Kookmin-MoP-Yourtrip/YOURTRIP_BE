#!/usr/bin/env bash
# 장애 격리 주실험(#197) — 운영 앱 인스턴스 안에서 회차 하나의 서버 쪽 자료를 모은다.
#
# 설계: docs/tasks/ai-bulkhead-loadtest/README.md 6-3절
#   - 지표: 관리 포트(localhost:8081)를 1초마다 긁는다. 앱 워커 풀(8080)과 분리돼 있어 워커가 고갈돼도
#     수집이 끊기지 않는다(전제가 깨지면 집계기가 '원인 분리 필요'로 표시한다)
#   - 스레드 덤프: 10초마다. CallerRuns 의 1차 증거다(executor 게이지는 CallerRuns 를 못 잡은 선례가 있다)
#   - 앱 로그: 회차 구간의 journal. 카카오 한도 소진·OpenAI 429 무효 판정용
#
# 사용 (root — 앱 JVM 이 root 로 돈다):
#   sudo bash bulkhead-collect.sh start <out-dir>
#   sudo bash bulkhead-collect.sh stop  <out-dir>      → <out-dir>.tar.gz
#
# 스레드 덤프 수단: 운영 인스턴스에는 java-21-amazon-corretto-headless 만 깔려 jcmd 가 없을 수 있다.
# 그러면 같은 저장소의 -devel 을 설치해 jcmd 를 쓰고, 그것도 안 되면 SIGQUIT(kill -3)으로 JVM 이
# 덤프를 stdout(= journal)에 쓰게 한 뒤 stop 때 journal 에서 잘라낸다. 어느 쪽인지는 dump-mode 에 남긴다.
set -euo pipefail

CMD="${1:?start|stop}"
OUT="${2:?out-dir}"
UNIT=yourtrip-app.service
POLL_INTERVAL=1
DUMP_INTERVAL=10
HERE="$(cd "$(dirname "$0")" && pwd)"

app_pid() {
  local pid
  pid=$(systemctl show -p MainPID --value "$UNIT")
  [ -n "$pid" ] && [ "$pid" != "0" ] || { echo "앱 프로세스를 찾지 못했다($UNIT)" >&2; exit 1; }
  echo "$pid"
}

find_jcmd() {
  command -v jcmd 2>/dev/null && return 0
  ls /usr/lib/jvm/*/bin/jcmd 2>/dev/null | head -1
}

start() {
  [ ! -e "$OUT" ] || { echo "이미 있다: $OUT (회차마다 새 디렉터리를 쓴다)" >&2; exit 1; }
  mkdir -p "$OUT/dumps"
  date +%s > "$OUT/started-epoch"

  local jcmd pid
  jcmd=$(find_jcmd || true)
  if [ -z "$jcmd" ]; then
    dnf install -y -q java-21-amazon-corretto-devel >/dev/null 2>&1 || true
    jcmd=$(find_jcmd || true)
  fi
  pid=$(app_pid)
  if [ -n "$jcmd" ] && "$jcmd" "$pid" VM.version >/dev/null 2>&1; then
    echo "jcmd $jcmd" > "$OUT/dump-mode"
  else
    jcmd=""
    echo "sigquit" > "$OUT/dump-mode"
  fi

  # 둘 다 setsid 로 SSH 세션에서 떼어 낸다 — 입출력을 쥐고 있으면 SSH 가 끝나지 않고, 세션이 닫힐 때
  # 함께 죽을 수도 있다.
  # 지표 1초 수집 — 저장소의 poll-metrics.sh 를 그대로 쓴다(스냅샷 형식이 집계기와 맞물려 있다).
  setsid nohup bash "$HERE/poll-metrics.sh" http://localhost:8081 "$OUT/metrics.prom" "$POLL_INTERVAL"     < /dev/null > "$OUT/poll.log" 2>&1 &
  echo $! > "$OUT/poll.pid"
  # 스레드 덤프 10초마다 — 이 스크립트의 내부 하위 명령으로 돌린다.
  setsid nohup bash "$0" _dumploop "$OUT" "$pid" "$jcmd" < /dev/null > "$OUT/dump.log" 2>&1 &
  echo $! > "$OUT/dump.pid"

  echo "수집 시작: $OUT (덤프 수단: $(cat "$OUT/dump-mode"))"
}

# SIGQUIT 모드: journal 에서 "Full thread dump" 부터 다음 덤프 직전까지를 덤프 하나로 잘라 <epoch>.txt 로 쓴다.
# 앱 로그 줄이 섞여 들어가도 집계기의 덤프 파서는 스레드 머리줄·상태·스택 줄만 읽으므로 무시된다.
extract_sigquit_dumps() {
  local since="$1"
  journalctl -u "$UNIT" --since "@$since" -o short-unix --no-pager | awk -v dir="$OUT/dumps" '
    {
      ts = $1
      msg = $0
      sub(/^[^:]*: /, "", msg)            # "<epoch> <host> <ident>[pid]: " 접두어 제거
    }
    msg ~ /^Full thread dump/ { if (f) close(f); f = dir "/" ts ".txt" }
    f { print msg > f }
  '
}

stop() {
  [ -d "$OUT" ] || { echo "없다: $OUT" >&2; exit 1; }
  for p in poll dump; do
    if [ -f "$OUT/$p.pid" ]; then
      kill "$(cat "$OUT/$p.pid")" 2>/dev/null || true
      rm -f "$OUT/$p.pid"
    fi
  done
  local since
  since=$(cat "$OUT/started-epoch")
  if grep -q '^sigquit' "$OUT/dump-mode"; then
    extract_sigquit_dumps "$since"
  fi
  journalctl -u "$UNIT" --since "@$since" -o cat --no-pager > "$OUT/app.log"
  tar -czf "$OUT.tar.gz" -C "$(dirname "$OUT")" "$(basename "$OUT")"
  echo "수집 종료: $OUT.tar.gz ($(du -h "$OUT.tar.gz" | cut -f1)), 덤프 $(ls "$OUT/dumps" | wc -l)개"
}

# 파일 이름이 epoch 초라 집계기가 시각을 맞춘다. jcmd 가 없으면 SIGQUIT — JVM 이 journal 에 덤프를 쓴다.
dump_loop() {
  local pid="$1" jcmd="$2" ts
  while true; do
    ts=$(date +%s.%N)
    if [ -n "$jcmd" ]; then
      "$jcmd" "$pid" Thread.print > "$OUT/dumps/$ts.txt" 2>&1 || true
    else
      kill -3 "$pid" || true
    fi
    sleep "$DUMP_INTERVAL"
  done
}

case "$CMD" in
  _dumploop) dump_loop "$3" "${4:-}" ;;
  start) start ;;
  stop) stop ;;
  *) echo "start|stop" >&2; exit 2 ;;
esac
