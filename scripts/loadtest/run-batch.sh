#!/usr/bin/env bash
# 로컬(개발 머신)에서 arm 배치를 돌리는 드라이버 — App EC2의 arm 전환, k6 EC2의 부하+폴링,
# App EC2의 호스트/schedstat 샘플링을 ssh로 엮는다.
#
# 한 run = (arm 전환·재기동·검증) → 예열 30s → VU 레벨별 constant-vus 90s ×N.
# 레벨마다 k6 EC2에서 poll-metrics.sh를 백그라운드로 띄우고 k6를 돌린 뒤 내려서, 레벨별
# k6 summary(p95/p99)와 Prometheus 스냅샷 파일이 1:1로 남는다. VU 최상위 레벨에서는 App EC2에서
# sample-schedstat.sh를 10초 돌려 스레드 그룹별 런큐 대기 비율을 남긴다.
#
# arm 표기:  T<max>[+snc]   예) T200, T32, T200+snc(=shareNativeConnection=false)
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
#   OUT=./results/<batch>  REMOTE_DIR=/tmp/lt  SCHEDSTAT=1  TAG=<접두사>
#   FLUSH_REDIS=1  arm 전환(재기동·워머) 직후 App EC2에서 FLUSHALL — 상세 캐시를 비워 DB 미스 경로를 만든다
#                  (mixed 시나리오용. ElastiCache는 App SG에서만 열려 있어 App EC2의 /dev/tcp로 보낸다)
#   REDIS_HOST=<엔드포인트>  FLUSH_REDIS=1일 때 필수
#
# 사전 조건: App EC2에 scripts/loadtest/{switch-thread-arm,sample-host,sample-schedstat}.sh,
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

switch_arm() { # T32 | T200+snc
  local arm="$1" max snc=true
  max="${arm#T}"; max="${max%%+*}"
  [[ "$arm" == *+snc ]] && snc=false
  # 예전에는 T200을 default(키 제거)로 넘겼다 — Tomcat 기본값이 200이라 같은 뜻이었기 때문이다.
  # #88이 application-prod.yml에 server.tomcat.threads.max: 32를 넣으면서 그 전제가 깨졌다.
  # 이제 키를 지우면 200이 아니라 32가 되므로, 모든 arm에서 값을 명시적으로 넘긴다.
  log "switch arm=$arm (max=$max snc=$snc)"
  app "sudo bash $REMOTE_DIR/switch-thread-arm.sh $max $snc" | tee -a "$OUT/batch.log"
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
  if [ "$SCHEDSTAT" = "1" ] && [ "$vus" = "${LEVELS##* }" ]; then
    ( sleep $(( (sec - SCHEDSTAT_SEC) / 2 )); app "bash $REMOTE_DIR/sample-schedstat.sh $SCHEDSTAT_SEC" > "$OUT/$run.schedstat.txt" 2>&1 ) &
    local sched_pid=$!
  fi
  k6h "cd /opt/app; bash $REMOTE_DIR/poll-metrics.sh http://$APP_PRIVATE:8080 $REMOTE_DIR/$run.prom >/dev/null 2>&1 & PP=\$!;
       sleep 3; T0=\$(date +%s.%N);
       k6 run -q --summary-trend-stats 'avg,min,med,max,p(90),p(95),p(99)' -e BASE_URL=http://$APP_PRIVATE:8080 -e VUS=$vus -e DURATION=${sec}s \
              --summary-export=$REMOTE_DIR/$run.k6.json scripts/k6/popular-cold.js > $REMOTE_DIR/$run.k6.log 2>&1;
       T1=\$(date +%s.%N); sleep 3; kill \$PP; echo \"t0=\$T0 t1=\$T1\" > $REMOTE_DIR/$run.window; cat $REMOTE_DIR/$run.window" \
      | tee -a "$OUT/batch.log"
  [ -n "${sched_pid:-}" ] && wait "$sched_pid" || true
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
