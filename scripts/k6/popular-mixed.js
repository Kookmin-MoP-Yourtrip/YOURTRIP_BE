// S5 — 혼합 부하(인기 코스 + 상세조회) 시나리오.
//
// [이 시나리오가 측정하는 것] 앞선 시나리오들은 /popular만 단독으로 때린다. 그런데 이
// 리팩터링이 실제 운영에서 갖는 의미는 "인기 코스가 빨라진다"보다 **"인기 코스가 커넥션을
// 쓰지 않게 되어, 같은 풀(HikariCP 10개)을 공유하는 다른 API가 그만큼 여유를 얻는다"**에
// 가깝다. 단일 엔드포인트 시나리오로는 이 효과가 원리적으로 드러나지 않는다.
//
// [실험 설계가 깔끔한 이유] before/after 두 arm에서 상세조회 코드는 **완전히 동일하다**
// (상세조회의 트랜잭션 분리는 PR #70에서 이미 끝났고 이번 커밋은 건드리지 않았다).
// 바뀌는 건 오직 "/popular이 커넥션을 대여하는가"뿐이다. 따라서 상세조회 쪽 지표
// (pending, acquire_seconds, p95)가 개선된다면 그건 전부 /popular이 커넥션을 반납한 덕이다.
//
// [상세조회를 pool 모드로 쓰는 이유] uploadcourse 상세조회는 캐시 히트 시 커넥션을 안 쓴다
// (PR #70). 캐시 미스를 충분히 만들어 실제로 DB를 타게 해야 풀 경쟁이 생기므로,
// 3,000개 코스를 무작위로 도는 pool 모드를 쓰고 측정 직전 FLUSHALL로 캐시를 비운다.
//
// [부하 배분] 두 시나리오를 동시에 실행한다. /popular은 고정 도착률로 배경 부하를 만들고,
// 상세조회는 VU를 올려가며 포화점을 찾는다 — "배경 부하가 커넥션을 먹느냐 마느냐"가
// 상세조회의 포화 시작 VU를 얼마나 밀어주는지가 이 측정의 답이다.
//
// 사용 예:
//   k6 run -e BASE_URL=http://<app-private-ip>:8080 -e POPULAR_RATE=300 -e MAX_VUS=200 \
//          --summary-export=results/s5-before.json scripts/k6/popular-mixed.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { buildPopularRequest, buildRequest } from './lib/scenarios.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const THEME_MODE = __ENV.THEME_MODE || 'mixed';
// /popular 배경 부하의 도착률(req/s). 커넥션 풀을 두고 경쟁시킬 만큼은 되어야 하지만,
// 이 자체가 앱을 포화시키면 상세조회의 포화점을 못 보므로 적당히 잡는다.
const POPULAR_RATE = __ENV.POPULAR_RATE ? parseInt(__ENV.POPULAR_RATE, 10) : 300;
const MAX_VUS = __ENV.MAX_VUS ? parseInt(__ENV.MAX_VUS, 10) : 200;
const EXPECTED_IMAGES = __ENV.EXPECTED_IMAGES ? parseInt(__ENV.EXPECTED_IMAGES, 10) : 10;

// 도메인별로 지연을 분리해서 봐야 한다 — 하나로 합치면 빠른 /popular이 평균을 끌어내려
// 상세조회의 악화가 가려진다.
export const popularLatency = new Trend('popular_latency', true);
export const detailLatency = new Trend('detail_latency', true);
export const detailPartial = new Counter('detail_partial_responses');

const detailStages = [
  { duration: '60s', target: 20 },
  { duration: '90s', target: 50 },
  { duration: '90s', target: 100 },
  { duration: '90s', target: MAX_VUS },
];

export const options = {
  scenarios: {
    // 배경 부하 — 열린 루프로 도착률을 고정해, 상세조회가 느려져도 /popular의 제공 부하가
    // 덩달아 줄지 않게 한다(닫힌 루프로 두면 그 결합 때문에 인과를 못 가른다).
    popular: {
      executor: 'constant-arrival-rate',
      rate: POPULAR_RATE,
      timeUnit: '1s',
      duration: '330s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      exec: 'popularScenario',
    },
    // 측정 대상 — 포화점을 찾아야 하므로 닫힌 루프로 VU를 올린다.
    detail: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: detailStages,
      gracefulRampDown: '15s',
      exec: 'detailScenario',
    },
  },
};

export function popularScenario() {
  const { url, params } = buildPopularRequest(BASE_URL, THEME_MODE);
  const res = http.get(url, params);
  check(res, { 'popular status is 200': (r) => r.status === 200 });
  popularLatency.add(res.timings.duration);
}

export function detailScenario() {
  const { url, params } = buildRequest('uploadcourse', 'pool', BASE_URL, '');
  const res = http.get(url, params);
  check(res, { 'detail status is 200': (r) => r.status === 200 });
  detailLatency.add(res.timings.duration);

  if (res.status !== 200) {
    return;
  }

  // detail-ramping.js와 동일한 이미지 완전성 체크 — 두 스크립트의 판정이 갈라지면
  // 기존 상세조회 실측과 나란히 놓을 수 없다.
  const isComplete = check(res, {
    'detail has all expected images': (r) => {
      const body = r.json();
      const imageCount = (body.daySchedules || [])
        .reduce((sum, day) => sum + (day.places || [])
          .reduce((s, place) => s + (place.placeImages || []).length, 0), 0);
      return imageCount === EXPECTED_IMAGES;
    },
  });

  if (!isComplete) {
    detailPartial.add(1);
  }
}
