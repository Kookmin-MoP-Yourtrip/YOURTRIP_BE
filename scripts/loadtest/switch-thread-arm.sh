#!/usr/bin/env bash
# App EC2에서 arm을 전환한다 — /opt/app/.env의 측정용 키를 바꾸고 재기동한 뒤 적용을 검증한다.
#
# .env는 systemd EnvironmentFile이라 여기 적힌 값이 실제 OS 환경변수로 주입되고, Spring의
# relaxed binding으로 SERVER_TOMCAT_THREADS_MAX → server.tomcat.threads.max에 매핑된다.
# 그래서 JAR을 다시 빌드하지 않고 같은 바이너리로 arm만 바꿀 수 있다.
#
# 키는 append가 아니라 **치환**한다. 두 배치가 같은 키를 append했다가 spring-dotenv의 중복 키
# IllegalStateException으로 앱이 재시작 루프에 빠진 사고가 있었다
# (docs/tasks/cache-effect-measurement/environment.md "운영 중 겪은 사고").
#
# 사용:  sudo switch-thread-arm.sh <maxThreads|default> [snc=true|false]
#   default  → SERVER_TOMCAT_THREADS_MAX 키 제거(=Tomcat 기본 200)
#   snc=false → YOURTRIP_REDIS_SHARE_NATIVE_CONNECTION=false, 아니면 키 제거(=기본 true)
set -euo pipefail

MAX="${1:?maxThreads|default}"
SNC="${2:-true}"
ENV_FILE=/opt/app/.env
SERVICE=yourtrip-app
BASE_URL=http://localhost:8080

set_key() { # set_key KEY VALUE  (VALUE 비면 제거)
  local key="$1" val="${2:-}"
  sed -i "/^${key}=/d" "$ENV_FILE"
  [ -n "$val" ] && echo "${key}=${val}" >> "$ENV_FILE"
  return 0
}

if [ "$MAX" = "default" ]; then set_key SERVER_TOMCAT_THREADS_MAX ""; else set_key SERVER_TOMCAT_THREADS_MAX "$MAX"; fi
if [ "$SNC" = "false" ]; then set_key YOURTRIP_REDIS_SHARE_NATIVE_CONNECTION false; else set_key YOURTRIP_REDIS_SHARE_NATIVE_CONNECTION ""; fi

# 중복 키가 남아 있으면 즉시 실패시킨다(spring-dotenv가 기동을 거부한다).
dups=$(grep -oE '^[A-Z_]+=' "$ENV_FILE" | sort | uniq -d || true)
[ -z "$dups" ] || { echo "duplicate keys in $ENV_FILE: $dups" >&2; exit 1; }

systemctl restart "$SERVICE"

for i in $(seq 1 60); do
  if curl -sf "$BASE_URL/actuator/health" | grep -q '"status":"UP"'; then break; fi
  sleep 2
  [ "$i" -lt 60 ] || { echo "health not UP after 120s" >&2; journalctl -u "$SERVICE" -n 30 --no-pager >&2; exit 1; }
done

metrics=$(curl -sf "$BASE_URL/actuator/prometheus")
cfg_max=$(awk '/^tomcat_threads_config_max_threads/ {print $2}' <<< "$metrics" | head -1)
expected=$([ "$MAX" = "default" ] && echo 200 || echo "$MAX")
printf 'tomcat_threads_config_max_threads=%s (expected %s)\n' "$cfg_max" "$expected"
[ "${cfg_max%.*}" = "$expected" ] || { echo "maxThreads not applied" >&2; exit 1; }

# 활성 프로필과 SNC 스위치는 기동 로그로 확인한다.
journalctl -u "$SERVICE" --since -3min --no-pager | grep -E 'profile.*active|share-native-connection' | tail -3
