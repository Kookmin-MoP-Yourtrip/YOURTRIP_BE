#!/usr/bin/env bash
#
# 캐싱 효과 부하테스트의 arm을 전환한다.
#
#   ./scripts/loadtest/switch-arm.sh a0|a1|a2
#
# arm 정의 (docs/tasks/upload-course-caching/README.md):
#   a0 = 캐싱 없음              (cache=disabled, tx=wrapped)
#   a1 = 캐싱 O, 분리 이전      (cache=enabled,  tx=wrapped)
#   a2 = 캐싱 O, 분리 이후      (cache=enabled,  tx=separated)  ← 현재 운영 상태
#
# 로컬 개발 머신에서 실행한다. 사전에 SSM 포트포워딩 두 개가 열려 있어야 한다:
#   RDS         localhost:${PG_PORT}    (기본 15432)
#   ElastiCache localhost:${REDIS_PORT} (기본 16379)
#
# 필수 환경변수:
#   APP_HOST   App EC2 주소 (ssh 대상)
#   SSH_KEY    App EC2 키페어 경로
#   APP_URL    앱 base URL (예: http://10.42.1.159:8080)
#
# 전환 순서를 스크립트로 고정하는 이유: 하나라도 빠지면 측정이 조용히 오염된다.
# 특히 (1) 프로필이 local이면 SQL 전량 로깅이 a0에만 비용을 얹고, (2) 재시딩을 빠뜨리면
# DB_DDL_AUTO=create 때문에 빈 스키마에 부하를 걸게 되며, (3) FLUSHALL을 빠뜨리면
# 직전 arm의 캐시가 남는다.

set -euo pipefail

ARM="${1:-}"
case "$ARM" in
  a0) CACHE_MODE=disabled; TX_MODE=wrapped   ;;
  a1) CACHE_MODE=enabled;  TX_MODE=wrapped   ;;
  a2) CACHE_MODE=enabled;  TX_MODE=separated ;;
  *)  echo "사용법: $0 a0|a1|a2" >&2; exit 1 ;;
esac

: "${APP_HOST:?APP_HOST를 지정해야 한다 (App EC2 주소)}"
: "${SSH_KEY:?SSH_KEY를 지정해야 한다 (키페어 경로)}"
: "${APP_URL:?APP_URL을 지정해야 한다 (예: http://10.42.1.159:8080)}"

PG_PORT="${PG_PORT:-15432}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-yourtrip}"
REDIS_PORT="${REDIS_PORT:-16379}"
SSH_USER="${SSH_USER:-ec2-user}"
# 규모 곡선(L1)에서만 지정한다. 비어 있으면 기본 3,000건 시드만 적용한다.
TARGET_COUNT="${TARGET_COUNT:-}"

SSH="ssh -i ${SSH_KEY} -o StrictHostKeyChecking=no ${SSH_USER}@${APP_HOST}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "=== arm ${ARM} 로 전환 (cache=${CACHE_MODE}, tx=${TX_MODE}) ==="

# ------------------------------------------------------------
# 1. /opt/app/.env 갱신
#
# SPRING_PROFILES_ACTIVE=prod가 없으면 앱이 local로 떠서 SQL과 바인딩 파라미터를 전량
# 로깅한다. 로깅 비용은 실행된 쿼리 수에 비례하는데 arm별 요청당 SQL이 a0=2~3 / a1=0 /
# a2=0으로 달라서, a0에만 비용이 얹혀 캐싱 효과가 과대평가된다.
# user_data 템플릿에는 이 줄이 있지만 user_data는 최초 부팅 1회만 실행되므로,
# 이미 떠 있는 인스턴스에는 여기서 직접 넣어준다.
# ------------------------------------------------------------
echo "[1/8] .env 갱신"
$SSH "sudo sed -i '/^SPRING_PROFILES_ACTIVE=/d;/^YOURTRIP_BENCHMARK_UPLOAD_COURSE_CACHE=/d;/^YOURTRIP_BENCHMARK_UPLOAD_COURSE_TX=/d' /opt/app/.env \
      && printf 'SPRING_PROFILES_ACTIVE=prod\nYOURTRIP_BENCHMARK_UPLOAD_COURSE_CACHE=%s\nYOURTRIP_BENCHMARK_UPLOAD_COURSE_TX=%s\n' '${CACHE_MODE}' '${TX_MODE}' | sudo tee -a /opt/app/.env > /dev/null"

