// test/presigned-url-bottleneck — 열린 모델(도착률 고정) 프로파일.
//
// CallerRunsPolicy → AbortPolicy → CloudFrontSigningGate 검증에는 detail-ramping.js가 아니라
// 이 스크립트를 쓴다. detail-ramping.js는 ramping-vus(닫힌 루프)라 VU가 응답을 받아야 다음
// 요청을 보낸다 — 그래서 게이트로 응답이 빨라질수록(거부가 빨라질수록) 제공 부하가 오히려
// 늘어나, 성공률이 "시스템 품질"이 아니라 "거부 속도의 함수"가 되어 개선할수록 지표가
// 나빠지는 역설이 생긴다(CLOUDFRONT_SIGNING_PERMITS=100000으로 게이트를 비활성화한
// 대조군에서 k6 TPS가 오히려 치솟는 식). 이 스크립트는 초당 요청 수(도착률)를 고정해
// "초당 N건을 제공했을 때 몇 %를 완전하게 처리하는가"를 직접 관측한다.
//
// 체크도 status만 보지 않는다. AbortPolicy 단독 상태에서는 요청 하나의 이미지 중 일부만
// 서명되고 나머지가 조용히 누락된 응답이 status 200으로 나가는 "브라운아웃"이 있었고,
// 기존 detail-ramping.js의 체크(status is 200뿐)는 이걸 못 잡았다(abortpolicy-gate-verification.md).
// mycourse는 응답에 담긴 이미지 총 개수가 EXPECTED_IMAGES(기본 10 — seed-benchmark.sql의
// day_schedule당 place 5 x image 2 기준)와 일치하는지까지 확인해 부분 응답을 별도 집계한다.
//
// [1단계 이후] partial_responses의 성격이 바뀌었다 — 측정 대상이 아니라 회귀 감지기다.
// 서명이 코스당 1회가 되면서 "이미지 일부만 서명된 응답"은 구조적으로 생길 수 없다(서명이
// 성공하면 전부, 실패하면 요청 전체가 503). 그래서 이 값은 이제 0이어야 정상이고, 0이 아니면
// 부하 문제가 아니라 key 규약이 깨졌다는 신호다 — 어떤 이미지의 key가 소유 코스의 서명
// 스코프(private/{courseId}/*) 밖에 있어 CloudFront가 403을 주는 상황. 그 경우
// seed-benchmark.sql 말미의 mismatched_private_keys 쿼리로 원인을 확인한다.
//
// 사용 예 (Run A/B: 게이트 A/B, Run D/D2: 서명 1회 전환 후 — permits만 바꿔 재실행):
//   k6 run -e BASE_URL=http://<app-ip>:8080 -e DOMAIN=mycourse -e MODE=pool -e JWT=... \
//          --summary-export=results/runD-mycourse-arrival-rate.json scripts/k6/detail-arrival-rate.js
//
// knee 탐색(Run F): 서명 부하가 1/10이 되면 기존 상한(400 req/s)에서 포화가 안 나타날 수
// 있다. 그때는 MAX_RATE를 올려 새 포화점을 찾는다. 기본값 400은 Run A/B/C와의 직접 비교를
// 보존하기 위한 것이므로, 비교가 목적인 arm에서는 바꾸지 않는다.
//   k6 run ... -e MAX_RATE=1200 scripts/k6/detail-arrival-rate.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { buildRequest } from './lib/scenarios.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const DOMAIN = __ENV.DOMAIN || 'mycourse';
const MODE = __ENV.MODE || 'pool';
const JWT = __ENV.JWT || '';
const EXPECTED_IMAGES = __ENV.EXPECTED_IMAGES ? parseInt(__ENV.EXPECTED_IMAGES, 10) : 10;
const MAX_RATE = __ENV.MAX_RATE ? parseInt(__ENV.MAX_RATE, 10) : 400;

// status 200이지만 이미지 일부가 누락된 응답 수 — AbortPolicy 단독 arm(게이트 비활성)에서
// k6 기본 성공률 지표만으로는 보이지 않는 브라운아웃을 이 커스텀 메트릭으로 드러냈다.
// 1단계(코스당 서명 1회) 이후에는 0이어야 정상이며, 0이 아니면 key 규약 위반 신호다(위 주석 참고).
export const partialResponses = new Counter('partial_responses');

export const options = {
  scenarios: {
    arrival: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      maxVUs: 1000,
      // 마지막 구간만 MAX_RATE로 뺀다 — 앞 구간(50/100/200)은 Run A/B/C와 동일하게 유지해야
      // 같은 도착률 지점끼리 직접 비교할 수 있다.
      stages: [
        { duration: '60s', target: Math.min(50, MAX_RATE) },
        { duration: '60s', target: Math.min(100, MAX_RATE) },
        { duration: '90s', target: Math.min(200, MAX_RATE) },
        { duration: '90s', target: MAX_RATE },
      ],
    },
  },
};

export default function () {
  const req = buildRequest(DOMAIN, MODE, BASE_URL, JWT);
  const res = http.get(req.url, req.params);

  const isOk = check(res, { 'status is 200 or 503': (r) => r.status === 200 || r.status === 503 });
  if (!isOk) {
    return;
  }
  if (res.status !== 200) {
    return; // 게이트 거부(503) — 정상적인 배압이므로 브라운아웃 집계 대상이 아니다.
  }

  // uploadcourse는 서명(게이트)을 거치지 않는 공개 URL 경로라 이 체크의 대상이 아니다
  // (docs/tasks/connection-pool-bottleneck/TASK-PRESIGN-BOTTLENECK-FIX.md 참고).
  if (DOMAIN !== 'mycourse') {
    return;
  }

  const isComplete = check(res, {
    'response has all expected images': (r) => {
      const body = r.json();
      const imageCount = (body.places || [])
        .reduce((sum, place) => sum + (place.placeImages || []).length, 0);
      return imageCount === EXPECTED_IMAGES;
    },
  });
  if (!isComplete) {
    partialResponses.add(1);
  }
}
