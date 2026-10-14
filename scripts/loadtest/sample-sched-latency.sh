#!/usr/bin/env bash
# App EC2에서 bpftrace(trace-sched-latency.bt)와 sample-schedstat.sh를 **같은 창**으로 돌린다.
#
# 둘을 묶는 이유는 서로를 검증하기 위해서다(lettuce-dispatch-latency.md의 게이트).
#   G1  bpftrace의 배정 횟수(@dispatch)          ≈ schedstat의 pcount 증가분(전환)
#   G2  bpftrace의 선점 내려옴 횟수(@out_preempt) ≈ /proc의 nonvoluntary_ctxt_switches 증가분
# G1이 어긋나면 스레드 이름 필터가, G2가 어긋나면 prev_state 선점 판별이 틀린 것이다 —
# 둘 다 bpftrace 쪽에서는 에러 없이 조용히 틀린 숫자를 내므로 이 대조 말고는 잡을 방법이 없다.
#
# bpftrace는 프로브 부착에 수 초가 걸린다. 그래서 동시에 띄우지 않고, BEGIN이 READY를 찍을 때까지
# 기다렸다가 schedstat을 시작한다. 어긋남은 READY 폴링 간격(0.1초) 이내다.
#
# 사용:  sample-sched-latency.sh <out-prefix> [duration-sec=10] [service-or-pid=yourtrip-app]
#   <out-prefix>.schedlat.json  bpftrace 결과(JSON 줄 단위 — summarize-sched-latency.py가 읽는다)
#   <out-prefix>.schedstat.txt  같은 창의 sample-schedstat.sh 출력
set -euo pipefail

PREFIX="${1:?out-prefix}"
DURATION="${2:-10}"
TARGET="${3:-yourtrip-app}"
DIR="$(cd "$(dirname "$0")" && pwd)"
BT_OUT="$PREFIX.schedlat.json"

command -v bpftrace >/dev/null || { echo "bpftrace not installed (sudo dnf install -y bpftrace)" >&2; exit 1; }

# 커널이 바뀌면 스케줄러(6.6+는 EEVDF)와 prev_state 인코딩이 바뀐다 — 결과마다 남긴다
{ echo "kernel=$(uname -r)"; echo "bpftrace=$(bpftrace --version)"; } > "$PREFIX.env.txt"

sudo bpftrace -f json -B none "$DIR/trace-sched-latency.bt" "$DURATION" > "$BT_OUT" 2>"$PREFIX.schedlat.err" &
BT_PID=$!
# set -e라 아래 schedstat이 실패하면 스크립트가 즉시 끝난다. 그때 bpftrace가 interval이 끝날 때까지
# 프로브를 붙인 채 남으면 다음 run의 측정에 추적 부하가 섞인다 — 어떤 경로로 끝나든 회수한다.
# 정상 종료 시에는 이미 wait로 끝난 뒤라 kill이 no-op이다.
trap 'kill "$BT_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 300); do
  grep -q READY "$BT_OUT" 2>/dev/null && break
  kill -0 "$BT_PID" 2>/dev/null || { echo "bpftrace exited early:" >&2; cat "$PREFIX.schedlat.err" >&2; exit 1; }
  sleep 0.1
done
grep -q READY "$BT_OUT" || { echo "bpftrace not ready in 30s" >&2; kill "$BT_PID"; exit 1; }

# 추적 창의 벽시계(epoch) — 추적 부하 게이트가 k6 EC2의 1초 폴링에서 "창 안 TPS vs 창 밖 TPS"를
# 가르는 데 쓴다. 두 호스트 모두 chrony 동기화라 초 단위 비교에는 충분하다.
echo "window_start=$(date +%s.%N)" >> "$PREFIX.env.txt"
bash "$DIR/sample-schedstat.sh" "$DURATION" "$TARGET" > "$PREFIX.schedstat.txt"
wait "$BT_PID"
echo "window_end=$(date +%s.%N)" >> "$PREFIX.env.txt"
echo "ok: $BT_OUT $PREFIX.schedstat.txt"
