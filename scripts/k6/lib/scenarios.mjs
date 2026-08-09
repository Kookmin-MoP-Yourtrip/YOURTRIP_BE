// test/presigned-url-bottleneck 공용 로직 — single(hot 1건 반복)/pool(무작위) 두 시나리오와
// mycourse/uploadcourse 두 도메인의 요청 조립을 한곳에 모은다.
//
// scripts/sql/seed-benchmark.sql이 만든 ID 규격을 그대로 가정한다:
//   - mycourse:      course_id 1..3000 (hot=1, pool=2..3000), day_schedule_id == course_id
//   - uploadcourse:  upload_course_id 1..3000 (hot=1, pool=2..3000)

export function pickCourseId(mode) {
  if (mode === 'single') {
    return 1; // hot — 캐시 히트율이 거의 100%에 수렴하는 핫키 반복 조회
  }
  if (mode === 'pool') {
    // 2..3000 균등 무작위 — TASK-CLOUDFRONT.md/TASK-4.md와 동일한 "롱테일" 트래픽 근사
    return 2 + Math.floor(Math.random() * 2999);
  }
  throw new Error(`unknown mode: ${mode} (expected "single" or "pool")`);
}

export function buildRequest(domain, mode, baseUrl, jwt) {
  const courseId = pickCourseId(mode);

  if (domain === 'uploadcourse') {
    return {
      url: `${baseUrl}/api/upload-courses/${courseId}`,
      params: {},
    };
  }

  if (domain === 'mycourse') {
    if (!jwt) {
      throw new Error('mycourse 도메인은 JWT 환경변수(__ENV.JWT)가 필요합니다');
    }
    // 시드 스크립트가 day_schedule_id를 course_id와 동일하게 채번했다(코스당 1일차).
    return {
      url: `${baseUrl}/api/my-courses/${courseId}/days/${courseId}/places`,
      params: { headers: { Authorization: `Bearer ${jwt}` } },
    };
  }

  throw new Error(`unknown domain: ${domain} (expected "mycourse" or "uploadcourse")`);
}
