// AI 코스 생성 — 도착률 기반 시나리오 (#193)
//
// ai-course-concurrent.js 는 N명이 같은 순간에 출발하는 최악의 몰림만 잰다. 이 스크립트는 요청이
// 평균 분당 RATE_PER_MIN 건으로 "무작위 간격"(포아송)으로 도착할 때 입장 제한(#192)이 얼마나 자주
// 사람을 돌려보내는지, 받아들인 요청의 품질은 어떤지를 잰다.
//
// 구조: 시드 고정 포아송 일정(lib/arrival.mjs)을 init 에서 만들고, 도착마다 시나리오 하나(VU 1, 반복 1)를
// startTime 으로 띄운다. 각 도착이 자기 VU 를 가지므로 앞사람의 응답을 기다리지 않는다(열린 모델).
// 같은 SEED 면 도착 시각과 입력 순서가 완전히 같아, 상한 4·5 를 같은 도착으로 비교할 수 있다.
// 거절(429)된 요청은 재시도하지 않는다 — 지금 FE 와 같다(STEP-arrival-rate.md 1-5).
//
// 사용 예 (분당 5건, 12분):
//   k6 run -e JWT="$TOKEN" -e RATE_PER_MIN=5 -e DURATION_SEC=720 -e SEED=193 \
//          --summary-export=results/ai-arrival/r5.json scripts/k6/ai-course-arrival.js \
//          2> results/ai-arrival/r5.log
// 요청별 결과는 stderr 에 "AIREQ {json}" 줄로 남는다(aggregate-arrival.py 가 읽는다).

import http from 'k6/http';
import { check } from 'k6';
import { arrivalSchedule } from './lib/arrival.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT = __ENV.JWT || '';
const RATE_PER_MIN = parseFloat(__ENV.RATE_PER_MIN || '5');
const DURATION_SEC = parseInt(__ENV.DURATION_SEC || '720', 10);
const SEED = parseInt(__ENV.SEED || '193', 10);
const FAST_RPS = parseInt(__ENV.FAST_RPS || '2', 10);
// 첫 도착까지의 여유. 빠른 API 시나리오와 서버 웜업이 먼저 돌게 한다.
const LEAD_MS = 5000;
// 클라이언트(OkHttp readTimeout)·ALB idle_timeout 60초 + 여유.
const REQUEST_MAX_SEC = 65;

if (!JWT) {
  throw new Error('POST /api/my-courses/ai 는 인증이 필요하다 — -e JWT=... 를 넘겨라');
}

// ai-course-concurrent.js(= BaselineInputSet)와 같은 지역 10 × 키워드 3. 도착 순번으로 순환한다.
const REGIONS = ['경주', '부산', '제주', '서울', '강릉', '순천', '영주', '공주', '통영', '삼척'];
const KEYWORD_SETS = {
  A: ['WALK', 'COUPLE', 'HEALING', 'SENSIBILITY', 'COST_EFFECTIVE'],
  B: ['CAR', 'FAMILY', 'NATURE', 'NORMAL'],
  C: ['WALK', 'FRIENDS', 'FOOD', 'ACTIVITY', 'PREMIUM'],
};
const INPUTS = REGIONS.flatMap((region) =>
  Object.keys(KEYWORD_SETS).map((set) => ({ region, set })));

// init 코드는 VU 마다 다시 실행되지만 시드가 같아 모든 VU 가 같은 일정을 얻는다.
const ARRIVALS = arrivalSchedule(RATE_PER_MIN, DURATION_SEC, SEED);

function buildScenarios() {
  const scenarios = {};
  ARRIVALS.forEach((atMs, i) => {
    scenarios[`ai_${i}`] = {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      startTime: `${LEAD_MS + atMs}ms`,
      maxDuration: `${REQUEST_MAX_SEC}s`,
      exec: 'aiCourse',
      env: { ARRIVAL: String(i), AT_MS: String(atMs) },
      tags: { api: 'ai' },
    };
  });
  scenarios.fast = {
    executor: 'constant-arrival-rate',
    rate: FAST_RPS,
    timeUnit: '1s',
    duration: `${Math.ceil(LEAD_MS / 1000) + DURATION_SEC + REQUEST_MAX_SEC}s`,
    preAllocatedVUs: 5,
    maxVUs: 20,
    exec: 'fastApi',
    tags: { api: 'fast' },
  };
  return scenarios;
}

export const options = {
  scenarios: buildScenarios(),
  // 임계값은 판정이 아니라 summary-export 에 태그별 하위 지표를 싣기 위한 장치다.
  thresholds: {
    'http_req_duration{api:ai}': ['max>=0'],
    'http_req_duration{api:fast}': ['max>=0'],
    'http_req_failed{api:ai}': ['rate>=0'],
    'http_req_failed{api:fast}': ['rate>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

// 실행 조건을 로그 맨 앞에 남긴다. 집계는 요청별 sentAtMs 로 시간 구간을 나누므로 이 줄은 기록용이다.
export function setup() {
  console.error(`AIRUN ${JSON.stringify({
    mode: 'arrival', ratePerMin: RATE_PER_MIN, durationSec: DURATION_SEC, seed: SEED,
    arrivals: ARRIVALS.length, leadMs: LEAD_MS, setupEpochMs: Date.now(),
  })}`);
}

export function aiCourse() {
  const arrival = parseInt(__ENV.ARRIVAL, 10);
  const scheduledMs = parseInt(__ENV.AT_MS, 10);
  const input = INPUTS[arrival % INPUTS.length];
  const body = JSON.stringify({
    location: input.region,
    startDate: '2026-11-06',
    endDate: '2026-11-08',
    keywords: KEYWORD_SETS[input.set],
  });

  const sentAtMs = Date.now();
  const res = http.post(`${BASE_URL}/api/my-courses/ai`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${JWT}` },
    timeout: '60s',
  });
  check(res, { 'ai 201': (r) => r.status === 201 });

  let errorCode = '';
  if (res.status !== 201) {
    try {
      errorCode = JSON.parse(res.body).code || '';
    } catch (e) {
      errorCode = '';
    }
  }
  console.error(`AIREQ ${JSON.stringify({
    mode: 'arrival', ratePerMin: RATE_PER_MIN, seed: SEED,
    arrival, scheduledMs, sentAtMs, location: input.region, keywordSet: input.set,
    status: res.status, durationMs: Math.round(res.timings.duration), errorCode,
  })}`);
}

export function fastApi() {
  const res = http.get(`${BASE_URL}/api/upload-courses/popular`);
  check(res, { 'fast 200': (r) => r.status === 200 });
}
