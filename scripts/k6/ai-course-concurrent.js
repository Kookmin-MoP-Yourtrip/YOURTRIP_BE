// LLM 호출 경로 기준선 — 동시 요청 시나리오 (로드맵 2단계, 이슈 #175)
//
// 단일 요청 분포는 AiCourseLatencyBaselineTest(in-process)가 잰다. 이 스크립트는 그쪽이 볼 수 없는
// 것을 본다 — 여러 사용자가 같은 서버의 LLM 슬롯(max-concurrent-calls)을 다툴 때 생기는
// 슬롯 대기 포기·폴백, 그리고 AI 요청이 Tomcat 워커를 붙드는 동안 빠른 API의 지연.
//
// 구조: 라운드 = AI 사용자 N명이 "동시에" 1건씩 보낸다. 라운드마다 별도 시나리오를 만들어
// startTime 으로 띄워 놓는다 — per-vu-iterations 하나로 돌리면 먼저 끝난 VU 가 다음 요청을 먼저
// 보내 "동시"가 무너진다. 라운드 사이 휴지는 마감 뒤 남은 호출이 슬롯을 비울 시간이다.
// 같은 시간 동안 빠른 API(인기 코스 목록, permitAll)를 고정 도착률로 보낸다.
//
// 사용 예 (N=3, 라운드 3, 5일 일정 — TRIP_DAYS 를 빼면 3일):
//   k6 run -e JWT="$TOKEN" -e CONCURRENCY=3 -e TRIP_DAYS=5 \
//          --summary-export=results/ai-concurrent-c3.json scripts/k6/ai-course-concurrent.js \
//          2> results/ai-concurrent-c3.log
// 요청별 결과는 stderr 에 "AIREQ {json}" 줄로 남는다(aggregate-ai.py 가 읽는다).

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT = __ENV.JWT || '';
const CONCURRENCY = parseInt(__ENV.CONCURRENCY || '2', 10);
const ROUNDS = parseInt(__ENV.ROUNDS || '3', 10);
// 라운드 간격. AI 요청은 서버 예산 30초 + 저장에 끝나지만, 남은 호출(슬롯 대기 20초 + HTTP 20초)이
// 다음 라운드에 섞이지 않게 넉넉히 잡는다.
const ROUND_INTERVAL_SEC = parseInt(__ENV.ROUND_INTERVAL_SEC || '90', 10);
const FAST_RPS = parseInt(__ENV.FAST_RPS || '2', 10);
// 여행 일수(시작·종료일 포함). 요청 하나의 LLM 작업 수가 1 + 일수라, 같은 동시 인원이라도 일수가
// 슬롯 부하를 바꾼다(#200). 기본 3은 이 손잡이가 생기기 전의 고정값(11/06~11/08)이다.
// 서버가 받는 1 ~ 5일(AICourseCreateRequest.MAX_TRIP_DAYS)만 허용한다. parseInt 는 '2.9' 를 2 로 조용히 잘라
// 측정 조건이 바뀌므로 문자열 전체를 검사한다.
const TRIP_DAYS_RAW = __ENV.TRIP_DAYS || '3';
if (!/^[1-5]$/.test(TRIP_DAYS_RAW)) {
  throw new Error(`TRIP_DAYS 는 1 ~ 5 의 정수여야 한다 — 받은 값: ${TRIP_DAYS_RAW}`);
}
const TRIP_DAYS = Number(TRIP_DAYS_RAW);
const START_DATE = '2026-11-06';

if (!JWT) {
  throw new Error('POST /api/my-courses/ai 는 인증이 필요하다 — -e JWT=... 를 넘겨라');
}

// BaselineInputSet(지역 10 × 키워드 3)과 같은 값. 순서도 같다 — requestId 1..30 과 대응한다.
const REGIONS = ['경주', '부산', '제주', '서울', '강릉', '순천', '영주', '공주', '통영', '삼척'];
const KEYWORD_SETS = {
  A: ['WALK', 'COUPLE', 'HEALING', 'SENSIBILITY', 'COST_EFFECTIVE'],
  B: ['CAR', 'FAMILY', 'NATURE', 'NORMAL'],
  C: ['WALK', 'FRIENDS', 'FOOD', 'ACTIVITY', 'PREMIUM'],
};
const INPUTS = REGIONS.flatMap((region) =>
  Object.keys(KEYWORD_SETS).map((set) => ({ region, set })));

// 시작일 + (일수 - 1)일. UTC 로 계산해 실행 머신의 시간대와 무관하게 같은 날짜가 나온다.
function endDateOf(startDate, days) {
  const end = new Date(`${startDate}T00:00:00Z`);
  end.setUTCDate(end.getUTCDate() + days - 1);
  return end.toISOString().slice(0, 10);
}
const END_DATE = endDateOf(START_DATE, TRIP_DAYS);

function buildScenarios() {
  const scenarios = {};
  for (let round = 0; round < ROUNDS; round++) {
    scenarios[`ai_round${round + 1}`] = {
      executor: 'per-vu-iterations',
      vus: CONCURRENCY,
      iterations: 1,
      startTime: `${5 + round * ROUND_INTERVAL_SEC}s`,
      maxDuration: `${ROUND_INTERVAL_SEC - 5}s`,
      exec: 'aiCourse',
      env: { ROUND: String(round + 1) },
      tags: { api: 'ai', round: String(round + 1) },
    };
  }
  scenarios.fast = {
    executor: 'constant-arrival-rate',
    rate: FAST_RPS,
    timeUnit: '1s',
    duration: `${5 + ROUNDS * ROUND_INTERVAL_SEC}s`,
    preAllocatedVUs: 5,
    maxVUs: 20,
    exec: 'fastApi',
    tags: { api: 'fast' },
  };
  return scenarios;
}

export const options = {
  scenarios: buildScenarios(),
  // 클라이언트(OkHttp readTimeout)·ALB idle_timeout 과 같은 60초.
  // 임계값은 판정이 아니라 summary-export 에 태그별 하위 지표를 싣기 위한 장치다.
  thresholds: {
    'http_req_duration{api:ai}': ['max>=0'],
    'http_req_duration{api:fast}': ['max>=0'],
    'http_req_failed{api:ai}': ['rate>=0'],
    'http_req_failed{api:fast}': ['rate>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function aiCourse() {
  const round = parseInt(__ENV.ROUND, 10);
  // 라운드 시나리오는 VU N명이 1회씩 돌므로 iterationInTest 가 라운드 안에서 0..N-1 로 겹치지 않는다.
  const vu = exec.scenario.iterationInTest;
  // 라운드마다 입력을 이어서 넘겨 같은 지역이 반복되지 않게 한다.
  const input = INPUTS[((round - 1) * CONCURRENCY + vu) % INPUTS.length];
  const body = JSON.stringify({
    location: input.region,
    startDate: START_DATE,
    endDate: END_DATE,
    keywords: KEYWORD_SETS[input.set],
  });

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
    concurrency: CONCURRENCY, tripDays: TRIP_DAYS, round, vu, location: input.region, keywordSet: input.set,
    status: res.status, durationMs: Math.round(res.timings.duration), errorCode,
  })}`);
}

export function fastApi() {
  const res = http.get(`${BASE_URL}/api/upload-courses/popular`);
  check(res, { 'fast 200': (r) => r.status === 200 });
}
