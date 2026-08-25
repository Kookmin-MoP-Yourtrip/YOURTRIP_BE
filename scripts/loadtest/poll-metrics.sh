#!/usr/bin/env bash
# /actuator/prometheus를 1초 간격으로 긁어 한 파일에 이어 붙인다.
#
# 스냅샷마다 "# ts=<epoch>" 구분자를 앞에 둔다. aggregate.py가 이 구분자로 스냅샷을 나누고
# 창의 첫/끝 스냅샷 Δ로 구간 평균(Δsum ÷ Δcount)을 계산한다.
#
# 화이트리스트 없이 전량 저장한다 — 이전 하네스가 15개 지표만 걸러 담는 바람에 처음부터
# 노출되던 lettuce_command_*를 놓쳤던 함정(docs/tasks/cache-effect-measurement/redis-io-bottleneck.md)을
# 되풀이하지 않기 위해서다. 스냅샷 하나가 50~100KB, 400초 run이면 40MB 정도라 감당된다.
#
# 사용:  poll-metrics.sh <base-url> <out-file> [interval-sec=1]
#   백그라운드로 띄우고 run이 끝나면 kill 한다. 부하 생성기(k6 EC2)에서 App 사설 IP로 돌리는
#   것을 권장한다 — 공인 IP·보안그룹·개발 머신 절전에 의존하지 않는다.
set -euo pipefail

BASE_URL="${1:?base-url}"
OUT="${2:?out-file}"
INTERVAL="${3:-1}"

: > "$OUT"
while true; do
  ts=$(date +%s.%N)
  {
    echo "# ts=$ts"
    curl -sf --max-time 2 "$BASE_URL/actuator/prometheus" || echo "# scrape-failed"
  } >> "$OUT"
  sleep "$INTERVAL"
done
