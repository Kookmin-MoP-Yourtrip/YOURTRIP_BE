// test/presigned-url-bottleneck — ramping 프로파일 (포화점 규명용, 이번 실험의 핵심)
//
// VU를 1 → 5 → 10 → 20 → 50 → 100 → 200으로 단계적으로 올리며 각 단계를 60~90초 유지한다.
// 목적은 "TPS가 꺾이는 지점(knee)에서 CPU/HikariCP/Tomcat 중 무엇이 한계에 닿아 있는가"를
// Grafana 대시보드(Presign CPU Bottleneck)와 나란히 관찰하는 것 — 고정 동시성 두 점(50, 200)만으로는
// 포화점 자체를 볼 수 없었다는 게 기존 4회 측정의 한계였다(계획 문서 "부하 프로파일" 참고).
//
// 사용 예:
//   k6 run -e DOMAIN=uploadcourse -e MODE=pool \
//          --summary-export=results/armP-uploadcourse-ramping.json scripts/k6/detail-ramping.js

import http from 'k6/http';
import { check } from 'k6';
import { buildRequest } from './lib/scenarios.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const DOMAIN = __ENV.DOMAIN || 'uploadcourse';
const MODE = __ENV.MODE || 'pool';
const JWT = __ENV.JWT || '';

export const options = {
  scenarios: {
    ramping: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '60s', target: 5 },
        { duration: '60s', target: 10 },
        { duration: '60s', target: 20 },
        { duration: '90s', target: 50 },
        { duration: '90s', target: 100 },
        { duration: '90s', target: 200 },
      ],
      gracefulRampDown: '15s',
    },
  },
};

export default function () {
  const req = buildRequest(DOMAIN, MODE, BASE_URL, JWT);
  const res = http.get(req.url, req.params);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
