#!/usr/bin/env bash
# App EC2에서 JVM 힙 arm을 전환한다 — /opt/app/.env의 JVM_OPTS를 바꾸고, systemd 드롭인이
# ExecStart에서 그 값을 읽게 만든다. switch-thread-arm.sh의 힙 버전이다.
#
# 왜 드롭인인가.
#   힙 플래그는 .env가 아니라 user_data가 쓴 유닛 파일의 ExecStart에 하드코딩돼 있다
#   (-Xmx448m -Xss512k). 템플릿을 고쳐 terraform apply를 하면 ec2_app.tf의
#   user_data_replace_on_change = true 때문에 App EC2가 교체되고, scp로 올린 app.jar와
#   CloudFront 개인키가 함께 사라진다. 드롭인은 리소스 형상이 아니라 실행 상태만 바꾸는
#   조작이라 state drift를 만들지 않는다 — terraform/loadtest/README.md의
#   upload-course-caching 선례("user_data를 적용하지 않고 .env만 직접 고쳤다")와 같은 경로다.
#
# 왜 재기동을 하지 않는가.
#   switch-thread-arm.sh가 이미 재기동·health 폴링·적용 검증을 한다. 여기서도 재기동하면
#   arm당 재기동이 2회가 되어 예열 시간과 측정 창이 흐트러진다. run-batch.sh는
#   set -> switch-thread-arm.sh(재기동) -> verify 순으로 부른다.
#
# 사용:  sudo switch-heap-arm.sh set <heapMB|default> [xssOpt=512k]
#        sudo switch-heap-arm.sh verify <heapMB>
#   set 448        -> JVM_OPTS="-Xmx448m -Xss512k", 드롭인 설치
#   set 1024 1m    -> JVM_OPTS="-Xmx1024m -Xss1m"
#   set default    -> 드롭인·JVM_OPTS 제거(= user_data 원본 ExecStart로 복귀)
#   verify 1024    -> 정말 그 값으로 떴는지 두 갈래로 확인, 아니면 exit 1
set -euo pipefail

ENV_FILE=/opt/app/.env
SERVICE=yourtrip-app
BASE_URL=http://localhost:8080
DROPIN_DIR=/etc/systemd/system/${SERVICE}.service.d
DROPIN=${DROPIN_DIR}/10-jvm-opts.conf

# switch-thread-arm.sh와 같은 idiom — append가 아니라 치환이다. 두 배치가 같은 키를
# append했다가 spring-dotenv의 중복 키 IllegalStateException으로 앱이 재시작 루프에
# 빠진 사고가 있었다(docs/tasks/cache-effect-measurement/environment.md).
set_key() { # set_key KEY VALUE  (VALUE 비면 제거)
  local key="$1" val="${2:-}"
  sed -i "/^${key}=/d" "$ENV_FILE"
  [ -n "$val" ] && echo "${key}=${val}" >> "$ENV_FILE"
  return 0
}

# /actuator/prometheus에서 힙 상한(바이트)을 읽는다. G1은 Eden/Survivor의 max를 -1로
# 내보내고 Old Gen에만 실제 상한을 싣기 때문에 양수만 더한다. 이 합이
# Runtime.getRuntime().maxMemory()와 같다는 항등식은 LoadtestMetricsExposureTest가 잠근다.
heap_max_bytes() {
  curl -sf "$BASE_URL/actuator/prometheus" \
    | awk '/^jvm_memory_max_bytes\{/ && /area="heap"/ { v = $NF + 0; if (v > 0) s += v }
           END { printf "%.0f", s + 0 }'
}

cmd="${1:?set|verify}"

case "$cmd" in
  set)
    HEAP="${2:?heapMB|default}"
    XSS="${3:-512k}"
    if [ "$HEAP" = "default" ]; then
      rm -f "$DROPIN"
      rmdir "$DROPIN_DIR" 2>/dev/null || true
      set_key JVM_OPTS ""
      systemctl daemon-reload
      echo "heap arm=default (드롭인 제거 — user_data 원본 ExecStart로 복귀)"
    else
      # ExecStart는 systemd에서 누적형이라 빈 값으로 한 번 초기화해야 원본이 함께 실행되지 않는다.
      # $JVM_OPTS는 중괄호 없이 써야 systemd가 공백으로 단어 분리한다(${JVM_OPTS}면 인자 1개가 된다).
      mkdir -p "$DROPIN_DIR"
      cat > "$DROPIN" <<'DROPIN_EOF'
[Service]
ExecStart=
ExecStart=/usr/bin/java $JVM_OPTS -jar /opt/app/app.jar
DROPIN_EOF
      set_key JVM_OPTS "-Xmx${HEAP}m -Xss${XSS}"
      systemctl daemon-reload
      echo "heap arm=${HEAP}m (JVM_OPTS=-Xmx${HEAP}m -Xss${XSS})"
    fi

    # 중복 키가 남아 있으면 즉시 실패시킨다(spring-dotenv가 기동을 거부한다).
    dups=$(grep -oE '^[A-Z_]+=' "$ENV_FILE" | sort | uniq -d || true)
    [ -z "$dups" ] || { echo "duplicate keys in $ENV_FILE: $dups" >&2; exit 1; }
    echo "재기동은 하지 않는다 — 호출자(switch-thread-arm.sh)가 한다."
    ;;

  verify)
    HEAP="${2:?heapMB}"
    pid=$(systemctl show -p MainPID --value "$SERVICE")
    [ "$pid" != "0" ] || { echo "service not running" >&2; exit 1; }

    # (1) 플래그가 프로세스에 전달됐는가. JVM_OPTS가 비면 $JVM_OPTS가 빈 문자열로 사라져
    #     JVM이 조용히 ergonomics 기본(2GB의 25% = 약 494MB)으로 뜬다 — 448m과 비슷해
    #     눈으로 구분되지 않으므로 반드시 기계로 확인한다.
    cmdline=$(tr '\0' ' ' < "/proc/$pid/cmdline")
    printf 'cmdline: %s\n' "$cmdline"
    case "$cmdline" in
      *"-Xmx${HEAP}m"*) ;;
      *) echo "-Xmx${HEAP}m not in cmdline" >&2; exit 1 ;;
    esac

    # (2) JVM이 그 값을 실제로 반영했는가. G1이 region 크기 배수로 반올림하므로 2% 허용.
    want=$((HEAP * 1024 * 1024))
    got=$(heap_max_bytes)
    printf 'jvm_memory_max_bytes{area="heap"} 양수 합=%s (expected ~%s)\n' "$got" "$want"
    awk -v w="$want" -v g="$got" 'BEGIN { exit !(g > 0 && (g - w) / w < 0.02 && (w - g) / w < 0.02) }' \
      || { echo "heap max not applied" >&2; exit 1; }
    ;;

  *)
    echo "usage: switch-heap-arm.sh set <heapMB|default> [xssOpt] | verify <heapMB>" >&2
    exit 1
    ;;
esac
