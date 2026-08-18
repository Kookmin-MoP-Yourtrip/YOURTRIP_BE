#!/usr/bin/env bash
# App EC2에서 1초 간격으로 호스트 수준 지표를 기록한다 — Actuator가 내보내지 않는 것들이다.
#
#   loadavg     : /proc/loadavg (1분 부하, runnable/전체 스레드 수)
#   procs_run   : /proc/stat의 procs_running (샘플 순간 runnable 스레드 수 — 런큐 길이의 근사)
#   cpu 행      : /proc/stat의 첫 줄(user nice system idle iowait irq softirq steal ...) 누적값.
#                 steal이 여기 있다 — 크레딧 스로틀링 배제의 직접 근거인데 이전 하네스가 수집하지
#                 않아 사후 소급이 안 됐다(docs/tasks/cache-effect-measurement/environment.md 한계).
#
# 출력은 탭 구분 한 줄/초. aggregate.py가 창의 첫/끝 cpu 행 Δ로 steal 비율을 계산한다.
#
# 사용:  sample-host.sh <out-file> [duration-sec=0(무한)]
#   nohup sample-host.sh /tmp/host.tsv > /dev/null 2>&1 &
set -euo pipefail

OUT="${1:?out-file}"
DURATION="${2:-0}"

echo -e "ts\tload1\tload5\trunnable_total\tprocs_running\tcpu_user\tcpu_nice\tcpu_system\tcpu_idle\tcpu_iowait\tcpu_irq\tcpu_softirq\tcpu_steal" > "$OUT"
start=$(date +%s)
while true; do
  ts=$(date +%s.%N)
  read -r l1 l5 _ rt _ < /proc/loadavg
  pr=$(awk '/^procs_running/ {print $2}' /proc/stat)
  cpu=$(awk '/^cpu / {print $2"\t"$3"\t"$4"\t"$5"\t"$6"\t"$7"\t"$8"\t"$9}' /proc/stat)
  echo -e "$ts\t$l1\t$l5\t$rt\t$pr\t$cpu" >> "$OUT"
  if [ "$DURATION" -gt 0 ] && [ $(( $(date +%s) - start )) -ge "$DURATION" ]; then break; fi
  sleep 1
done
