#!/usr/bin/env bash
# 장애 격리 주실험(#197) — 운영 앱 인스턴스 안에서 운영 RDS 에 SQL 파일을 실행한다.
#
# RDS 는 VPC 안에서만 닿고 접속 정보는 /opt/app/.env(root 전용)에 있다. 비밀번호를 인스턴스 밖으로
# 꺼내지 않도록 여기서 읽어 psql 프로세스 환경(PGPASSWORD)에만 넘긴다. 화면·파일에 쓰지 않는다.
#
# 사용 (root):  sudo bash bulkhead-sql.sh <file.sql>
set -euo pipefail

SQL="${1:?sql 파일}"
ENV_FILE=/opt/app/.env

val() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

# DB_URL=jdbc:postgresql://<host>:<port>/<db>
URL=$(val DB_URL)
HOSTPORT=${URL#jdbc:postgresql://}; HOSTPORT=${HOSTPORT%%/*}
DB=${URL##*/}; DB=${DB%%\?*}
HOST=${HOSTPORT%%:*}; PORT=${HOSTPORT##*:}
[ "$PORT" != "$HOST" ] || PORT=5432

if ! command -v psql >/dev/null 2>&1; then
  dnf install -y -q postgresql16 >/dev/null 2>&1 || dnf install -y -q postgresql15 >/dev/null 2>&1
fi

PGPASSWORD="$(val DB_PASSWORD)" psql -h "$HOST" -p "$PORT" -U "$(val DB_USERNAME)" -d "$DB" \
  -v ON_ERROR_STOP=1 -f "$SQL"
