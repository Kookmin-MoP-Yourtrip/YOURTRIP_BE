#!/usr/bin/env bash
# 메일 발송 측정(#221)의 SMTP 장애 주입 — Toxiproxy 런타임 API로 앱→Mailpit 구간에 독성을 넣고 뺀다.
#
# 설계: docs/tasks/mail-async/measurement-design.md 2-1절
#
#   latency <ms>   서버→앱 방향의 데이터 조각마다 <ms> 지연. 발송 한 번이 SMTP 응답 약 10개를 기다리므로
#                  시도 지연은 대략 10 × <ms>다 — 계산을 믿지 말고 사전 점검 G3에서 mail.send.attempt로 보정한다.
#   hang           연결은 받아 주되 서버 데이터를 전부 버리고 닫지도 않는다(timeout 독성, timeout=0).
#                  데이터를 쌓지 않고 버리므로, 독성을 빼도 이미 걸린 연결에는 응답이 오지 않는다.
#   clear          모든 독성 제거
#   status         현재 독성 목록
#
# 사용:  mail-toxic.sh <latency <ms>|hang|clear|status>   (TOXIPROXY_URL 기본 http://localhost:8474)
set -euo pipefail

API="${TOXIPROXY_URL:-http://localhost:8474}/proxies/smtp/toxics"

clear_all() {
  # 이름을 모를 수 있으므로 목록을 받아 하나씩 지운다. 독성이 없으면 아무것도 하지 않는다.
  for name in $(curl -sf "$API" | grep -o '"name":"[^"]*"' | cut -d'"' -f4); do
    curl -sf -X DELETE "$API/$name" > /dev/null
  done
}

case "${1:-}" in
  latency)
    ms="${2:?latency <ms>}"
    clear_all
    curl -sf -X POST "$API" -H 'Content-Type: application/json' \
      -d "{\"name\":\"latency\",\"type\":\"latency\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"latency\":$ms,\"jitter\":0}}" > /dev/null
    ;;
  hang)
    clear_all
    curl -sf -X POST "$API" -H 'Content-Type: application/json' \
      -d '{"name":"hang","type":"timeout","stream":"downstream","toxicity":1.0,"attributes":{"timeout":0}}' > /dev/null
    ;;
  clear)
    clear_all
    ;;
  status)
    ;;
  *)
    echo "사용: $0 <latency <ms>|hang|clear|status>" >&2
    exit 2
    ;;
esac

curl -sf "$API"
echo
