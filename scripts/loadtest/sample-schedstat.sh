#!/usr/bin/env bash
# App EC2에서 JVM 스레드 그룹별 "CPU 실행 시간 vs 런큐 대기 시간"을 잰다.
#
# /proc/<pid>/task/<tid>/schedstat의 앞 두 필드(실행 ns, 런큐 대기 ns)를 t0와 t0+N초에 읽어
# 차이를 그룹별로 합산한다. 실행+대기 ≈ 벽시계면 그 스레드는 한순간도 놀지 않은 것이고,
# 대기 비율이 높으면 "CPU를 많이 쓰진 않지만 차례를 못 얻고 있다"는 뜻이다 — CPU 사용률이나
# tomcat_threads_busy 어디에도 잡히지 않는 단일 Lettuce I/O 스레드 병목을 이 방법으로 특정했다
# (docs/tasks/cache-effect-measurement/redis-io-bottleneck.md).
#
# 그룹은 스레드 이름(comm, 15자 절단)으로 나눈다:
#   lettuce   : lettuce-epollEv* / lettuce-nioEven*  (netty 이벤트루프 — lettuce-timer 등은 other)
#   tomcat    : http-nio-*                            (요청 워커)
#   other     : 나머지 전부
# 두 시점 모두 존재한 tid만 합산한다(그 사이 생성/소멸된 스레드는 제외).
#
# 사용:  sample-schedstat.sh [duration-sec=10] [service=yourtrip-app]
#   부하가 정상 상태(steady)에 들어간 구간에서 실행한다. 출력은 마크다운 표 + 원자료 TSV.
set -euo pipefail

DURATION="${1:-10}"
SERVICE="${2:-yourtrip-app}"

PID=$(systemctl show -p MainPID --value "$SERVICE")
[ -n "$PID" ] && [ "$PID" != "0" ] || { echo "service $SERVICE not running" >&2; exit 1; }

snapshot() {
  # tid \t comm \t run_ns \t wait_ns
  for t in /proc/"$PID"/task/*; do
    tid=${t##*/}
    comm=$(cat "$t/comm" 2>/dev/null) || continue
    read -r run wait _ < "$t/schedstat" 2>/dev/null || continue
    printf '%s\t%s\t%s\t%s\n' "$tid" "$comm" "$run" "$wait"
  done
}

group_of() {
  case "$1" in
    lettuce-epollEv*|lettuce-nioEven*) echo lettuce ;;
    http-nio-*) echo tomcat ;;
    *) echo other ;;
  esac
}

t0=$(date +%s.%N)
s0=$(snapshot)
sleep "$DURATION"
s1=$(snapshot)
t1=$(date +%s.%N)
wall_ms=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.0f", (b-a)*1000}')

declare -A run wait cnt
while IFS=$'\t' read -r tid comm r1 w1; do
  line0=$(grep -P "^${tid}\t" <<< "$s0" || true)
  [ -n "$line0" ] || continue
  IFS=$'\t' read -r _ _ r0 w0 <<< "$line0"
  g=$(group_of "$comm")
  run[$g]=$(( ${run[$g]:-0} + r1 - r0 ))
  wait[$g]=$(( ${wait[$g]:-0} + w1 - w0 ))
  cnt[$g]=$(( ${cnt[$g]:-0} + 1 ))
done <<< "$s1"

echo "pid=$PID wall=${wall_ms}ms"
echo
echo "| 그룹 | 실행(ms) | 런큐 대기(ms) | 대기 비율 | (실행+대기) ÷ 벽시계 | 스레드 수 |"
echo "|---|---|---|---|---|---|"
for g in lettuce tomcat other; do
  r=${run[$g]:-0}; w=${wait[$g]:-0}; c=${cnt[$g]:-0}
  awk -v g="$g" -v r="$r" -v w="$w" -v c="$c" -v wall="$wall_ms" 'BEGIN{
    rm=r/1e6; wm=w/1e6; tot=rm+wm;
    ratio = (tot>0) ? wm/tot*100 : 0;
    per = (wall>0) ? tot/wall*100 : 0;
    printf "| %s | %.0f | %.0f | %.1f%% | %.1f%% | %d |\n", g, rm, wm, ratio, per, c }'
done
echo
echo "raw (lettuce event loop threads):"
grep -P '\tlettuce-(epollEv|nioEven)' <<< "$s1" | while IFS=$'\t' read -r tid comm r1 w1; do
  line0=$(grep -P "^${tid}\t" <<< "$s0" || true); [ -n "$line0" ] || continue
  IFS=$'\t' read -r _ _ r0 w0 <<< "$line0"
  awk -v t="$tid" -v c="$comm" -v r="$((r1-r0))" -v w="$((w1-w0))" 'BEGIN{printf "  tid=%s %s run=%.0fms wait=%.0fms\n", t, c, r/1e6, w/1e6}'
done
