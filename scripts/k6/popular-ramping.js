// 인기 코스 조회(GET /api/upload-courses/popular) 닫힌 루프(ramping-vus) 프로파일.
// getPopularCourses의 트랜잭션 분리(커밋 6637534) 효과를 before/after로 대조하기 위한 스크립트다.
//
// [왜 닫힌 루프를 주력으로 쓰는가] 상세조회 측정(Run A~G)에서는 열린 루프가 주력이었다.
// 거기서는 fail-open(AbortPolicy)이 "빨리 거부할수록 제공 부하가 늘어나는" 역설을 만들어
// 닫힌 루프를 못 썼기 때문이다. 이 경로에는 그 게이트가 아예 없고(응답은 200 아니면 예외),
// 검증하려는 가설이 "커넥션 풀(10개)에 처리량이 묶이는가"라 동시 사용자 축이 오히려 직접적이다.
// 무엇보다 이번 비교의 선례인 stage0/production/ec2-rds.md의 uploadcourse 표
// (pending 187→0, TPS +45.4%)가 detail-ramping.js(닫힌 루프)로 나온 수치라,
// 같은 executor·같은 VU 단계를 써야 나란히 놓을 수 있다.
//
// [측정하려는 가설]
//   H1 — before는 캐시가 전부 히트해도 DB 커넥션을 획득한다. Hibernate는 지연 획득을 하지만
//        hibernate.connection.provider_disables_autocommit이 설정돼 있지 않으면(현재 없음)
//        트랜잭션 begin 시점에 물리 커넥션을 잡아 autocommit을 끄고, readOnly=true면
//        Connection.setReadOnly(true) 호출도 커넥션을 요구한다.
//   H2 — 그 결과 before의 처리량이 풀 크기 10에 묶인다(Tomcat 200스레드 : 커넥션 10개 = 20:1).
// 결정적 증거 형태: hibernate_statements_total 증분이 0인데 hikaricp_connections_active가 10
// — "일은 하나도 안 하면서 커넥션만 점유". PRESIGN-BOTTLENECK.md의 직접 증거 3과 같은 구조다.
//
// [사전 준비] seed-benchmark.sql + seed-popular.sql 시딩 후, 캐시를 워밍한 상태에서 실행한다
// (S1/S2). 워밍은 8키(ALL + mood 7종)를 curl로 한 번씩 긁으면 된다. 콜드 캐시 측정(S3)은
// FLUSHALL 직후 실행하되, 앱을 재기동하면 PopularCourseCacheWarmer가 전부 데워버리므로
// 재기동해서는 안 된다.
//
// 사용 예:
//   k6 run -e BASE_URL=http://<app-private-ip>:8080 -e THEME_MODE=all \
//          --summary-export=results/s1-before.json scripts/k6/popular-ramping.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { buildPopularRequest } from './lib/scenarios.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// all = 무테마만(랭킹 캐시 키 1개), mixed = ALL + mood 7종 균등(키 8개)
const THEME_MODE = __ENV.THEME_MODE || 'all';
// 시드 기준 각 랭킹은 정확히 5건이다(POPULAR_COURSE_LIMIT = 5).
const EXPECTED_COURSES = __ENV.EXPECTED_COURSES ? parseInt(__ENV.EXPECTED_COURSES, 10) : 5;
const MAX_VUS = __ENV.MAX_VUS ? parseInt(__ENV.MAX_VUS, 10) : 200;

// 상세조회의 partial_responses에 대응하는 회귀 감지기. 이 경로에는 부분 응답을 만드는
// fail-open 지점이 없으므로 0이 정상이고, 0이 아니면 측정값이 아니라 전제가 깨진 것이다
// (시드 누락으로 랭킹이 5건 미만이거나, 아이템 캐시 정합성 문제).
export const incompleteRankings = new Counter('popular_incomplete');

// detail-ramping.js와 동일한 6단계(VU 5→10→20→50→100→200, 450초). stage0의 닫힌 루프
// 측정과 같은 VU 지점에서 "포화 시작 VU"를 비교하려면 앞 구간을 건드리면 안 된다.
const stages = [
  { duration: '60s', target: 5 },
  { duration: '60s', target: 10 },
  { duration: '60s', target: 20 },
  { duration: '90s', target: 50 },
  { duration: '90s', target: 100 },
  { duration: '90s', target: 200 },
];

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
  const { url, params } = buildPopularRequest(BASE_URL, THEME_MODE);
  const res = http.get(url, params);

  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  if (res.status !== 200) {
    return;
  }

  const isComplete = check(res, {
    'ranking has expected course count': (r) => {
      const body = r.json();
      return (body.uploadCourses || []).length === EXPECTED_COURSES;
    },
  });

  if (!isComplete) {
    incompleteRankings.add(1);
  }
}
