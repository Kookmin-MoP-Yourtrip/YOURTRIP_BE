// test/presigned-url-bottleneck — 고정 동시성 프로파일 (과거 4회 벤치마크와 직접 비교용)
//
// TASK-4.md/TASK-CLOUDFRONT.md와 동일한 파라미터: 동시성 50/200, 각 600건.
// 이 스크립트 하나로 4가지 조합(도메인 2 × 시나리오 2)을 환경변수로 선택한다.
//
// 사용 예 (arm P, uploadcourse, hot, 동시성 50):
//   k6 run -e DOMAIN=uploadcourse -e MODE=single -e CONCURRENCY=50 \
//          --summary-export=results/armP-uploadcourse-single-c50.json scripts/k6/detail-fixed.js
//
// mycourse는 JWT가 필요하다:
//   k6 run -e DOMAIN=mycourse -e MODE=pool -e CONCURRENCY=200 -e JWT="$TOKEN" \
//          --summary-export=results/armP-mycourse-pool-c200.json scripts/k6/detail-fixed.js

import http from 'k6/http';
import { check } from 'k6';
import { buildRequest } from './lib/scenarios.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const DOMAIN = __ENV.DOMAIN || 'uploadcourse'; // uploadcourse | mycourse
const MODE = __ENV.MODE || 'single'; // single(hot) | pool(무작위)
const JWT = __ENV.JWT || '';
const CONCURRENCY = parseInt(__ENV.CONCURRENCY || '50', 10);
const ITERATIONS = parseInt(__ENV.ITERATIONS || '600', 10);

export const options = {
  scenarios: {
    fixed: {
      executor: 'shared-iterations',
      vus: CONCURRENCY,
      iterations: ITERATIONS,
      maxDuration: '5m',
    },
  },
  // 동시성 200 조합은 Windows 개발 머신의 Tomcat maxThreads(기본 200) 경계에 걸려
  // 체크 성공률이 84~96%로 떨어지는 게 이미 알려진 환경 특성이다(TASK-CLOUDFRONT.md).
  // 여기서 임계값으로 막지 않고 결과에 성공률을 그대로 남겨 Phase 5에서 해석한다.
};

export default function () {
  const req = buildRequest(DOMAIN, MODE, BASE_URL, JWT);
  const res = http.get(req.url, req.params);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
