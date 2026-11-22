#!/usr/bin/env bash
# 헬스체크 결함 주입 측정(#216) — 비교 구성(C1·C2)을 SSM env/ 로 고르고 새 인스턴스로 띄운다. 개발 PC(Git Bash)에서 실행한다.
#
# 설계: docs/tasks/health-check-progress/fault-scenarios.md 2절
#   C1 = 관리 포트만 분리(liveness 그룹 livenessState 하나), C2 = 채택안(livenessState + requestProgress).
#   두 구성은 같은 JAR, 같은 헬스체크 포트(8081)이고 환경변수 하나만 다르다 — terraform 을 건드리지 않는다.
#   결함 주입 장치(FAULT_INJECTION_ENABLED)는 두 구성 모두 켠다.
#
# 사용:  bash scripts/loadtest/health-config.sh <C1|C2|restore>
#   restore: 측정 전용 키 두 개를 지운다(운영 기본값 = C2, 결함 주입 꺼짐). 인스턴스는 교체하지 않는다 — 인프라를
#            내릴 때 쓰거나, 계속 쓸 거면 뒤이어 instance refresh 를 한다.
# 설정:  results/ai-bulkhead/config.env (회차 스크립트들과 같은 파일)
#
# 값은 부팅 때 user-data 가 SSM env/ 에서 /opt/app/.env 로 내려받는다. 그래서 SSM 값만 바꾸고 앱을 재시작하면
# 반영되지 않는다 — 반드시 새 인스턴스로 띄운다(instance refresh). 교체로 생긴 인스턴스도 같은 값을 받으므로
# 회차 도중 교체가 일어나도 구성이 유지된다.
set -euo pipefail

CFG="${1:?C1|C2|restore}"
case "$CFG" in C1|C2|restore) ;; *) echo "C1, C2, restore 중 하나" >&2; exit 2 ;; esac

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck disable=SC1091
set -a; . "$REPO/results/ai-bulkhead/config.env"; set +a
export AWS_CONFIG_FILE="${AWS_CONFIG_FILE:-$USERPROFILE/.aws/config}" AWS_SHARED_CREDENTIALS_FILE="${AWS_SHARED_CREDENTIALS_FILE:-$USERPROFILE/.aws/credentials}"
export AWS_PROFILE AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION" MSYS_NO_PATHCONV=1
win() { cygpath -m "$1"; }
log() { echo "[$(date '+%H:%M:%S')] $*"; }

ENV_PREFIX="${SSM_ARTIFACT_KEY_PARAM%/artifact_key}/env"
FAULT_KEY="$ENV_PREFIX/FAULT_INJECTION_ENABLED"
INCLUDE_KEY="$ENV_PREFIX/MANAGEMENT_ENDPOINT_HEALTH_GROUP_LIVENESS_INCLUDE"

delete_key() {
  local err
  err=$(aws ssm delete-parameter --name "$1" 2>&1 >/dev/null) && return 0
  grep -q ParameterNotFound <<< "$err" && return 0
  echo "중단: SSM 키 $1 삭제 실패 — $err" >&2
  exit 3
}

case "$CFG" in
  restore)
    delete_key "$FAULT_KEY"
    delete_key "$INCLUDE_KEY"
    log "측정 전용 키 삭제 완료 — 다음에 뜨는 인스턴스는 운영 기본값(C2, 결함 주입 꺼짐)이다"
    exit 0 ;;
  C1)
    aws ssm put-parameter --name "$INCLUDE_KEY" --value livenessState --type String --overwrite >/dev/null ;;
  C2)
    # 운영 기본값(application.yml)이 곧 C2 다. 키를 남겨 두지 않고 지워야, 기본값을 바꿨을 때 측정이 옛 값으로 돌지 않는다.
    delete_key "$INCLUDE_KEY" ;;
esac
aws ssm put-parameter --name "$FAULT_KEY" --value true --type String --overwrite >/dev/null
log "SSM 구성 $CFG 반영 — instance refresh 로 새 인스턴스를 띄운다"

REFRESH_ID=$(aws autoscaling start-instance-refresh --auto-scaling-group-name "$ASG_NAME" \
  --preferences "file://$(win "$REPO/deploy/prod/instance-refresh-preferences.json")" \
  --query InstanceRefreshId --output text)
log "instance refresh 시작 $REFRESH_ID"
DEADLINE=$(( $(date +%s) + 1500 ))
while :; do
  STATUS=$(aws autoscaling describe-instance-refreshes --auto-scaling-group-name "$ASG_NAME" \
    --instance-refresh-ids "$REFRESH_ID" --query 'InstanceRefreshes[0].Status' --output text)
  case "$STATUS" in
    Successful) break ;;
    Failed|Cancelled|RollbackFailed|RollbackSuccessful) log "중단: instance refresh $STATUS"; exit 3 ;;
  esac
  [ "$(date +%s)" -lt "$DEADLINE" ] || { log "중단: refresh 25분 초과(취소하지 않았다)"; exit 3; }
  sleep 15
done
log "구성 $CFG 로 교체 완료 — 회차 스크립트의 사전 점검이 liveness 구성을 다시 확인한다"
