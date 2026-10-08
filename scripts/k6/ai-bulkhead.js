// AI 코스 생성 장애 격리(bulkhead) 주실험 — 회차 하나 (#197)
//
// 설계: docs/tasks/ai-bulkhead-loadtest/README.md
// 질문: AI 요청이 몰릴 때 피해가 AI 기능 안에 갇히는가, AI 와 무관한 API 까지 번지는가.
//
// 회차 하나의 구간(기본값):
//   [웜업 120s] → [기준선 120s] → [AI 도착 300s] → [회복 관측 180s]  = 12분
//   - 배경 트래픽(/popular + 코스 상세)은 처음부터 끝까지 일정한 속도로 흐른다
//   - 웜업에는 AI 를 분당 2건 넣어 새 인스턴스의 AI 경로 JIT 를 데운다(배경 트래픽만으로는 안 데워진다)
//   - AI 도착 구간에는 단계의 도착률(RATE_PER_MIN)로 포아송 도착을 넣는다. 시드가 같으면 A·B 회차가
//     정확히 같은 도착 시각·입력 순서를 받는다(lib/arrival.mjs)
//
// 배경 요청을 열린 모델(constant-arrival-rate)로 보내는 이유: 앞 요청의 응답을 기다리면 워커가 고갈됐을 때
// 보내는 속도 자체가 떨어져 대기 시간이 가려진다. 대신 응답이 늦어지면 VU 가 많이 필요하므로, 배경 요청의
// 타임아웃(BG_TIMEOUT_SEC, 기본 10초)으로 필요한 VU 를 묶는다 — 60초면 초당 20건에 VU 1,200개가 넘게 들고,
// VU 가 모자라면 k6 가 요청을 건너뛰어(dropped_iterations) 가장 나쁜 구간의 데이터가 빠진다. 판정 기준이
// "p99 1초 초과 = 장애"라 10초에서 잘라도 판정은 바뀌지 않는다(설계 문서 5-3).
//
// 요청별 결과는 stderr 에 한 줄씩 남는다(aggregate-bulkhead.py 가 읽는다).
//   AIREQ {json}  — AI 요청. phase 는 warmup | main
//   BGREQ {json}  — 배경 요청. api 는 popular | detail
//   BHRUN {json}  — 실행 조건(맨 앞 한 줄)
//
// 사용 예 (분당 40건, A 첫 회차):
//   k6 run -e BASE_URL=https://api.example.com -e JWT="$TOKEN" -e RATE_PER_MIN=40 -e SEED=4001 \
//          -e LABEL=r40-A1 --summary-export=r40-A1.json scripts/k6/ai-bulkhead.js 2> r40-A1.log

import http from 'k6/http';
import { arrivalSchedule } from './lib/arrival.mjs';
import { buildPopularRequest } from './lib/scenarios.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT = __ENV.JWT || '';
const LABEL = __ENV.LABEL || '';
const RATE_PER_MIN = parseFloat(__ENV.RATE_PER_MIN || '5');
const SEED = parseInt(__ENV.SEED || '197', 10);

const WARMUP_SEC = parseInt(__ENV.WARMUP_SEC || '120', 10);
const BASELINE_SEC = parseInt(__ENV.BASELINE_SEC || '120', 10);
const AI_SEC = parseInt(__ENV.AI_SEC || '300', 10);
// 3분 안에 회복하지 않으면 집계기가 '회복 안 됨(3분 이상)'으로 기록한다(설계 문서 6-5).
const RECOVERY_SEC = parseInt(__ENV.RECOVERY_SEC || '180', 10);
const WARMUP_AI_PER_MIN = parseFloat(__ENV.WARMUP_AI_PER_MIN || '2');
// 웜업 도착은 단계와 무관하게 같은 일정이어야 회차끼리 같은 상태에서 기준선에 들어간다.
const WARMUP_SEED = parseInt(__ENV.WARMUP_SEED || '1970', 10);

const BG_POPULAR_RPS = parseInt(__ENV.BG_POPULAR_RPS || '10', 10);
const BG_DETAIL_RPS = parseInt(__ENV.BG_DETAIL_RPS || '10', 10);
const BG_TIMEOUT_SEC = parseInt(__ENV.BG_TIMEOUT_SEC || '10', 10);
// 운영 DB 에 실제로 있는 업로드 코스 ID 범위. 측정 전 점검(설계 문서 10-3)에서 확인한 값을 넘긴다.
const DETAIL_ID_MIN = parseInt(__ENV.DETAIL_ID_MIN || '2', 10);
const DETAIL_ID_MAX = parseInt(__ENV.DETAIL_ID_MAX || '3000', 10);

