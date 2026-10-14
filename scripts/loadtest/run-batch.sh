#!/usr/bin/env bash
# 로컬(개발 머신)에서 arm 배치를 돌리는 드라이버 — App EC2의 arm 전환, k6 EC2의 부하+폴링,
# App EC2의 호스트/schedstat 샘플링을 ssh로 엮는다.
#
# 한 run = (arm 전환·재기동·검증) → 예열 30s → VU 레벨별 constant-vus 90s ×N.
# 레벨마다 k6 EC2에서 poll-metrics.sh를 백그라운드로 띄우고 k6를 돌린 뒤 내려서, 레벨별
# k6 summary(p95/p99)와 Prometheus 스냅샷 파일이 1:1로 남는다. VU 최상위 레벨에서는 App EC2에서
# sample-schedstat.sh를 10초 돌려 스레드 그룹별 런큐 대기 비율을 남긴다.
#
# arm 표기:  T<max>[H<heapMB>][+snc]
#   T200            Tomcat maxThreads만 바꾼다(힙은 건드리지 않는다)
#   T32H1024        maxThreads 32 + -Xmx1024m
#   T200+snc        shareNativeConnection=false
#   T32+nice10      예열 직후 lettuce 이벤트루프 스레드에 nice -10(renice-lettuce.sh, #210)
# H 토큰이 없으면 힙을 건드리지 않으므로 기존 배치 이름이 그대로 동작한다.
# + 꼬리 토큰(snc·nice<N>)은 함께 붙일 수 있다(예: T200+snc+nice10).
#
# 필수 환경변수(하드코딩 금지 — 재기동마다 퍼블릭 IP가 바뀌고, IP를 두 곳에 박아뒀다가 한쪽만
# 고쳐 죽은 IP로 ssh하던 사고가 있었다):
#   APP_IP        App EC2 퍼블릭 IP (ssh용)
#   K6_IP         k6 EC2 퍼블릭 IP (ssh용)
#   APP_PRIVATE   App EC2 사설 IP (k6·폴러가 때리는 주소)
#   SSH_KEY       개인키 경로 (terraform/loadtest/yourtrip-loadtest-ssh)
# 선택:
#   ARMS="T200 T64 T32 T16 T8 T200+snc"   REPS=2   LEVELS="5 20 50 200"   LEVEL_SEC=90
#   SCENARIO=levels|mixed  (mixed는 popular-mixed.js 450s 1회 — 보통 FLUSH_REDIS=1과 함께 쓴다)
#   OUT=./results/<batch>  REMOTE_DIR=/tmp/lt  SCHEDSTAT=1  SCHEDLAT=0  TAG=<접두사>
#   FLUSH_REDIS=1  arm 전환(재기동·워머) 직후 App EC2에서 FLUSHALL — 상세 캐시를 비워 DB 미스 경로를 만든다
#                  (mixed 시나리오용. ElastiCache는 App SG에서만 열려 있어 App EC2의 /dev/tcp로 보낸다)
#   REDIS_HOST=<엔드포인트>  FLUSH_REDIS=1일 때 필수
#
# 사전 조건: App EC2에 scripts/loadtest/{switch-thread-arm,switch-heap-arm,sample-host,sample-schedstat,renice-lettuce}.sh
#            (SCHEDLAT=1이면 sample-sched-latency.sh·trace-sched-latency.bt도),
#            k6 EC2에 scripts/loadtest/poll-metrics.sh와 scripts/k6/{popular-cold,popular-mixed}.js,
#            scripts/k6/lib/scenarios.mjs 가 있어야 한다(scp).
set -euo pipefail

