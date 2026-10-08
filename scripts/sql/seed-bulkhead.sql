-- 장애 격리 주실험(#197) 운영 시드 — 부하용 사용자 1명 + 업로드 코스 3,000건
--
-- 설계: docs/tasks/ai-bulkhead-loadtest/README.md 10-2절
--   - 배경 트래픽의 코스 상세조회(GET /api/upload-courses/{id})가 읽을 공개 코스가 운영 DB에 없어 넣는다
--   - AI 코스 생성 요청의 토큰 주인(부하용 사용자)도 함께 넣는다 — 인증 필터가 토큰의 사용자를 DB 에서 조회한다
--
-- seed-benchmark.sql 과 다른 점:
--   - PK 를 하드코딩하지 않고 시퀀스에 맡긴다. 기존 데이터와 충돌하지 않게 하기 위해서다
--   - created_at/updated_at 을 now() 로 넣는다. JPA Auditing 이 채우는 값이라 SQL 로 넣으면 NULL 이 되고,
--     생성일 정렬(목록 조회)이 의미를 잃는다
--   - mycourse 비공개 세트는 넣지 않는다(이 실험은 업로드 코스 상세만 읽는다)
--   - 시드 데이터는 부하용 사용자 소유이고 제목이 'bulkhead-seed ' 로 시작한다 — 나중에 정확히 골라낼 수 있다
--
-- 이미지 키는 'uploads/' 로 시작한다(MediaKeys 의 공개 접두사). 공개 이미지는 서명 없이 URL 만 만들므로,
-- 서명 CPU 비용이 배경 부하에 섞이지 않는다.
--
-- 전제: 앱이 한 번 기동해 스키마가 만들어진 뒤에 실행한다(운영 DDL 모드 update).
-- 실행: 앱 인스턴스에서 scripts/loadtest/bulkhead-sql.sh 로 (.env 의 DB 접속 정보를 쓴다)

\set ON_ERROR_STOP on

BEGIN;

-- 두 번 넣지 않는다 — 이미 있으면 트랜잭션 전체를 실패시킨다.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM users WHERE email = 'loadtest-bulkhead@yourtrip.local') THEN
    RAISE EXCEPTION '부하용 사용자가 이미 있다 — 시드가 이미 들어갔다';
  END IF;
END $$;

-- ============================================================
-- 0. 부하용 사용자 — 로그인할 수 없는 계정이다(password 는 해시가 아니다)
-- ============================================================
INSERT INTO users (email, password, nickname, role, deleted, email_verified, created_at, updated_at)
VALUES ('loadtest-bulkhead@yourtrip.local', 'not-a-real-hash', 'bulkhead-loadtest', 'USER', false, true, now(), now());

-- ============================================================
-- 1. 업로드 코스의 hidden copy(my_course) 3,000건 — 코스당 1일차 × 장소 5 × 이미지 2
-- ============================================================
INSERT INTO my_course (user_id, title, location, start_date, end_date, type, uploaded, deleted, created_at, updated_at)
SELECT u.user_id, 'bulkhead-seed ' || n, 'busan', DATE '2026-01-01', DATE '2026-01-03', 'UPLOADED', true, false, now(), now()
FROM users u, generate_series(1, 3000) AS n
WHERE u.email = 'loadtest-bulkhead@yourtrip.local'
ORDER BY n;

INSERT INTO day_schedule (course_id, day, created_at, updated_at)
SELECT c.course_id, 1, now(), now()
FROM my_course c JOIN users u ON u.user_id = c.user_id
WHERE u.email = 'loadtest-bulkhead@yourtrip.local' AND c.title LIKE 'bulkhead-seed %'
ORDER BY c.course_id;

