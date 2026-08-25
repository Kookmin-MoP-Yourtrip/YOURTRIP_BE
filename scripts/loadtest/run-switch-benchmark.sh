#!/usr/bin/env bash
# App EC2에서 컨텍스트 스위칭 1회의 비용을 (스레드 수 N) x (워킹셋 S)로 스윕한다.
#
# 왜 하는가: #88에서 maxThreads 200 -> 32로 요청당 CPU가 26% 줄었는데, 그 26%가 전환 오버헤드인지
# 캐시 지역성인지 나뉘지 않았다(이슈 #97). 전환 1회의 비용을 알아야 몫을 계산할 수 있고, 그 값은
# 하드웨어마다 다르므로 측정 대상과 같은 박스에서 재야 한다.
#
# 방법(Li/Ding/Shen, ExpCS 2007):
#   c1 = S=0(메모리 접근 없음)일 때의 전환당 CPU  -> 직접 비용(커널 경로)
#   c2 = S>0일 때의 전환당 CPU                    -> 직접 + 간접
#   간접(캐시 재적재) = c2 - c1
# 여기에 N 축을 더해, "N이 커질 때 붙는 페널티가 S에 비례하는가"를 본다.
#   S에 따라 커지면 캐시 지역성, S와 무관하게 평평하면 전환 경로다.
#
# 이 박스는 t3.small = 물리 코어 1개(하이퍼스레드 2개)라 L1d 32KB와 L2 1MB를 두 vCPU가 통째로
# 공유한다(aws ec2 describe-instances의 CoreCount=1, ThreadsPerCore=2로 확인). S 후보를 L1/L2
# 경계에 맞춰 고른 이유다.
#
# 실행 전제:
#   - 앱을 멈춘 상태에서 돌린다(sudo systemctl stop yourtrip-app). 부하테스트 스택은 필요 없다.
#   - jar는 로컬에서 `./gradlew contextSwitchBenchmarkJar`로 만들어 scp한다.
#     App EC2에는 headless JRE만 있어(javac 없음) 거기서 컴파일할 수 없다.
#
# 사용:  run-switch-benchmark.sh [out-file=/tmp/switch-bench.tsv]
#   환경변수: JAR, BENCH_SECONDS, THREADS, SETS, HEAP, BUDGET_MB
set -euo pipefail

OUT="${1:-/tmp/switch-bench.tsv}"
JAR="${JAR:-/tmp/lt/context-switch-benchmark.jar}"
# SECONDS는 bash 내장 변수(셸 기동 후 경과 초)라 항상 설정돼 있어 기본값이 먹지 않는다.
SECONDS_PER_RUN="${BENCH_SECONDS:-10}"
# N은 짝수여야 한다(쌍으로 핑퐁한다). 부하테스트 arm(T200/T64/T32/T8)에 대응시켰다.
THREADS="${THREADS:-2 8 32 64 200}"
# 0 = 직접 비용 c1. 나머지는 L1d(32KB)와 L2(1MB) 경계를 걸치도록 골랐다.
SETS="${SETS:-0 4096 32768 262144 1048576}"
HEAP="${HEAP:-1200m}"
# N x S가 이 값을 넘으면 건너뛴다. 조용히 자르지 않고 로그로 남긴다.
BUDGET_MB="${BUDGET_MB:-400}"

[ -r "$JAR" ] || { echo "jar을 찾을 수 없다: $JAR" >&2; exit 1; }

echo "# jar=$JAR seconds=$SECONDS_PER_RUN heap=$HEAP budget=${BUDGET_MB}MB" | tee "$OUT"
echo "# $(date -Is)  kernel=$(uname -r)" | tee -a "$OUT"
# 스케줄러 파라미터를 함께 남긴다 — 타임슬라이스 기반 선점의 이론값을 계산할 때 필요하고,
# 커널 6.6+면 CFS가 아니라 EEVDF라 파라미터 이름 자체가 다르다.
for f in /sys/kernel/debug/sched/latency_ns /sys/kernel/debug/sched/min_granularity_ns \
         /sys/kernel/debug/sched/base_slice_ns; do
  [ -r "$f" ] && echo "# $f = $(cat "$f")" | tee -a "$OUT"
done
echo -e "threads\tws_bytes\tpasses\tswitches\tutime\tstime\tns_per_pass\tns_per_switch\tsw_per_pass" | tee -a "$OUT"

for n in $THREADS; do
  for s in $SETS; do
    mb=$(( n * s / 1024 / 1024 ))
    if [ "$mb" -gt "$BUDGET_MB" ]; then
      echo "# skip threads=$n ws=$s — 워킹셋 합계 ${mb}MB > ${BUDGET_MB}MB 예산" | tee -a "$OUT"
      continue
    fi
    # N마다 JVM을 새로 띄운다 — 한 JVM에서 N을 바꾸면 앞 설정이 JIT 프로파일을 오염시킨다.
    java -Xmx"$HEAP" -jar "$JAR" "$n" "$s" "$SECONDS_PER_RUN" \
      | grep -E '^(TSV|  \[경고\])' \
      | sed 's/^TSV\t//' \
      | tee -a "$OUT"
  done
done

echo
echo "=== 전환당 CPU (ns) — 행 threads, 열 워킹셋 ==="
awk -F'\t' '
  /^#/ || /^threads/ { next }
  NF >= 8 { v[$1"_"$2] = $8; n[$1]; s[$2] }
  END {
    printf "| threads |"; for (k in s) o[++c] = k
    # 열을 숫자 오름차순으로
    for (i = 1; i <= c; i++) for (j = i + 1; j <= c; j++) if (o[i] + 0 > o[j] + 0) { t = o[i]; o[i] = o[j]; o[j] = t }
    for (i = 1; i <= c; i++) printf " %s |", (o[i] + 0 == 0 ? "S=0(c1)" : o[i] / 1024 "KB")
    printf "\n|---|"; for (i = 1; i <= c; i++) printf "---|"; printf "\n"
    for (k in n) r[++m] = k
    for (i = 1; i <= m; i++) for (j = i + 1; j <= m; j++) if (r[i] + 0 > r[j] + 0) { t = r[i]; r[i] = r[j]; r[j] = t }
    for (i = 1; i <= m; i++) {
      printf "| %s |", r[i]
      for (j = 1; j <= c; j++) { key = r[i]"_"o[j]; printf " %s |", (key in v ? v[key] : "—") }
      printf "\n"
    }
  }' "$OUT"
