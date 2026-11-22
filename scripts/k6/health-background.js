// 헬스체크 결함 주입 회차의 배경 트래픽 — "죽은 서버가 트래픽을 받은 시간"을 사용자 관점으로 잰다 (#216).
//
// 설계: docs/tasks/health-check-progress/fault-scenarios.md 6절
//
// 부하가 목적이 아니다. 초당 20건의 가벼운 요청을 회차 내내 흘려, 결함이 주입된 뒤 언제부터 요청이 실패하고
// 교체나 복구로 언제 다시 성공하는지를 본다. 미탐(C1)이면 이 실패 구간이 10분 넘게 이어지고, 정탐(C2)이면
// 감지 + 교체 시간만큼에서 끝난다 — 두 구성의 차이를 실패한 요청 수로 보이는 장치다.
//
// 인증 없는 공개 상세 조회(GET /api/upload-courses/{id})만 보낸다. 토큰이 필요 없어 개발 PC에서 바로 돌고,
// 1시간짜리 토큰이 회차 도중 만료돼 401이 섞이는 일도 없다.
//
// 요청별 결과는 --out csv 로 남긴다(초당 20건이라 30분이어도 3만 줄 남짓). 집계는 aggregate-health.py 가 한다.
//
// 사용:
//   k6 run -e BASE_URL=https://yourtrip.cloud -e DURATION=30m --out csv=bg.csv scripts/k6/health-background.js
import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = parseInt(__ENV.RATE || '20', 10);
const DURATION = __ENV.DURATION || '30m';
// fast-api-ramp.js 와 같은 2초. 2초 안에 끝나지 않은 요청을 실패로 센다.
const TIMEOUT_SEC = parseInt(__ENV.TIMEOUT_SEC || '2', 10);
const DETAIL_ID_MIN = parseInt(__ENV.DETAIL_ID_MIN || '1', 10);
const DETAIL_ID_MAX = parseInt(__ENV.DETAIL_ID_MAX || '3000', 10);

export const options = {
  scenarios: {
    background: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // 서버가 응답하지 않으면 요청마다 2초씩 VU 를 쥔다. 초당 20건 × 2초 = 40이 정상 상한이고 여유를 둔다.
      preAllocatedVUs: RATE * TIMEOUT_SEC * 2,
      maxVUs: RATE * TIMEOUT_SEC * 4,
    },
  },
  // 실패가 측정 대상이라 임계값으로 회차를 실패시키지 않는다.
  thresholds: {},
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const id = DETAIL_ID_MIN + Math.floor(Math.random() * (DETAIL_ID_MAX - DETAIL_ID_MIN + 1));
  http.get(`${BASE_URL}/api/upload-courses/${id}`, {
    timeout: `${TIMEOUT_SEC}s`,
    tags: { name: 'detail' },
  });
}