-- 장소 순서 컬럼이 없고 표시 순서가 place_id 순이라(DaySchedule.places @OrderBy("id")) 코스·순번 순으로 넣는다.
INSERT INTO place (day_schedule_id, place_name, latitude, longitude, place_url, place_location, created_at, updated_at)
SELECT d.day_schedule_id, 'bulkhead place ' || d.day_schedule_id || '-' || k,
       35.1 + (d.day_schedule_id % 1000) * 0.0001 + k * 0.001, 129.0 + (d.day_schedule_id % 1000) * 0.0001,
       'http://place.local/bulkhead/' || d.day_schedule_id || '-' || k, 'busan', now(), now()
FROM day_schedule d
JOIN my_course c ON c.course_id = d.course_id
JOIN users u ON u.user_id = c.user_id
CROSS JOIN generate_series(1, 5) AS k
WHERE u.email = 'loadtest-bulkhead@yourtrip.local' AND c.title LIKE 'bulkhead-seed %'
ORDER BY d.day_schedule_id, k;

INSERT INTO place_image (place_id, place_images3key, created_at, updated_at)
SELECT p.place_id, 'uploads/bulkhead-seed/' || p.place_id || '-' || k || '.jpg', now(), now()
FROM place p
JOIN day_schedule d ON d.day_schedule_id = p.day_schedule_id
JOIN my_course c ON c.course_id = d.course_id
JOIN users u ON u.user_id = c.user_id
CROSS JOIN generate_series(1, 2) AS k
WHERE u.email = 'loadtest-bulkhead@yourtrip.local' AND c.title LIKE 'bulkhead-seed %'
ORDER BY p.place_id, k;

-- ============================================================
-- 2. 업로드 코스 3,000건 — hidden copy 와 1:1
-- ============================================================
INSERT INTO upload_course (course_id, user_id, title, introduction, location, thumbnail_images3key,
                           view_count, heart_count, fork_count, deleted, created_at, updated_at)
SELECT c.course_id, c.user_id, c.title, 'bulkhead load test seed course', 'busan',
       'uploads/bulkhead-seed/thumb-' || c.course_id || '.jpg', 0, 0, 0, false, now(), now()
FROM my_course c JOIN users u ON u.user_id = c.user_id
WHERE u.email = 'loadtest-bulkhead@yourtrip.local' AND c.title LIKE 'bulkhead-seed %'
ORDER BY c.course_id;

COMMIT;

-- ============================================================
-- 3. 검증 — k6 에 넘길 값(사용자 id, 상세 코스 ID 범위)도 여기서 읽는다
-- ============================================================
SELECT u.user_id AS loadtest_user_id, u.email AS loadtest_user_email
FROM users u WHERE u.email = 'loadtest-bulkhead@yourtrip.local';

-- 기대: courses 3000, 범위가 연속(span = 3000). 연속이 아니면 k6 의 DETAIL_ID 범위 안에 없는 ID 가 섞여 404 가 난다.
SELECT count(*) AS upload_courses,
       min(uc.upload_course_id) AS detail_id_min,
       max(uc.upload_course_id) AS detail_id_max,
       max(uc.upload_course_id) - min(uc.upload_course_id) + 1 AS span
FROM upload_course uc JOIN users u ON u.user_id = uc.user_id
WHERE u.email = 'loadtest-bulkhead@yourtrip.local';

-- 기대: day 3000, place 15000, image 30000
SELECT (SELECT count(*) FROM day_schedule d JOIN my_course c ON c.course_id = d.course_id
          JOIN users u ON u.user_id = c.user_id WHERE u.email = 'loadtest-bulkhead@yourtrip.local') AS day_schedules,
       (SELECT count(*) FROM place p JOIN day_schedule d ON d.day_schedule_id = p.day_schedule_id
          JOIN my_course c ON c.course_id = d.course_id JOIN users u ON u.user_id = c.user_id
          WHERE u.email = 'loadtest-bulkhead@yourtrip.local') AS places,
       (SELECT count(*) FROM place_image pi JOIN place p ON p.place_id = pi.place_id
          JOIN day_schedule d ON d.day_schedule_id = p.day_schedule_id JOIN my_course c ON c.course_id = d.course_id
          JOIN users u ON u.user_id = c.user_id WHERE u.email = 'loadtest-bulkhead@yourtrip.local') AS images;