// 첫 요청까지의 여유. k6 VU 초기화가 끝난 뒤 시작하게 한다.
const LEAD_MS = 5000;
// FE OkHttp readTimeout = ALB idle_timeout 60초. 여유 5초는 응답 수신 마무리용이다.
const AI_TIMEOUT_SEC = 60;
const AI_MAX_DURATION_SEC = AI_TIMEOUT_SEC + 5;

if (!JWT) {
  throw new Error('POST /api/my-courses/ai 는 인증이 필요하다 — -e JWT=... 를 넘겨라');
}
if (!(DETAIL_ID_MIN >= 1 && DETAIL_ID_MAX >= DETAIL_ID_MIN)) {
  throw new Error(`상세 코스 ID 범위가 잘못됐다: ${DETAIL_ID_MIN}..${DETAIL_ID_MAX}`);
}

// 구간 경계(테스트 시작 기준 ms). 집계기가 같은 값을 BHRUN 줄에서 읽는다.
const PHASES = {
  warmupStartMs: LEAD_MS,
  baselineStartMs: LEAD_MS + WARMUP_SEC * 1000,
  aiStartMs: LEAD_MS + (WARMUP_SEC + BASELINE_SEC) * 1000,
  recoveryStartMs: LEAD_MS + (WARMUP_SEC + BASELINE_SEC + AI_SEC) * 1000,
  endMs: LEAD_MS + (WARMUP_SEC + BASELINE_SEC + AI_SEC + RECOVERY_SEC) * 1000,
};

// ai-course-arrival.js 와 같은 입력(지역 10 × 키워드 세트 3, 3일 코스). 도착 순번으로 순환한다.
const REGIONS = ['경주', '부산', '제주', '서울', '강릉', '순천', '영주', '공주', '통영', '삼척'];
const KEYWORD_SETS = {
  A: ['WALK', 'COUPLE', 'HEALING', 'SENSIBILITY', 'COST_EFFECTIVE'],
  B: ['CAR', 'FAMILY', 'NATURE', 'NORMAL'],
  C: ['WALK', 'FRIENDS', 'FOOD', 'ACTIVITY', 'PREMIUM'],
};
const INPUTS = REGIONS.flatMap((region) =>
  Object.keys(KEYWORD_SETS).map((set) => ({ region, set })));

// init 코드는 VU 마다 다시 실행되지만 시드가 같아 모든 VU 가 같은 일정을 얻는다.
const WARMUP_ARRIVALS = arrivalSchedule(WARMUP_AI_PER_MIN, WARMUP_SEC, WARMUP_SEED);
const MAIN_ARRIVALS = arrivalSchedule(RATE_PER_MIN, AI_SEC, SEED);

function aiScenario(phase, i, offsetMs) {
  return {
    executor: 'per-vu-iterations',
    vus: 1,
    iterations: 1,
    startTime: `${offsetMs}ms`,
    maxDuration: `${AI_MAX_DURATION_SEC}s`,
    exec: 'aiCourse',
    env: { PHASE: phase, ARRIVAL: String(i), OFFSET_MS: String(offsetMs) },
    tags: { api: 'ai', phase },
  };
}

function backgroundScenario(exec, rps) {
  return {
    executor: 'constant-arrival-rate',
    rate: rps,
    timeUnit: '1s',
    // 웜업 시작부터 회복 관측 끝까지 흐른다.
    startTime: `${PHASES.warmupStartMs}ms`,
    duration: `${(PHASES.endMs - PHASES.warmupStartMs) / 1000}s`,
    // 응답이 타임아웃까지 늦어져도 제때 보낼 수 있는 VU 수 + 여유. 처음부터 전부 할당한다 — 부하 도중에
    // VU 를 늘리면 그 초기화 사이에 보낼 요청을 건너뛴다(모의 서버 검증에서 150건 실측). 하필 응답이
    // 늦어지는 가장 나쁜 구간에서 일어나므로 미리 할당이 필수다.
    preAllocatedVUs: rps * (BG_TIMEOUT_SEC + 5),
    maxVUs: rps * (BG_TIMEOUT_SEC + 5),
    exec,
    tags: { api: exec },
  };
}