: "${APP_IP:?}" "${K6_IP:?}" "${APP_PRIVATE:?}" "${SSH_KEY:?}"
ARMS="${ARMS:-T200 T64 T32 T16 T8 T200+snc}"
REPS="${REPS:-2}"
LEVELS="${LEVELS:-5 20 50 200}"
LEVEL_SEC="${LEVEL_SEC:-90}"
SCENARIO="${SCENARIO:-levels}"
REMOTE_DIR="${REMOTE_DIR:-/tmp/lt}"
SCHEDSTAT="${SCHEDSTAT:-1}"
# schedstat 창 길이. 요청당 전환 횟수로 정규화하려면 창이 길수록 표본이 안정된다(#97에서
# 10초 -> 60초로 올렸다). 창은 레벨 구간의 한가운데에 놓는다.
SCHEDSTAT_SEC="${SCHEDSTAT_SEC:-10}"
# 1이면 최상위 레벨에서 sample-schedstat.sh 대신 sample-sched-latency.sh를 부른다 — bpftrace로
# 런큐 대기 분포를 잡고, 같은 창의 schedstat과 대조한다(lettuce-dispatch-latency.md).
# App EC2에 bpftrace가 설치돼 있어야 한다(sudo dnf install -y bpftrace).
SCHEDLAT="${SCHEDLAT:-0}"
# SCHEDLAT 래퍼를 레벨 중앙보다 몇 초 앞당겨 띄울지. 기본 0 — 실제 앱(VU 200)에서 bpftrace 부착은
# 3.8~4.1초였다(#207). 합성 포화 조건(워커 32)의 25초를 근거로 20초를 앞당겼더니 창이 레벨 시작 후
# 약 25초간의 TPS 램프업(VU 5 예열 → VU 200)과 겹쳐 G4가 −16~27%로 부풀었다. 부착이 25초까지
# 늘어도 창은 30 + 25 + 30 = 85초에 닫혀 90초 레벨 안에 든다.
ATTACH_LEAD="${ATTACH_LEAD:-0}"
# arm 전환 직후 예열. 재기동 직후 첫 고부하 run은 JIT이 덜 데워져 TPS가 20% 이상 낮게 나온다
# (실측: 2,253 -> 2,976). 낮은 VU 레벨을 먼저 도는 것이 추가 예열을 겸한다.
WARMUP_SEC="${WARMUP_SEC:-30}"
FLUSH_REDIS="${FLUSH_REDIS:-0}"
TAG="${TAG:-$(date +%m%d-%H%M)}"
OUT="${OUT:-./results/$TAG}"
mkdir -p "$OUT"

SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o ServerAliveInterval=30 -o BatchMode=yes)
app() { ssh "${SSH_OPTS[@]}" "ec2-user@$APP_IP" "$@"; }
k6h() { ssh "${SSH_OPTS[@]}" "ec2-user@$K6_IP" "$@"; }
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/batch.log"; }

app "mkdir -p $REMOTE_DIR"; k6h "mkdir -p $REMOTE_DIR"

ARM_NICE=""  # switch_arm이 채운다 — 예열 뒤 apply_nice가 읽는다

