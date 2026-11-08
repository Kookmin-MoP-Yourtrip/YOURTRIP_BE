// 빠른 API 계단 부하 — 플랫폼 스레드 32 와 가상 스레드의 한계점과 무너지는 모양을 비교한다.
//
// 설계: docs/tasks/ai-bulkhead-loadtest/async-variant.md 3-7절
//
// 열린 모델(ramping-arrival-rate)을 쓰는 이유: 닫힌 모델은 서버가 느려지면 k6 도 덜 보내서, 상한이 없는 구조가
// 과부하에서 어떻게 되는지를 볼 수 없다. 도착률을 계단마다 고정하고 서버가 따라오는지만 본다.
//
// 초당 수천 건이라 요청별 로그를 남기지 않는다. 계단별 커스텀 지표로 --summary-export 에 싣는다.
//   fast_sent_s<i>      보낸 수       fast_2xx_s<i>  2xx 수
//   fast_timeout_s<i>   응답 없음     fast_5xx_s<i>  5xx 수     fast_other_s<i>  그 밖(401 등 — 도구 문제)
//   fast_ms_s<i>        지연(계단 앞 SETTLE_SEC 제외)  fast_ms_s<i>_<api>  API 별 지연
//
// 사용:
//   k6 run -e BASE_URL=https://yourtrip.cloud -e JWT="$TOKEN" -e LABEL=s-T1 \
//     --summary-export=summary.json scripts/k6/fast-api-ramp.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { buildPopularRequest } from './lib/scenarios.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT = __ENV.JWT || '';
const LABEL = __ENV.LABEL || '';
const STEPS = (__ENV.STEPS || '200,400,700,1000,1400,1800,2300,2800').split(',').map(Number);
const HOLD_SEC = parseInt(__ENV.HOLD_SEC || '90', 10);
// 계단이 바뀐 직후의 과도 구간은 지연 분포에서 뺀다. 도착 수·오류 수는 계단 전체로 센다.
const SETTLE_SEC = parseInt(__ENV.SETTLE_SEC || '20', 10);
const RECOVERY_RPS = parseInt(__ENV.RECOVERY_RPS || '50', 10);
const RECOVERY_SEC = parseInt(__ENV.RECOVERY_SEC || '120', 10);
const TIMEOUT_SEC = parseInt(__ENV.TIMEOUT_SEC || '5', 10);
const DETAIL_ID_MIN = parseInt(__ENV.DETAIL_ID_MIN || '1', 10);
const DETAIL_ID_MAX = parseInt(__ENV.DETAIL_ID_MAX || '3000', 10);

// 인기 코스는 Redis, 상세는 Redis + 조회수 증분, 내 코스는 인증 필터의 사용자 조회 + 목록 조회라 DB 를 매번 탄다.
const SHARES = { popular: 0.5, detail: 0.3, mine: 0.2 };
const API_NAMES = Object.keys(SHARES);

if (!JWT) {
  throw new Error('GET /api/my-courses 는 인증이 필요하다 — -e JWT=... 를 넘겨라');
}

// 회복 구간은 마지막 계단 번호(STEPS.length)로 센다.
const STEP_COUNT = STEPS.length + 1;
const metrics = [];
for (let i = 0; i < STEP_COUNT; i += 1) {
  const m = {
    sent: new Counter(`fast_sent_s${i}`),
    ok: new Counter(`fast_2xx_s${i}`),
    timeout: new Counter(`fast_timeout_s${i}`),
    err5xx: new Counter(`fast_5xx_s${i}`),
    other: new Counter(`fast_other_s${i}`),
    ms: new Trend(`fast_ms_s${i}`),
    perApi: {},
  };
  API_NAMES.forEach((api) => {
    m.perApi[api] = new Trend(`fast_ms_s${i}_${api}`);
  });
  metrics.push(m);
}

function apiRate(rps, api) {
  return Math.max(1, Math.round(rps * SHARES[api]));
}

function scenarioFor(api) {
  const peak = apiRate(Math.max(...STEPS), api);
  // 전환 1초로 계단을 올린다. 0초 stage 는 k6 가 건너뛰어 계단 경계가 어긋난다.
  const stages = [{ duration: `${HOLD_SEC}s`, target: apiRate(STEPS[0], api) }];
  STEPS.slice(1).forEach((rps) => {
    stages.push({ duration: '1s', target: apiRate(rps, api) });
    stages.push({ duration: `${HOLD_SEC - 1}s`, target: apiRate(rps, api) });
  });
  stages.push({ duration: '1s', target: apiRate(RECOVERY_RPS, api) });
  stages.push({ duration: `${RECOVERY_SEC - 1}s`, target: apiRate(RECOVERY_RPS, api) });
  // 모든 요청이 타임아웃까지 늘어져도 제때 보낼 수 있는 VU 를 처음부터 잡는다. 도중에 늘리면 그 사이 요청을 건너뛴다.
  const vus = peak * (TIMEOUT_SEC + 1);
  return {
    executor: 'ramping-arrival-rate',
    startRate: apiRate(STEPS[0], api),
    timeUnit: '1s',
    preAllocatedVUs: vus,
    maxVUs: vus,
    stages,
    exec: api,
    tags: { api },
  };
}

export const options = {
  discardResponseBodies: true,
  scenarios: Object.fromEntries(API_NAMES.map((api) => [api, scenarioFor(api)])),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  console.error(`FASTRUN ${JSON.stringify({
    label: LABEL, steps: STEPS, holdSec: HOLD_SEC, settleSec: SETTLE_SEC, recoveryRps: RECOVERY_RPS,
    recoverySec: RECOVERY_SEC, timeoutSec: TIMEOUT_SEC, shares: SHARES, setupEpochMs: Date.now(),
  })}`);
}

function record(api, res) {
  const elapsedMs = Date.now() - exec.scenario.startTime;
  const step = Math.min(Math.floor(elapsedMs / (HOLD_SEC * 1000)), STEPS.length);
  const m = metrics[step];
  m.sent.add(1);
  if (res.status === 0) {
    m.timeout.add(1);
  } else if (res.status >= 500) {
    m.err5xx.add(1);
  } else if (res.status >= 200 && res.status < 300) {
    m.ok.add(1);
  } else {
    m.other.add(1);
  }
  if (res.status !== 0 && elapsedMs % (HOLD_SEC * 1000) >= SETTLE_SEC * 1000) {
    m.ms.add(res.timings.duration);
    m.perApi[api].add(res.timings.duration);
  }
}

export function popular() {
  const req = buildPopularRequest(BASE_URL, 'mixed');
  record('popular', http.get(req.url, { timeout: `${TIMEOUT_SEC}s`, tags: { name: 'popular' } }));
}

export function detail() {
  const id = DETAIL_ID_MIN + Math.floor(Math.random() * (DETAIL_ID_MAX - DETAIL_ID_MIN + 1));
  record('detail', http.get(`${BASE_URL}/api/upload-courses/${id}`, {
    timeout: `${TIMEOUT_SEC}s`, tags: { name: 'detail' },
  }));
}

export function mine() {
  record('mine', http.get(`${BASE_URL}/api/my-courses`, {
    headers: { Authorization: `Bearer ${JWT}` }, timeout: `${TIMEOUT_SEC}s`, tags: { name: 'mine' },
  }));
}
