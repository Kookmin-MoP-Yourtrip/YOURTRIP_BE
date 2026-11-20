#!/usr/bin/env bash
# 측정 회차의 SSM env/ 값을 다루는 공용 함수. bulkhead-round.sh · fastapi-round.sh 가 source 한다.
# 전제: SSM_ARTIFACT_KEY_PARAM 이 설정돼 있고 MSYS_NO_PATHCONV=1 이 켜져 있다.
# 이 키들은 운영에 원래 없는 측정 전용 키다. 그 밖의 arm(P 등)은 clear_arm_env 로 지워 운영 기본값으로 뜨게 한다.
ARM_ENV_PREFIX="${SSM_ARTIFACT_KEY_PARAM%/artifact_key}/env"
ARM_ENV_KEYS=(AI_COURSE_ASYNC_ENABLED AI_COURSE_ADMISSION_MAXWORKUNITS AI_COURSE_ADMISSION_MAXREQUESTS SPRING_THREADS_VIRTUAL_ENABLED
  SERVER_TOMCAT_THREADS_MAX YOURTRIP_REDIS_SHAREDCONNECTIONS)
PIN_TRACE_OPT="-Djdk.tracePinnedThreads=short"
# NMT 요약 모드(async-variant.md 3-10절 B층). 네이티브 메모리를 영역별로 나눠 보기 위한 측정 전용 옵션이다.
NMT_OPT="-XX:NativeMemoryTracking=summary"
# Lettuce I/O 스레드 수(async-variant.md 3-9절). Lettuce 가 기본값을 정할 때 이 시스템 속성을 먼저 읽는다.
IO_THREADS_OPT_PREFIX="-Dio.netty.eventLoopThreads="
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
# 측정 arm 이 실제로 쓰는 I/O 스레드 수만 측정 소유 값으로 본다(WS8). 숫자 전체를 허용하면 운영이 같은 옵션을 다른 값으로
# 쓰고 있을 때 그 값을 측정 값으로 오판해 지우거나 덮어쓴다.
IO_THREADS_ALLOWED=(8)
is_allowed_io_threads() {
  local v
  for v in "${IO_THREADS_ALLOWED[@]}"; do [ "$1" = "$v" ] && return 0; done
  return 1
}
# 이 스크립트가 넣는 JAVA_TOOL_OPTIONS 값인가 — 피닝 추적, NMT, 허용 목록의 I/O 스레드 수 중 하나뿐이다.
is_arm_tool_opts() {
  local v
  [ "$1" = "$PIN_TRACE_OPT" ] && return 0
  [ "$1" = "$NMT_OPT" ] && return 0
  for v in "${IO_THREADS_ALLOWED[@]}"; do [ "$1" = "$IO_THREADS_OPT_PREFIX$v" ] && return 0; done
  return 1
}
# JAVA_TOOL_OPTIONS 는 운영이 쓸 수도 있는 키라, 이 스크립트가 넣은 값일 때만 지운다.
clear_pin_trace() {
  local cur
  cur=$(current_tool_opts) || exit 3
  if [ -n "$cur" ] && is_arm_tool_opts "$cur"; then delete_ssm_key "$ARM_ENV_PREFIX/JAVA_TOOL_OPTIONS"; fi
  return 0
}
# 다섯 번째 인자는 Tomcat 워커 상한, 여섯 번째는 Redis 공유 연결 수, 일곱 번째는 Lettuce I/O 스레드 수다(3-9절).
# 여덟 번째는 JAVA_TOOL_OPTIONS 방식이다(3-10절): 비우면 기존대로 가상 스레드일 때 피닝 추적을 넣고,
# none 이면 아무것도 넣지 않으며, nmt 면 NMT 옵션만 넣는다. 메모리 비교 회차는 두 구성의 JVM 옵션을 같게 하려고 쓴다.
# 비우면 키를 지워 운영 기본값으로 뜬다(워커는 prod 32, 공유 연결 1, I/O 스레드는 Lettuce 기본값) —
# 앞 회차가 넣은 값이 다음 회차로 새지 않게 모든 호출에서 정리한다.
set_arm_env() {
  local async="$1" units="$2" requests="$3" virtual="$4" threads_max="${5:-}" shared="${6:-}" io_threads="${7:-}" tool="${8:-}" cur want=""
  [ "$virtual" = true ] && want="$PIN_TRACE_OPT"
  case "$tool" in
    "") ;;
    none) want="" ;;
    nmt) want="$NMT_OPT" ;;
    *) echo "중단: JAVA_TOOL_OPTIONS 방식 '$tool' 을 모른다(none|nmt)" >&2; exit 3 ;;
  esac
  if [ -n "$io_threads" ]; then
    [ -z "$want" ] || { echo "중단: 피닝 추적·NMT 와 I/O 스레드 수를 한 회차에 함께 넣지 않는다" >&2; exit 3; }
    is_allowed_io_threads "$io_threads" || { echo "중단: I/O 스레드 수 $io_threads 는 허용 목록(${IO_THREADS_ALLOWED[*]})에 없다" >&2; exit 3; }
    want="$IO_THREADS_OPT_PREFIX$io_threads"
  fi
  # JAVA_TOOL_OPTIONS 상태를 먼저 확인한다 — 조회 실패나 운영 값이 있으면 SSM 을 하나도 바꾸지 않은 채 멈춘다.
  # 운영 값을 덮어쓰면 뒤 회차에서 지울 때 원래 값을 되살릴 수 없다.
  cur=$(current_tool_opts) || exit 3
  if [ -n "$want" ] && [ -n "$cur" ] && ! is_arm_tool_opts "$cur"; then
    echo "중단: SSM JAVA_TOOL_OPTIONS 에 운영 값이 있다 — 측정 값과 합칠지 먼저 정한다" >&2
    exit 3
  fi
  aws ssm put-parameter --name "$ARM_ENV_PREFIX/AI_COURSE_ASYNC_ENABLED" --value "$async" --type String --overwrite >/dev/null
  aws ssm put-parameter --name "$ARM_ENV_PREFIX/AI_COURSE_ADMISSION_MAXWORKUNITS" --value "$units" --type String --overwrite >/dev/null
  aws ssm put-parameter --name "$ARM_ENV_PREFIX/AI_COURSE_ADMISSION_MAXREQUESTS" --value "$requests" --type String --overwrite >/dev/null
  aws ssm put-parameter --name "$ARM_ENV_PREFIX/SPRING_THREADS_VIRTUAL_ENABLED" --value "$virtual" --type String --overwrite >/dev/null
  if [ -n "$threads_max" ]; then
    aws ssm put-parameter --name "$ARM_ENV_PREFIX/SERVER_TOMCAT_THREADS_MAX" --value "$threads_max" --type String --overwrite >/dev/null
  else
    delete_ssm_key "$ARM_ENV_PREFIX/SERVER_TOMCAT_THREADS_MAX"
  fi
  if [ -n "$shared" ]; then
    aws ssm put-parameter --name "$ARM_ENV_PREFIX/YOURTRIP_REDIS_SHAREDCONNECTIONS" --value "$shared" --type String --overwrite >/dev/null
  else
    delete_ssm_key "$ARM_ENV_PREFIX/YOURTRIP_REDIS_SHAREDCONNECTIONS"
  fi
  if [ -n "$want" ]; then
    aws ssm put-parameter --name "$ARM_ENV_PREFIX/JAVA_TOOL_OPTIONS" --value="$want" --type String --overwrite >/dev/null
  elif [ -n "$cur" ] && is_arm_tool_opts "$cur"; then
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
