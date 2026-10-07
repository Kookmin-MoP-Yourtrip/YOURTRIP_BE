#!/usr/bin/env bash
# 장애 격리 주실험(#197) — 회차를 정해진 순서로 이어 돌린다. 개발 PC(Git Bash)에서 실행한다.
#
# 설계: docs/tasks/ai-bulkhead-loadtest/README.md 5-5·5-7절
#   - 40 단계(H0/H1 을 가르는 경계)만 A → B → B → A 로 두 번씩 잰다. 시간에 따라 선형으로 흐르는 LLM 속도
#     변화가 두 버전에 같게 배분된다
#   - r40-A1 이 장애로 나와 계획을 바꿨다(5-7절): A 는 70·20·5 대신 30 부터 경계를 찾고, B 는 70 만 더 잰다.
#     경계 탐색은 직전 결과로 다음 단계를 정하므로 미리 고정할 수 없다 — 고정 회차는 A30 까지만 두고,
#     그 뒤는 --rounds 로 한 회차씩 지정한다
#
# 회차 결과(bulkhead-round.sh 종료 코드):
#   0 유효 · 4 무효(측정 조건이 어긋남) → 결과를 보관하고 같은 자리에서 한 번 더 잰다. 또 무효면 멈춘다
#   3 전제·인프라 문제 → 바로 멈춘다(사람이 원인을 봐야 한다)
#
# 사용:  bash scripts/loadtest/bulkhead-plan.sh [시작할 순번(1부터)]
#        bash scripts/loadtest/bulkhead-plan.sh --rounds "A 20 1" ["B 70 1" ...]
#   중간에 멈췄으면 남은 순번부터 다시 시작한다. 끝난 회차의 결과 디렉터리는 그대로 둔다.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
# 40 단계 ABBA 후반(B2·A2)은 B1 결과에 따라 --rounds 로만 돌린다(5-7절) — A 는 이미 두 번 재 결과가 같았다.
PLAN=(
  "A 40 1" "B 40 1"
  "A 30 1"
)
START=1
if [ "${1:-}" = "--rounds" ]; then
  shift
  PLAN=("$@")
  [ "${#PLAN[@]}" -gt 0 ] || { echo "--rounds 뒤에 \"<A|B> <분당 건수> <반복>\" 을 하나 이상 준다" >&2; exit 2; }
else
  START="${1:-1}"
fi

for ((i = START - 1; i < ${#PLAN[@]}; i++)); do
  read -r ARM RATE REP <<< "${PLAN[$i]}"
  LABEL="r${RATE}-${ARM}${REP}"
  for attempt in 1 2; do
    echo "=== [$((i + 1))/${#PLAN[@]}] $LABEL (시도 $attempt) $(date '+%F %T')"
    bash "$REPO/scripts/loadtest/bulkhead-round.sh" "$ARM" "$RATE" "$REP"
    code=$?
    if [ "$code" -eq 0 ]; then
      break
    elif [ "$code" -eq 4 ] && [ "$attempt" -eq 1 ]; then
      # 무효 회차는 지우지 않고 보관한다 — 왜 무효였는지가 그 자체로 기록이다.
      mv "$REPO/results/ai-bulkhead/$LABEL" "$REPO/results/ai-bulkhead/$LABEL.invalid-$(date +%H%M%S)"
      echo "무효 — 같은 자리에서 다시 잰다"
    else
      echo "!!! $LABEL 에서 멈춘다(종료 코드 $code). 원인을 확인한 뒤 남은 회차를 이어서 돌린다"
      exit "$code"
    fi
  done
done
echo "=== ${#PLAN[@]}회차 중 $START번부터 완료 $(date '+%F %T')"
