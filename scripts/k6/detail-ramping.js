// test/presigned-url-bottleneck — 닫힌 루프(ramping-vus) 프로파일. "동시 사용자 몇 명에서
// 포화가 시작되는가"를 재는 보조 도구다.
//
// [주력은 여전히 열린 루프다] Run A 이후 모든 측정(Run A~F)은 detail-arrival-rate.js(열린 루프)로
// 했고, 그 판단 자체는 유효하다 — 닫힌 루프는 VU가 응답을 받아야 다음 요청을 보내므로, 서버가
// 요청을 "빨리 거부"하거나 "이미지를 빼먹고 빨리 응답"할수록 제공 부하가 오히려 늘어난다.
// 그래서 성공률이 시스템 품질이 아니라 거부 속도의 함수가 되고, 개선할수록 지표가 나빠지는
// 역설이 생긴다(abortpolicy-gate-verification.md "핵심 설계 판단").
//
// [그럼에도 이 스크립트를 다시 쓰는 이유] 열린 루프는 도착률(req/s) 기준이라 "동시 사용자 N명"
// 이라는 형태의 지표를 원리적으로 줄 수 없다. Run A 이전 stage0 측정들이 쓰던 "포화 시작 VU"
// 컬럼이 그 이후 사라져, custom policy 도입 전/후를 VU로 비교한 기록이 하나도 없다.
// Run H(도입 전)/Run I(도입 후)가 그 빈칸을 메운다.
//
// [어디까지 신뢰할 수 있는가]
//   - 도입 후(fail-closed, 서명 실패는 503) — 위 왜곡이 작동하지 않으므로 전 구간 신뢰 가능.
//   - 도입 전(AbortPolicy, fail-open) — "포화가 시작되는 지점"(대기줄·브라운아웃이 처음
//     발생하는 VU 단계)까지만 신뢰한다. 그 이후 구간의 TPS·p95 절대값은 위 역설에 오염되므로
//     도입 후와 숫자로 비교하지 않는다.
//
// 체크는 status만 보지 않는다 — AbortPolicy는 fail-open이라 이미지가 몇 장 빠지든 무조건 200을
// 반환한다. 예전 이 스크립트의 체크가 `status is 200` 하나뿐이라 과거 라운드의 "성공률 100%"가
// 사실은 브라운아웃을 못 본 것이었다는 사후 정정이 abortpolicy-gate-verification.md에 남아 있다.
// 그 사각지대를 없애려고 detail-arrival-rate.js와 동일한 이미지 완전성 체크를 여기에도 둔다
// (두 스크립트의 완전성 판정이 갈라지면 열린/닫힌 루프 결과를 나란히 놓을 수 없다).
//
// 사용 예 (Run H/I: 도입 전/후 포화 VU 비교):
//   k6 run -e BASE_URL=http://<app-private-ip>:8080 -e DOMAIN=mycourse -e MODE=pool -e JWT=... \
//          -e MAX_VUS=400 \
//          --summary-export=results/runH-mycourse-ramping.json scripts/k6/detail-ramping.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { buildRequest } from './lib/scenarios.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const DOMAIN = __ENV.DOMAIN || 'uploadcourse';
const MODE = __ENV.MODE || 'pool';
const JWT = __ENV.JWT || '';
const EXPECTED_IMAGES = __ENV.EXPECTED_IMAGES ? parseInt(__ENV.EXPECTED_IMAGES, 10) : 10;
const MAX_VUS = __ENV.MAX_VUS ? parseInt(__ENV.MAX_VUS, 10) : 200;

// status 200이지만 이미지 일부가 누락된 응답 수 — 도입 전(AbortPolicy) arm에서 k6 기본
// 성공률 지표로는 보이지 않는 브라운아웃을 드러낸다. 도입 후(코스당 서명 1회)에는 서명이
// 성공하면 전부, 실패하면 요청 전체가 503이라 구조적으로 0이어야 정상이다.
export const partialResponses = new Counter('partial_responses');

// 기존 6단계(VU 1→5→10→20→50→100→200, 450초)는 그대로 둔다 — stage0의 닫힌 루프 측정
// (포화 시작 VU 10~20)과 같은 VU 지점끼리 직접 비교하려면 앞 구간을 건드리면 안 된다.
const stages = [
  { duration: '60s', target: 5 },
  { duration: '60s', target: 10 },
  { duration: '60s', target: 20 },
  { duration: '90s', target: 50 },
  { duration: '90s', target: 100 },
  { duration: '90s', target: 200 },
];

// 200을 넘길 때만 마지막에 한 단계를 덧붙인다. Run F에서 tomcat_threads_busy_threads가
// maxThreads(200)에 도달한 것을 감안하면 도입 후 코드는 VU 200에서도 안 꺾일 수 있고,
// 그러면 "포화 VU를 못 찾았다"로 끝나기 때문이다.
if (MAX_VUS > 200) {
  stages.push({ duration: '90s', target: MAX_VUS });
}

export const options = {
  scenarios: {
    ramping: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages,
      gracefulRampDown: '15s',
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

  // uploadcourse는 서명을 거치지 않는 공개 URL 경로라 이 체크의 대상이 아니다.
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