switch_arm() { # T32 | T200+snc | T32H1024 | T32+nice10
  local arm="$1" max heap="" snc=true body tok
  body="${arm%%+*}"                       # + 꼬리를 떼고 T<max>[H<heap>]만 남긴다
  ARM_NICE=""
  for tok in $(tr '+' ' ' <<< "${arm#"$body"}"); do
    case "$tok" in
      snc) snc=false ;;
      nice[0-9]*) ARM_NICE="-${tok#nice}" ;;  # 우대만 실험하므로 토큰의 숫자는 음수 nice로 읽는다
      *) echo "unknown arm token '+$tok' in $arm" >&2; exit 1 ;;
    esac
  done
  max="${body#T}"
  if [[ "$max" == *H* ]]; then            # H 토큰이 있으면 힙 축이 붙은 arm이다
    heap="${max#*H}"
    max="${max%%H*}"
  fi
  # 예전에는 T200을 default(키 제거)로 넘겼다 — Tomcat 기본값이 200이라 같은 뜻이었기 때문이다.
  # #88이 application-prod.yml에 server.tomcat.threads.max: 32를 넣으면서 그 전제가 깨졌다.
  # 이제 키를 지우면 200이 아니라 32가 되므로, 모든 arm에서 값을 명시적으로 넘긴다.
  log "switch arm=$arm (max=$max heap=${heap:-unchanged} snc=$snc nice=${ARM_NICE:-0})"
  # 힙은 .env의 JVM_OPTS와 systemd 드롭인만 갈아두고 재기동하지 않는다. 재기동·health
  # 폴링·적용 검증은 switch-thread-arm.sh가 하므로, arm당 재기동이 1회로 유지된다.
  if [ -n "$heap" ]; then
    app "sudo bash $REMOTE_DIR/switch-heap-arm.sh set $heap" | tee -a "$OUT/batch.log"
  fi
  app "sudo bash $REMOTE_DIR/switch-thread-arm.sh $max $snc" | tee -a "$OUT/batch.log"
  # 기동된 뒤에야 확인할 수 있다. JVM_OPTS가 비면 $JVM_OPTS가 사라져 JVM이 조용히
  # ergonomics 기본(2GB의 25% = 약 494MB)으로 뜨는데 448m과 비슷해 눈으로 구분되지 않는다.
  if [ -n "$heap" ]; then
    app "sudo bash $REMOTE_DIR/switch-heap-arm.sh verify $heap" | tee -a "$OUT/batch.log"
  fi
  if [ "$FLUSH_REDIS" = "1" ]; then
    : "${REDIS_HOST:?FLUSH_REDIS=1 needs REDIS_HOST}"
    log "FLUSHALL on $REDIS_HOST"
    app "exec 3<>/dev/tcp/$REDIS_HOST/6379; printf 'FLUSHALL\r\nDBSIZE\r\nQUIT\r\n' >&3; timeout 3 cat <&3 | tr -d '\r'; exec 3>&-" | tee -a "$OUT/batch.log"
  fi
}

# run_level <run-id> <vus> <sec>  — k6 EC2에서 폴러+k6, App EC2에서 호스트 샘플러
run_level() {
  local run="$1" vus="$2" sec="$3"
  local hostdur=$((sec + 15))
  app "nohup bash $REMOTE_DIR/sample-host.sh $REMOTE_DIR/$run.host.tsv $hostdur >/dev/null 2>&1 &"
  if [ "$SCHEDLAT" = "1" ] && [ "$vus" = "${LEVELS##* }" ]; then
    # schedstat을 bpftrace와 같은 창으로 묶어 돈다 — 산출물(.schedstat.txt·.schedlat.json·.env.txt)은
    # App EC2의 $REMOTE_DIR/$run.* 에 남고 배치 끝의 scp가 함께 회수한다
    local lead=$(( (sec - SCHEDSTAT_SEC) / 2 - ATTACH_LEAD )); [ "$lead" -lt 0 ] && lead=0
    ( sleep "$lead"; app "bash $REMOTE_DIR/sample-sched-latency.sh $REMOTE_DIR/$run $SCHEDSTAT_SEC" >> "$OUT/batch.log" 2>&1 ) &
    local sched_pid=$! sched_kind=schedlat
  elif [ "$SCHEDSTAT" = "1" ] && [ "$vus" = "${LEVELS##* }" ]; then
    ( sleep $(( (sec - SCHEDSTAT_SEC) / 2 )); app "bash $REMOTE_DIR/sample-schedstat.sh $SCHEDSTAT_SEC" > "$OUT/$run.schedstat.txt" 2>&1 ) &
    local sched_pid=$!
  fi
  k6h "cd /opt/app; bash $REMOTE_DIR/poll-metrics.sh http://$APP_PRIVATE:8080 $REMOTE_DIR/$run.prom >/dev/null 2>&1 & PP=\$!;
       sleep 3; T0=\$(date +%s.%N);
       k6 run -q --summary-trend-stats 'avg,min,med,max,p(90),p(95),p(99)' -e BASE_URL=http://$APP_PRIVATE:8080 -e VUS=$vus -e DURATION=${sec}s \
              --summary-export=$REMOTE_DIR/$run.k6.json scripts/k6/popular-cold.js > $REMOTE_DIR/$run.k6.log 2>&1;
       T1=\$(date +%s.%N); sleep 3; kill \$PP; echo \"t0=\$T0 t1=\$T1\" > $REMOTE_DIR/$run.window; cat $REMOTE_DIR/$run.window" \
      | tee -a "$OUT/batch.log"
  # SCHEDLAT 샘플러가 실패하면 그 run의 bpftrace·schedstat 결과가 비거나 반쪽이 된다. 배치 전체를
  # 멈추지는 않는다(40분짜리 배치를 샘플러 하나 때문에 중단하면 다른 arm의 정상 데이터까지 잃는다).
  # 대신 batch.log에 경고를 남기고 실패 표식 파일을 만들어, 사후 집계에서 그 run을 바로 걸러낸다.
  # 기존 SCHEDSTAT 경로는 이전처럼 결과를 무시한다.
  if [ -n "${sched_pid:-}" ]; then
    if ! wait "$sched_pid" && [ "${sched_kind:-}" = "schedlat" ]; then
      log "WARN schedlat sampler failed for $run — 이 run의 추적 결과를 쓰지 말 것"
      : > "$OUT/$run.schedlat.FAILED"
    fi
  fi
}

