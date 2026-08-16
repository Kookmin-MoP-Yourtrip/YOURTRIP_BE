#!/usr/bin/env bash
# Phase 0 로컬 게이트 — 벤치마크 토글이 실제로 작동하는지 검증한다.
#
# 검증 항목 (계획 §6 Phase 0):
#   A0: 요청당 SQL popular≈2 / detail≈3, 커넥션 대여 >= 1
#   A1: 요청당 SQL ≈ 0 인데 커넥션 대여 >= 1   ← 이번 측정 전체가 걸린 한 줄
#   A2: 요청당 SQL ≈ 0, 커넥션 대여 ≈ 0
#   세 arm의 응답 본문이 동일한가
#
# 로컬 수치로 처리량/포화 VU를 판단하지 않는다. 여기서 보는 건 "카운터가 예상대로 움직이는가"뿐이다.

set -uo pipefail

ROOT="C:/YOURTRIP/YOURTRIP_BE/YOURTRIP_BE/.claude/worktrees/multi-agent-travel-course-fc4b56"
OUT="$(dirname "${BASH_SOURCE[0]}")/phase0"
JAR="$ROOT/build/libs/yourtrip-0.0.1-SNAPSHOT.jar"
PSQL="/c/Program Files/PostgreSQL/17/bin/psql.exe"
BASE="http://localhost:8080"
N="${N:-200}"          # arm당 엔드포인트별 요청 수
DETAIL_ID=1

mkdir -p "$OUT"
cd "$ROOT"
export PGPASSWORD="$(grep '^DB_PASSWORD=' .env | cut -d= -f2-)"

metric() {  # metric <파일> <메트릭명> — 라벨 무시하고 전부 합산
  awk -v m="$2" '$1 ~ "^"m"([{ ]|$)" {
      v=$NF; if (v+0==v) s+=v
  } END { printf "%.0f", s+0 }' "$1"
}

run_arm() {
  local arm="$1" cache="$2" tx="$3"
  echo
  echo "=================== $arm (cache=$cache, tx=$tx) ==================="

  # --- 앱 기동 (prod 프로필 고정) ---
  SPRING_PROFILES_ACTIVE=prod \
  YOURTRIP_BENCHMARK_UPLOAD_COURSE_CACHE="$cache" \
  YOURTRIP_BENCHMARK_UPLOAD_COURSE_TX="$tx" \
    java -jar "$JAR" > "$OUT/$arm-app.log" 2>&1 &
  local pid=$!

  for i in $(seq 1 90); do
    curl -sf -o /dev/null "$BASE/actuator/health" && break
    if ! kill -0 $pid 2>/dev/null; then echo "  기동 실패 — $OUT/$arm-app.log 확인"; return 1; fi
    [ "$i" -eq 90 ] && { echo "  기동 타임아웃"; kill $pid; return 1; }
    sleep 1
  done
  echo "  기동 완료 (pid=$pid)"

  # 활성 프로필 검증 — local로 뜨면 SQL 전량 로깅이 A0에만 비용을 얹는다
  if ! grep -q 'The following 1 profile is active: "prod"' "$OUT/$arm-app.log"; then
    echo "  prod 프로필이 아니다 — 중단"; kill $pid; return 1
  fi

  # --- 시딩 (DB_DDL_AUTO=create라 기동 때마다 스키마가 날아간다) ---
  "$PSQL" -h localhost -p 5434 -U postgres -d yourtrip -q -f scripts/sql/seed-benchmark.sql > /dev/null 2>&1
  "$PSQL" -h localhost -p 5434 -U postgres -d yourtrip -q -f scripts/sql/seed-popular.sql   > /dev/null 2>&1
  echo "  시딩 완료"

  # --- 캐시 초기화 후 워밍 ---
  # 기동 시 Warmer가 돌지만 그땐 DB가 비어 있어 빈 랭킹이 캐시된다. 시딩 후 다시 채운다.
  docker exec yourtrip-redis redis-cli FLUSHALL > /dev/null
  if [ "$cache" = "enabled" ]; then
    curl -s -o /dev/null "$BASE/api/upload-courses/popular"
    curl -s -o /dev/null "$BASE/api/upload-courses/$DETAIL_ID"
    echo "  워밍 완료"
  else
    echo "  워밍 생략 (캐시 비활성)"
  fi

  # --- 응답 본문 저장 (arm 간 동일성 비교용) ---
  curl -s "$BASE/api/upload-courses/popular"             > "$OUT/$arm-popular.json"
  curl -s "$BASE/api/upload-courses/popular?theme=FOOD"   > "$OUT/$arm-popular-food.json"
  curl -s "$BASE/api/upload-courses/$DETAIL_ID"           > "$OUT/$arm-detail.json"

  # --- 측정: popular ---
  curl -s "$BASE/actuator/prometheus" > "$OUT/$arm-pop-before.txt"
  for i in $(seq 1 "$N"); do curl -s -o /dev/null "$BASE/api/upload-courses/popular"; done
  curl -s "$BASE/actuator/prometheus" > "$OUT/$arm-pop-after.txt"

  # --- 측정: detail ---
  curl -s "$BASE/actuator/prometheus" > "$OUT/$arm-det-before.txt"
  for i in $(seq 1 "$N"); do curl -s -o /dev/null "$BASE/api/upload-courses/$DETAIL_ID"; done
  curl -s "$BASE/actuator/prometheus" > "$OUT/$arm-det-after.txt"

  # --- 집계 ---
  for phase in pop det; do
    local b="$OUT/$arm-$phase-before.txt" a="$OUT/$arm-$phase-after.txt"
    local sql=$(( $(metric "$a" hibernate_statements_total) - $(metric "$b" hibernate_statements_total) ))
    local con=$(( $(metric "$a" hikaricp_connections_usage_seconds_count) - $(metric "$b" hikaricp_connections_usage_seconds_count) ))
    local label; [ "$phase" = "pop" ] && label="popular" || label="detail "
    printf "  %s | 요청 %4d | SQL %5d (%.3f/req) | 커넥션대여 %5d (%.3f/req)\n" \
      "$label" "$N" "$sql" "$(echo "$sql $N" | awk '{print $1/$2}')" \
      "$con" "$(echo "$con $N" | awk '{print $1/$2}')"
    echo "$arm,$label,$N,$sql,$con" >> "$OUT/summary.csv"
  done

  kill $pid 2>/dev/null; wait $pid 2>/dev/null
  echo "  종료"
}

echo "arm,endpoint,requests,sql,connections" > "$OUT/summary.csv"
run_arm a0 disabled wrapped
run_arm a1 enabled  wrapped
run_arm a2 enabled  separated

echo
echo "=================== 응답 본문 동일성 ==================="
for f in popular popular-food detail; do
  if diff -q "$OUT/a0-$f.json" "$OUT/a1-$f.json" > /dev/null 2>&1 \
  && diff -q "$OUT/a1-$f.json" "$OUT/a2-$f.json" > /dev/null 2>&1; then
    echo "  OK    $f — 세 arm 동일"
  else
    echo "  DIFF  $f — arm 간 응답이 다르다 (비교 불가)"
  fi
done

echo
echo "=================== 요약 ==================="
column -s, -t < "$OUT/summary.csv"