# ------------------------------------------------------------
# 2. 재기동
#
# arm이 안 바뀌었더라도 매 run 전에 반드시 재기동한다.
# hikaricp_connections_acquire_seconds_max는 Micrometer 롤링 윈도우라 직전 run의 값을
# 이어받아 약 2분에 걸쳐 감쇠한다(stage0/local/index.md 실측).
# ------------------------------------------------------------
echo "[2/8] 앱 재기동"
$SSH "sudo systemctl restart yourtrip-app"

# ------------------------------------------------------------
# 3. health 대기
# ------------------------------------------------------------
echo "[3/8] health 대기"
for i in $(seq 1 60); do
  if curl -sf -o /dev/null "${APP_URL}/actuator/health"; then
    echo "      up (${i}s)"
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "      기동 실패 — journalctl 확인 필요" >&2
    exit 1
  fi
  sleep 1
done

# ------------------------------------------------------------
# 4. 활성 프로필 검증 — local로 떴으면 즉시 중단
#
# 여기서 걸러내지 못하면 그 run의 수치는 폐기해야 한다. 측정을 시작하기 전에 끊는다.
# ------------------------------------------------------------
echo "[4/8] 활성 프로필 검증"
PROFILE_LINE="$($SSH "sudo journalctl -u yourtrip-app --since '-3min' | grep -m1 'The following .* profile'" || true)"
echo "      ${PROFILE_LINE:-(프로필 로그 없음)}"
if ! echo "$PROFILE_LINE" | grep -q '"prod"'; then
  echo "      prod 프로필이 아니다 — 측정 중단" >&2
  exit 1
fi

# ------------------------------------------------------------
# 5. 재시딩
#
# DB_DDL_AUTO=create라 재기동마다 스키마가 DROP/CREATE된다.
# ------------------------------------------------------------
echo "[5/8] 재시딩"
psql -h localhost -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -q \
     -f "${REPO_ROOT}/scripts/sql/seed-benchmark.sql" > /dev/null
psql -h localhost -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -q \
     -f "${REPO_ROOT}/scripts/sql/seed-popular.sql" > /dev/null
if [ -n "$TARGET_COUNT" ]; then
  echo "      규모 곡선용 증량: ${TARGET_COUNT}건"
  psql -h localhost -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -q \
       -v target_count="$TARGET_COUNT" \
       -f "${REPO_ROOT}/scripts/sql/seed-popular-large.sql" > /dev/null
fi

# ------------------------------------------------------------
# 6. 캐시 초기화
# ------------------------------------------------------------
echo "[6/8] FLUSHALL"
redis-cli -h localhost -p "$REDIS_PORT" FLUSHALL > /dev/null

# ------------------------------------------------------------
# 7. 랭킹 캐시 워밍 (캐시를 쓰는 arm만)
#
# PopularCourseCacheWarmer가 기동 시 8키를 데우지만, 위 FLUSHALL로 다시 비워졌다.
# a0는 캐시가 꺼져 있어 워밍이 무의미하므로 건너뛴다.
# ------------------------------------------------------------
if [ "$CACHE_MODE" = "enabled" ]; then
  echo "[7/8] 랭킹 캐시 워밍 (8키)"
  for T in "" "?theme=HEALING" "?theme=ACTIVITY" "?theme=FOOD" \
           "?theme=SENSIBILITY" "?theme=CULTURE" "?theme=NATURE" "?theme=SHOPPING"; do
    curl -s -o /dev/null "${APP_URL}/api/upload-courses/popular${T}"
  done
else
  echo "[7/8] 워밍 생략 (캐시 비활성)"
fi

# ------------------------------------------------------------
# 8. 적용된 설정 출력 — 결과 파일명의 arm 라벨과 대조한다
#
# arm 라벨 오기입은 사후에 알아챌 방법이 없는 종류의 오류라, 매번 눈으로 확인한다.
# ------------------------------------------------------------
echo "[8/8] 적용 확인"
$SSH "sudo journalctl -u yourtrip-app --since '-3min' | grep -E '벤치마크 모드|profile' | tail -3" || true
$SSH "sudo grep -E '^(SPRING_PROFILES_ACTIVE|YOURTRIP_BENCHMARK)' /opt/app/.env"

echo
echo "=== arm ${ARM} 준비 완료 ==="
echo "다음: 스케줄러 틱(매 10분) 직후에 k6를 시작한다 — run이 450초라 그냥 돌리면"
echo "      ViewCountSyncScheduler의 DB bulk update가 측정 구간 한복판에 끼어든다."
