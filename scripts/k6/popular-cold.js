// S3 — 콜드 캐시(랭킹 스탬피드) 시나리오.
//
// [이 시나리오가 핵심인 이유] Phase 0 로컬 측정에서 확인된 사실: 워밍 캐시 경로는 커넥션을
// 빌렸다 즉시 반납하므로 점유 시간이 마이크로초 수준이고, 그래서 풀(10개)이 애초에 병목이
// 아니다 — before의 커넥션 대여를 100% 없애도 처리량은 +4.9%밖에 안 올랐다.
// 반면 랭킹 캐시가 미스나면 락을 못 잡은 요청은 최대 1초(RANKING_LOCK_RETRY_COUNT 10 ×
// RANKING_LOCK_RETRY_INTERVAL_MS 100ms) Thread.sleep하며 대기한다.
//   - before: 이 sleep 구간 내내 트랜잭션이 살아 있어 커넥션을 쥔 채로 잔다.
//   - after:  sleep이 트랜잭션 밖이라 커넥션을 전혀 쥐지 않는다.
// 즉 "점유 시간이 긴 구간"은 여기뿐이고, 이 리팩터링의 실질 가치도 여기에 있다.
//
// [콜드 구간을 반복 발생시킨다] 랭킹 캐시 TTL은 30분(RedisConfig.POPULAR_COURSES_TTL)이라
// 한 번 채워지면 그걸로 끝이고, 스탬피드는 수 초 만에 사라져 측정 창이 너무 좁다.
// 그래서 이 스크립트는 부하를 계속 거는 상태로 두고, **별도 셸에서 주기적으로 FLUSHALL**을
// 날려 스탬피드를 반복 유발하는 것을 전제로 한다. 실행 절차는 문서 참고:
//   docs/tasks/popular-tx-separation/README.md
//
// [주의] FLUSHALL 후 앱을 재기동하면 안 된다 — PopularCourseCacheWarmer가
// ApplicationReadyEvent에서 8키를 전부 데워버려 콜드 상태가 사라진다.
//
// 사용 예:
//   k6 run -e BASE_URL=http://<app-private-ip>:8080 -e VUS=100 -e DURATION=90s \
//          --summary-export=results/s3-before.json scripts/k6/popular-cold.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { buildPopularRequest } from './lib/scenarios.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const THEME_MODE = __ENV.THEME_MODE || 'all';
const VUS = __ENV.VUS ? parseInt(__ENV.VUS, 10) : 100;
const DURATION = __ENV.DURATION || '90s';

// 스탬피드 구간에서만 튀는 느린 응답을 따로 센다. 락 대기 예산이 1초이므로, 락을 못 잡고
// 재시도 루프를 끝까지 돈 요청은 1초 이상이 된다. before/after의 차이는 "이 요청들이
// 커넥션을 쥔 채 기다렸는가"인데, 그 결과가 hikaricp_connections_pending으로 드러난다.
export const lockWaitResponses = new Counter('lock_wait_responses');
// 전체 분포와 별개로, 스탬피드 구간의 꼬리 지연만 따로 본다.
export const coldLatency = new Trend('cold_latency', true);

export const options = {
  scenarios: {
    cold: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
};

export default function () {
  const { url, params } = buildPopularRequest(BASE_URL, THEME_MODE);
  const res = http.get(url, params);

  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  coldLatency.add(res.timings.duration);

  // 락 재시도 예산(1초)을 넘겼다는 건 재시도 루프를 끝까지 돌았다는 뜻이다.
  if (res.timings.duration >= 1000) {
    lockWaitResponses.add(1);
  }
}
