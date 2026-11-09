#!/usr/bin/env bash
# 측정 회차의 SSM env/ 값을 다루는 공용 함수. bulkhead-round.sh · fastapi-round.sh 가 source 한다.
# 전제: SSM_ARTIFACT_KEY_PARAM 이 설정돼 있고 MSYS_NO_PATHCONV=1 이 켜져 있다.
# 이 키들은 운영에 원래 없는 측정 전용 키다. 그 밖의 arm(P 등)은 clear_arm_env 로 지워 운영 기본값으로 뜨게 한다.
ARM_ENV_PREFIX="${SSM_ARTIFACT_KEY_PARAM%/artifact_key}/env"
ARM_ENV_KEYS=(AI_COURSE_ASYNC_ENABLED AI_COURSE_ADMISSION_MAXWORKUNITS AI_COURSE_ADMISSION_MAXREQUESTS SPRING_THREADS_VIRTUAL_ENABLED)
PIN_TRACE_OPT="-Djdk.tracePinnedThreads=short"
# SSM 키를 지운다. 키가 없는 것은 정상이고, 그 밖의 실패(권한·네트워크)는 앞 회차 값이 남을 수 있으니 회차를 멈춘다.
delete_ssm_key() {
  local err
  err=$(aws ssm delete-parameter --name "$1" 2>&1 >/dev/null) && return 0
  grep -q ParameterNotFound <<< "$err" && return 0
  echo "중단: SSM 키 $1 삭제 실패 — 앞 회차 값이 남을 수 있다: $err" >&2
  exit 3
}
# JAVA_TOOL_OPTIONS 의 현재 값. 키가 없으면 빈 문자열이다. 조회가 다른 이유로 실패하면 0 이 아닌 값을 돌려준다 —
# 빈 값으로 오판하면 운영 값을 덮어쓰거나 피닝 추적 값을 남긴다. $( ) 안에서 불리므로 호출자가 실패를 받아 멈춘다.
current_tool_opts() {
  local out
  out=$(aws ssm get-parameter --name "$ARM_ENV_PREFIX/JAVA_TOOL_OPTIONS" --query Parameter.Value --output text 2>&1)     && { echo "$out"; return 0; }
  grep -q ParameterNotFound <<< "$out" && return 0
  echo "중단: SSM JAVA_TOOL_OPTIONS 조회 실패 — 값을 바꾸기 전에 멈춘다: $out" >&2
  return 1
}
# JAVA_TOOL_OPTIONS 는 운영이 쓸 수도 있는 키라, 이 스크립트가 넣은 피닝 추적 값일 때만 지운다.
clear_pin_trace() {
  local cur
  cur=$(current_tool_opts) || exit 3
  [ "$cur" = "$PIN_TRACE_OPT" ] && delete_ssm_key "$ARM_ENV_PREFIX/JAVA_TOOL_OPTIONS"
  return 0
}
set_arm_env() {
  local async="$1" units="$2" requests="$3" virtual="$4" cur
  # JAVA_TOOL_OPTIONS 상태를 먼저 확인한다 — 조회 실패나 운영 값이 있으면 SSM 을 하나도 바꾸지 않은 채 멈춘다.
  # 운영 값을 덮어쓰면 뒤 회차에서 지울 때 원래 값을 되살릴 수 없다.
  cur=$(current_tool_opts) || exit 3
  if [ "$virtual" = true ] && [ -n "$cur" ] && [ "$cur" != "$PIN_TRACE_OPT" ]; then
    echo "중단: SSM JAVA_TOOL_OPTIONS 에 운영 값이 있다 — 피닝 추적 값과 합칠지 먼저 정한다" >&2
    exit 3
  fi
  aws ssm put-parameter --name "$ARM_ENV_PREFIX/AI_COURSE_ASYNC_ENABLED" --value "$async" --type String --overwrite >/dev/null
  aws ssm put-parameter --name "$ARM_ENV_PREFIX/AI_COURSE_ADMISSION_MAXWORKUNITS" --value "$units" --type String --overwrite >/dev/null
  aws ssm put-parameter --name "$ARM_ENV_PREFIX/AI_COURSE_ADMISSION_MAXREQUESTS" --value "$requests" --type String --overwrite >/dev/null
  aws ssm put-parameter --name "$ARM_ENV_PREFIX/SPRING_THREADS_VIRTUAL_ENABLED" --value "$virtual" --type String --overwrite >/dev/null
  if [ "$virtual" = true ]; then
    aws ssm put-parameter --name "$ARM_ENV_PREFIX/JAVA_TOOL_OPTIONS" --value="$PIN_TRACE_OPT" --type String --overwrite >/dev/null
  elif [ "$cur" = "$PIN_TRACE_OPT" ]; then
    delete_ssm_key "$ARM_ENV_PREFIX/JAVA_TOOL_OPTIONS"
  fi
}
clear_arm_env() {
  local k
  for k in "${ARM_ENV_KEYS[@]}"; do
    delete_ssm_key "$ARM_ENV_PREFIX/$k"
  done
  clear_pin_trace
}
