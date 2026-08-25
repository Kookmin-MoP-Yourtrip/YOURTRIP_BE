#!/usr/bin/env bash
# App EC2에서 JVM 스레드 그룹별 "CPU 실행 / 런큐 대기 / 전환 횟수"를 잰다.
#
# /proc/<pid>/task/<tid>/schedstat의 세 필드를 t0와 t0+N초에 읽어 차이를 그룹별로 합산한다.
#   1) 실행 ns    : CPU를 잡고 돈 시간
#   2) 런큐 대기 ns: 차례를 못 얻고 기다린 시간. 실행+대기 ≈ 벽시계면 그 스레드는 한순간도 놀지
#                    않은 것이고, 대기 비율이 높으면 "CPU를 많이 쓰진 않지만 차례를 못 얻고 있다"는
#                    뜻이다 — CPU 사용률이나 tomcat_threads_busy 어디에도 잡히지 않는 단일 Lettuce
#                    I/O 스레드 병목을 이 방법으로 특정했다(cache-effect-measurement/redis-io-bottleneck.md).
#   3) pcount     : 이 스레드가 CPU에 스케줄된 횟수 = 컨텍스트 스위칭 횟수. #97에서 추가했다.
#                    /proc/stat의 ctxt(시스템 전역)와 달리 스레드 그룹별로 나뉘고, 요청당 횟수로
#                    정규화하면 "스레드를 줄이면 전환이 줄어서 CPU가 덜 드는가"를 직접 반증할 수 있다.
#
# 여기에 /proc/<pid>/task/<tid>/status의 voluntary/nonvoluntary_ctxt_switches를 함께 읽어
# 자발(블로킹 — 요청당 블로킹 지점 수가 정하므로 arm과 무관해야 한다)과
# 비자발(선점 — 경합이 늘면 증가한다)을 분해한다.
#
# 그룹은 스레드 이름(comm, 15자 절단)으로 나눈다:
#   lettuce   : lettuce-epollEv* / lettuce-nioEven*  (netty 이벤트루프 — lettuce-timer 등은 other)
#   tomcat    : http-nio-*                            (요청 워커)
#   gc        : GC Thread#* / G1 * / VM Thread        (GC를 유저 시간 분해에서 차감하기 위해)
#   jit       : C1/C2 CompilerThre*                   (JIT도 마찬가지)
#   other     : 나머지 전부
# 두 시점 모두 존재한 tid만 합산한다(그 사이 생성/소멸된 스레드는 제외).
#
# **스냅샷은 프로세스를 fork하지 않는다.** 이전 판은 스레드마다 `cat`과 `grep`을 띄워서
# 스레드가 많은 arm일수록 순회가 길어졌고, 그만큼 분모(벽시계)가 부풀어 arm 간 비교에 최대
# 15%p의 편향이 생겼다(tomcat-thread-sizing/ec2-measurement.md의 caveat). 스냅샷당 fork 400여 회를
# awk 1회로 바꿔 그 편향을 없앴다 — 전환 횟수를 요청당으로 정규화하려면 이 편향이 치명적이다.
#
# 사용:  sample-schedstat.sh [duration-sec=60] [service-or-pid=yourtrip-app] [base-url=http://localhost:8080]
#   부하가 정상 상태(steady)에 들어간 구간에서 실행한다. 출력은 마크다운 표 + lettuce 원자료.
set -euo pipefail

DURATION="${1:-60}"
TARGET="${2:-yourtrip-app}"
BASE_URL="${3:-http://localhost:8080}"

case "$TARGET" in
  ''|*[!0-9]*) PID=$(systemctl show -p MainPID --value "$TARGET") ;;
  *) PID="$TARGET" ;;
esac
[ -n "$PID" ] && [ "$PID" != "0" ] && [ -d "/proc/$PID" ] || { echo "target $TARGET not running" >&2; exit 1; }

# 창 안의 요청 수 — 전환 횟수를 "요청당"으로 정규화하려면 같은 창의 분모가 필요하다.
# 창 밖(t0 이전 / t1 이후)에서 부르므로 창 자체를 늘리지 않는다.
requests() {
  curl -sf --max-time 2 "$BASE_URL/actuator/prometheus" 2>/dev/null \
    | awk '/^http_server_requests_seconds_count/ && !/uri="\/actuator/ {s+=$NF} END{printf "%.0f", s+0}' \
    || echo 0
}

