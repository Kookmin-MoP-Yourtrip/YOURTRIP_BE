#!/usr/bin/env bash
# App EC2에서 1초 간격으로 호스트/프로세스 수준 지표를 기록한다 — Actuator가 내보내지 않는 것들이다.
#
#   loadavg     : /proc/loadavg (1분 부하, runnable/전체 스레드 수)
#   procs_run   : /proc/stat의 procs_running (샘플 순간 runnable 스레드 수 — 런큐 길이의 근사)
#   cpu 행      : /proc/stat의 첫 줄(user nice system idle iowait irq softirq steal ...) 누적값.
#                 steal이 여기 있다 — 크레딧 스로틀링 배제의 직접 근거인데 이전 하네스가 수집하지
#                 않아 사후 소급이 안 됐다(docs/tasks/cache-effect-measurement/environment.md 한계).
#   ctxt        : /proc/stat의 컨텍스트 스위칭 누적 횟수(시스템 전역). #97에서 추가했다 — 이것이
#                 없어서 "요청당 CPU 26% 감소가 전환 오버헤드인지 캐시 지역성인지"를 사후에
#                 나누지 못했다(docs/tasks/tomcat-thread-sizing/ec2-measurement.md 한계).
#   jvm_utime   : /proc/<pid>/stat의 유저 모드 CPU 시간(jiffies, USER_HZ=100).
#   jvm_stime   : 같은 파일의 커널 모드 CPU 시간.
#                 이 둘의 분리가 #97의 핵심 지표다 — 요청당 실행하는 유저 모드 명령어 수는 arm과
#                 무관하게 같으므로(같은 코드·같은 히트 경로·요청당 Redis 2.0회), 유저 시간/요청의
#                 차이는 "일이 늘어난 것"이 아니라 "같은 일이 느려진 것" = 캐시/TLB 지역성이다.
#                 반대로 전환 경로·스케줄러·시스템콜은 전부 커널 시간에 잡힌다. t3는 PMU가 없어
#                 perf로 cache-misses를 못 재므로(PMC는 대형/전용 호스트 한정) 이것이 대체재다.
#   jvm_minflt  : 마이너 페이지 폴트 누적(메모리/TLB 압박 신호).
#   jvm_rss_kb  : /proc/<pid>/stat의 상주 메모리(RSS). 페이지 단위로 나오는 값을 KB로 환산해
#                 남긴다 — 페이지 크기를 집계기가 가정하지 않게 하기 위해서다.
#                 #101에서 추가했다 — Actuator는 힙/논힙만 내보내고 스레드 스택·GC·컴파일러
#                 같은 네이티브 영역은 전혀 노출하지 않는다. 프로세스 RSS에서 (힙 committed +
#                 논힙 committed + direct buffer)를 뺀 잔여가 곧 힙 밖에 실제로 쓰는 양이고,
#                 그것이 -Xmx를 t3.small(2GB) 기준으로 재산정하는 근거가 된다.
#   jvm_vsz_b   : 가상 메모리 크기. RSS와 나란히 두면 '예약했지만 안 만진' 양이 보인다.
#   mem_*_kb    : /proc/meminfo의 MemTotal/MemFree/MemAvailable. 힙을 키웠을 때 박스에
#                 남는 여유가 얼마인지가 -Xmx 상한을 정하는 안전 조건이라 함께 잰다.
#                 MemAvailable이 바닥나면 페이지 캐시가 밀려 I/O가 느려지고 결국 OOM이다.
#
# 출력은 탭 구분 한 줄/초. aggregate.py가 창의 첫/끝 스냅샷 Δ로 비율·요청당 값을 계산한다.
#
# 사용:  sample-host.sh <out-file> [duration-sec=0(무한)] [service-or-pid=yourtrip-app]
#   nohup sample-host.sh /tmp/host.tsv > /dev/null 2>&1 &
#
# 3번째 인자는 systemd 서비스명 또는 PID를 그대로 받는다. PID를 직접 받는 경로가 필요한 이유는
# #97 Phase 2의 전환 비용 캘리브레이션 벤치마크가 systemd 서비스가 아니라 그냥 java 프로세스라서다.
set -euo pipefail