function buildScenarios() {
  const scenarios = {};
  WARMUP_ARRIVALS.forEach((atMs, i) => {
    scenarios[`ai_warmup_${i}`] = aiScenario('warmup', i, PHASES.warmupStartMs + atMs);
  });
  MAIN_ARRIVALS.forEach((atMs, i) => {
    scenarios[`ai_main_${i}`] = aiScenario('main', i, PHASES.aiStartMs + atMs);
  });
  scenarios.popular = backgroundScenario('popular', BG_POPULAR_RPS);
  scenarios.detail = backgroundScenario('detail', BG_DETAIL_RPS);
  return scenarios;
}

export const options = {
  scenarios: buildScenarios(),
  // 판정은 요청별 로그로 하므로 임계값은 summary-export 에 태그별 하위 지표를 싣는 장치일 뿐이다.
  thresholds: {
    'http_req_duration{api:popular}': ['max>=0'],
    'http_req_duration{api:detail}': ['max>=0'],
    'http_req_duration{api:ai}': ['max>=0'],
    // dropped_iterations 에는 시나리오 tags(api)가 붙지 않고 scenario 태그만 붙는다.
    'dropped_iterations{scenario:popular}': ['count>=0'],
    'dropped_iterations{scenario:detail}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

// 실행 조건을 로그 맨 앞에 남긴다. setupEpochMs 는 기록용이고, 집계기는 테스트 시작 시각을 요청 로그
// (송신 시각 − 예정 오프셋)로 추정한다 — setup 과 시나리오 시작 사이의 간격에 기대지 않기 위해서다.
export function setup() {
  console.error(`BHRUN ${JSON.stringify({
    label: LABEL, ratePerMin: RATE_PER_MIN, seed: SEED, warmupSeed: WARMUP_SEED,
    warmupAiPerMin: WARMUP_AI_PER_MIN, phases: PHASES,
    warmupArrivals: WARMUP_ARRIVALS.length, mainArrivals: MAIN_ARRIVALS.length,
    bgPopularRps: BG_POPULAR_RPS, bgDetailRps: BG_DETAIL_RPS, bgTimeoutSec: BG_TIMEOUT_SEC,
    detailIdRange: [DETAIL_ID_MIN, DETAIL_ID_MAX], setupEpochMs: Date.now(),
  })}`);
}

export function aiCourse() {
  const phase = __ENV.PHASE;
  const arrival = parseInt(__ENV.ARRIVAL, 10);
  const offsetMs = parseInt(__ENV.OFFSET_MS, 10);
  // 웜업과 본 도착이 같은 입력부터 시작하지 않게 순번을 어긋나게 둔다.
  const input = INPUTS[(phase === 'warmup' ? arrival + 15 : arrival) % INPUTS.length];
  const body = JSON.stringify({
    location: input.region,
    startDate: '2026-11-06',
    endDate: '2026-11-08',
    keywords: KEYWORD_SETS[input.set],
  });

  const sentAtMs = Date.now();
  const res = http.post(`${BASE_URL}/api/my-courses/ai`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${JWT}` },
    timeout: `${AI_TIMEOUT_SEC}s`,
    tags: { api: 'ai', phase },
  });

  let errorCode = '';
  if (res.status !== 201) {
    try {
      errorCode = JSON.parse(res.body).code || '';
    } catch (e) {
      errorCode = '';
    }
  }
  console.error(`AIREQ ${JSON.stringify({
    phase, arrival, offsetMs, sentAtMs, status: res.status,
    durationMs: Math.round(res.timings.duration), errorCode,
  })}`);
}

function logBackground(api, sentAtMs, res) {
  // 줄 수가 많아(초당 20건) 키를 짧게 둔다. s=0 은 타임아웃·연결 실패다.
  console.error(`BGREQ ${JSON.stringify({
    api, t: sentAtMs, s: res.status, ms: Math.round(res.timings.duration),
  })}`);
}

export function popular() {
  const req = buildPopularRequest(BASE_URL, 'mixed');
  const sentAtMs = Date.now();
  const res = http.get(req.url, { timeout: `${BG_TIMEOUT_SEC}s`, tags: { api: 'popular' } });
  logBackground('popular', sentAtMs, res);
}

export function detail() {
  const id = DETAIL_ID_MIN + Math.floor(Math.random() * (DETAIL_ID_MAX - DETAIL_ID_MIN + 1));
  const sentAtMs = Date.now();
  const res = http.get(`${BASE_URL}/api/upload-courses/${id}`, {
    timeout: `${BG_TIMEOUT_SEC}s`,
    tags: { api: 'detail', name: 'detail' },
  });
  logBackground('detail', sentAtMs, res);
}