# tid \t comm \t run_ns \t wait_ns \t pcount \t vol \t nonvol — awk 1회로 전량 수집(fork 없음)
snapshot() {
  local dirs=("/proc/$PID/task"/*)
  printf '%s\n' "${dirs[@]}" | awk '
    {
      d = $0; tid = d; sub(/.*\//, "", tid)
      if ((getline comm < (d "/comm")) <= 0) { next }
      close(d "/comm")
      if ((getline sl < (d "/schedstat")) <= 0) { next }
      close(d "/schedstat")
      split(sl, s, /[ \t]+/)
      vol = 0; nvol = 0
      while ((getline ln < (d "/status")) > 0) {
        if (ln ~ /^voluntary_ctxt_switches:/)         { split(ln, a, /[:[:space:]]+/); vol  = a[2] + 0 }
        else if (ln ~ /^nonvoluntary_ctxt_switches:/) { split(ln, a, /[:[:space:]]+/); nvol = a[2] + 0 }
      }
      close(d "/status")
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", tid, comm, s[1], s[2], s[3], vol, nvol
    }'
}

group_of() {
  case "$1" in
    lettuce-epollEv*|lettuce-nioEven*) echo lettuce ;;
    http-nio-*) echo tomcat ;;
    "GC Thread#"*|"G1 "*|"VM Thread") echo gc ;;
    "C1 CompilerThre"*|"C2 CompilerThre"*) echo jit ;;
    *) echo other ;;
  esac
}

# 벽시계는 /proc/uptime(단조 증가)으로 잰다 — date는 CLOCK_REALTIME이라 창 도중 NTP가 시계를
# 스텝하면 분모가 통째로 어긋난다(WSL 검증 중 8초 창이 7.57초로 계측되는 것을 실제로 관측했다).
# 해상도는 10ms라 60초 창에서 0.02% 수준이다.
uptime_ms() { local u _; read -r u _ < /proc/uptime; echo "${u/./}0"; }

req0=$(requests)
t0=$(uptime_ms)
s0=$(snapshot)
sleep "$DURATION"
s1=$(snapshot)
t1=$(uptime_ms)
req1=$(requests)
wall_ms=$(( t1 - t0 ))
reqs=$(( req1 - req0 ))

# t0 스냅샷을 연관 배열로 올려둔다 — 이전 판은 tid마다 grep을 띄워 O(N²)에 fork까지 났다.
declare -A p0
while IFS=$'\t' read -r tid _ r0 w0 c0 v0 n0; do
  p0[$tid]="$r0 $w0 $c0 $v0 $n0"
done <<< "$s0"

declare -A run wait sw vol nvol cnt
while IFS=$'\t' read -r tid comm r1 w1 c1 v1 n1; do
  prev=${p0[$tid]:-}
  [ -n "$prev" ] || continue
  read -r r0 w0 c0 v0 n0 <<< "$prev"
  g=$(group_of "$comm")
  run[$g]=$((  ${run[$g]:-0}  + r1 - r0 ))
  wait[$g]=$(( ${wait[$g]:-0} + w1 - w0 ))
  sw[$g]=$((   ${sw[$g]:-0}   + c1 - c0 ))
  vol[$g]=$((  ${vol[$g]:-0}  + v1 - v0 ))
  nvol[$g]=$(( ${nvol[$g]:-0} + n1 - n0 ))
  cnt[$g]=$((  ${cnt[$g]:-0}  + 1 ))
done <<< "$s1"

echo "pid=$PID wall=${wall_ms}ms requests=$reqs"
echo
echo "| 그룹 | 실행(ms) | 런큐 대기(ms) | 대기 비율 | (실행+대기) ÷ 벽시계 | 스레드 수 | 전환 | 자발 | 비자발 | 전환/요청 |"
echo "|---|---|---|---|---|---|---|---|---|---|"
tot_sw=0
for g in lettuce tomcat gc jit other; do
  r=${run[$g]:-0}; w=${wait[$g]:-0}; c=${cnt[$g]:-0}
  s=${sw[$g]:-0}; v=${vol[$g]:-0}; n=${nvol[$g]:-0}
  tot_sw=$(( tot_sw + s ))
  awk -v g="$g" -v r="$r" -v w="$w" -v c="$c" -v wall="$wall_ms" \
      -v s="$s" -v v="$v" -v n="$n" -v q="$reqs" 'BEGIN{
    rm=r/1e6; wm=w/1e6; tot=rm+wm;
    ratio = (tot>0) ? wm/tot*100 : 0;
    per   = (wall>0) ? tot/wall*100 : 0;
    pr    = (q>0) ? sprintf("%.2f", s/q) : "—";
    printf "| %s | %.0f | %.0f | %.1f%% | %.1f%% | %d | %d | %d | %d | %s |\n", g, rm, wm, ratio, per, c, s, v, n, pr }'
done
awk -v s="$tot_sw" -v q="$reqs" 'BEGIN{
  printf "| **합계** | | | | | | %d | | | %s |\n", s, (q>0) ? sprintf("%.2f", s/q) : "—" }'

echo
echo "raw (lettuce event loop threads):"
while IFS=$'\t' read -r tid comm r1 w1 c1 v1 n1; do
  case "$comm" in lettuce-epollEv*|lettuce-nioEven*) ;; *) continue ;; esac
  prev=${p0[$tid]:-}; [ -n "$prev" ] || continue
  read -r r0 w0 c0 v0 n0 <<< "$prev"
  awk -v t="$tid" -v c="$comm" -v r="$((r1-r0))" -v w="$((w1-w0))" \
      -v s="$((c1-c0))" -v v="$((v1-v0))" -v n="$((n1-n0))" 'BEGIN{
    printf "  tid=%s %s run=%.0fms wait=%.0fms switches=%d (vol=%d nonvol=%d)\n", t, c, r/1e6, w/1e6, s, v, n }'
done <<< "$s1"