OUT="${1:?out-file}"
DURATION="${2:-0}"
TARGET="${3:-yourtrip-app}"

# PID는 한 번만 조회하고, 프로세스가 사라졌을 때만 다시 찾는다(1초마다 systemctl을 부르지 않기 위해).
# 창 안에서 PID가 바뀌면 /proc/<pid>/stat의 누적 카운터가 리셋되므로 aggregate.py가 그 run을
# 무효 처리할 수 있도록 PID를 매 샘플 함께 기록한다.
resolve_pid() {
  case "$TARGET" in
    ''|*[!0-9]*) systemctl show -p MainPID --value "$TARGET" 2>/dev/null || echo 0 ;;
    *) echo "$TARGET" ;;
  esac
}
PID=$(resolve_pid)
# 페이지 크기는 부팅 중 안 바뀌므로 루프 밖에서 한 번만 구한다 — 루프 안에서 부르면
# 초당 fork가 하나 늘어난다(샘플러에 fork를 더하지 않는다는 이 파일의 원칙).
PAGE_KB=$(( $(getconf PAGESIZE) / 1024 ))

echo -e "ts\tload1\tload5\trunnable_total\tprocs_running\tcpu_user\tcpu_nice\tcpu_system\tcpu_idle\tcpu_iowait\tcpu_irq\tcpu_softirq\tcpu_steal\tctxt\tprocs_blocked\tpid\tjvm_utime\tjvm_stime\tjvm_minflt\tjvm_majflt\tjvm_nthreads\tjvm_vsz_b\tjvm_rss_kb\tmem_total_kb\tmem_free_kb\tmem_avail_kb" > "$OUT"
start=$(date +%s)
while true; do
  ts=$(date +%s.%N)
  read -r l1 l5 _ rt _ < /proc/loadavg
  # cpu 행·procs_running·ctxt·procs_blocked를 awk 한 번으로 뽑는다 — 따로 부르면 fork가 늘고
  # 샘플 안에서 읽는 시점도 어긋난다.
  stat=$(awk '/^cpu /{c=$2"\t"$3"\t"$4"\t"$5"\t"$6"\t"$7"\t"$8"\t"$9}
              /^ctxt /{x=$2} /^procs_running/{r=$2} /^procs_blocked/{b=$2}
              END{print c"\t"x"\t"b"\t"r}' /proc/stat)
  pr=${stat##*$'\t'}          # 마지막 필드가 procs_running
  stat=${stat%$'\t'*}         # 나머지(cpu 8필드 + ctxt + procs_blocked)

  # MemTotal/MemFree/MemAvailable은 /proc/meminfo의 첫 세 줄이다(리눅스 3.14+ 고정 순서).
  # awk/grep 대신 셸 내장 read로 읽어 fork를 늘리지 않는다. 순서 전제는 측정 시작 전
  # `head -3 /proc/meminfo`로 한 번 확인한다.
  { read -r _ mt _; read -r _ mf _; read -r _ ma _; } < /proc/meminfo

  [ -d "/proc/$PID" ] || PID=$(resolve_pid)
  ut=""; st=""; mn=""; mj=""; nt=""; rs=""; vz=""
  if [ "$PID" != "0" ] && [ -r "/proc/$PID/stat" ]; then
    # comm(2번 필드)은 괄호로 감싸이고 공백을 포함할 수 있어 $14 같은 위치 참조가 어긋난다.
    # 마지막 ') ' 이후로 잘라내면 남은 첫 필드가 state(원래 3번)이므로, 원래 N번 필드는 N-2번이다.
    line=$(< "/proc/$PID/stat")
    rest=${line##*') '}
    # shellcheck disable=SC2086
    set -- $rest
    mn=$8; mj=${10}; ut=${12}; st=${13}; nt=${18}; vz=${21}; rs=$(( ${22} * PAGE_KB ))
  fi
  echo -e "$ts\t$l1\t$l5\t$rt\t$pr\t$stat\t$PID\t$ut\t$st\t$mn\t$mj\t$nt\t$vz\t$rs\t$mt\t$mf\t$ma" >> "$OUT"
  if [ "$DURATION" -gt 0 ] && [ $(( $(date +%s) - start )) -ge "$DURATION" ]; then break; fi
  sleep 1
done