run_mixed() {
  local run="$1"
  app "nohup bash $REMOTE_DIR/sample-host.sh $REMOTE_DIR/$run.host.tsv 480 >/dev/null 2>&1 &"
  k6h "cd /opt/app; bash $REMOTE_DIR/poll-metrics.sh http://$APP_PRIVATE:8080 $REMOTE_DIR/$run.prom >/dev/null 2>&1 & PP=\$!;
       sleep 3; T0=\$(date +%s.%N);
       k6 run -q --summary-trend-stats 'avg,min,med,max,p(90),p(95),p(99)' -e BASE_URL=http://$APP_PRIVATE:8080 --summary-export=$REMOTE_DIR/$run.k6.json scripts/k6/popular-mixed.js > $REMOTE_DIR/$run.k6.log 2>&1;
       T1=\$(date +%s.%N); sleep 3; kill \$PP; echo \"t0=\$T0 t1=\$T1\" > $REMOTE_DIR/$run.window; cat $REMOTE_DIR/$run.window" \
      | tee -a "$OUT/batch.log"
}

for rep in $(seq 1 "$REPS"); do
  order="$ARMS"
  # 홀수 rep은 정순, 짝수 rep은 역순 — 시간 표류가 특정 arm에만 얹히지 않게
  if [ $((rep % 2)) -eq 0 ]; then order=$(echo "$ARMS" | tr ' ' '\n' | tac | tr '\n' ' '); fi
  for arm in $order; do
    switch_arm "$arm"
    log "warm-up ${WARMUP_SEC}s"
    k6h "cd /opt/app && k6 run -q -e BASE_URL=http://$APP_PRIVATE:8080 -e VUS=5 -e DURATION=${WARMUP_SEC}s scripts/k6/popular-cold.js >/dev/null 2>&1"
    # nice는 예열 **뒤에** 건다 — 재기동으로 tid가 바뀌었고, netty 이벤트루프는 첫 작업이 들어올 때
    # 생긴다. 기준 arm도 0으로 같은 검증을 거쳐(게이트 N2) 재기동이 nice를 초기화했음을 남긴다.
    # 실패하면 배치를 멈춘다 — nice가 안 걸린 run을 nice arm으로 기록하면 판정 자체가 틀린다.
    app "sudo bash $REMOTE_DIR/renice-lettuce.sh ${ARM_NICE:-0}" | tee -a "$OUT/batch.log"
    if [ "$SCENARIO" = "mixed" ]; then
      run="${TAG}_${arm}_mixed_r${rep}"; log "run $run"; run_mixed "$run"
    else
      for vus in $LEVELS; do
        run="${TAG}_${arm}_vu${vus}_r${rep}"; log "run $run"; run_level "$run" "$vus" "$LEVEL_SEC"
      done
    fi
  done
done

sleep 20  # 마지막 run의 호스트 샘플러(sec+15)가 끝나길 기다린다
log "collect results → $OUT"
scp "${SSH_OPTS[@]}" -q "ec2-user@$K6_IP:$REMOTE_DIR/${TAG}_*" "$OUT/" || true
scp "${SSH_OPTS[@]}" -q "ec2-user@$APP_IP:$REMOTE_DIR/${TAG}_*" "$OUT/" || true
log "done"
