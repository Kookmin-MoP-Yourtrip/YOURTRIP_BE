-- test/presigned-url-bottleneck 벤치마크 시드
--
-- TASK-CLOUDFRONT.md의 병렬화/ECDSA 재측정 규격을 그대로 재현한다 — 과거 4회 측정과
-- 직접 비교 가능해야 하기 때문이다: mycourse/uploadcourse 각각 3,000코스(hot 1건 +
-- pool 2,999건), 코스당 1일차 × 장소 5 × 이미지 2장 = 코스당 이미지 10장.
--
-- DB_DDL_AUTO=create라 앱을 재기동할 때마다 스키마가 초기화되므로, arm을 바꿀 때마다
-- (Phase 4-3 초기화 절차) 이 스크립트를 다시 실행해야 한다.
--
-- 실행 예:
--   "C:\Program Files\PostgreSQL\17\bin\psql.exe" -h localhost -p 5434 -U postgres -d yourtrip -f scripts/sql/seed-benchmark.sql
--
-- 벤치마크 사용자(user_id=1)로 mycourse 3,000건 전량을 소유하게 해서, k6 스크립트가
-- 이 사용자 하나의 JWT만으로 모든 mycourse 요청을 보낼 수 있게 한다.

BEGIN;

-- ============================================================
-- 0. 벤치마크 전용 사용자
-- ============================================================
INSERT INTO users (user_id, email, password, nickname, role, deleted, email_verified)
VALUES (1, 'benchmark@yourtrip.local', 'not-a-real-hash', 'benchmark-user', 'USER', false, true);

-- ============================================================
-- 1. mycourse 비공개 벤치마크 세트 (course_id 1..3000)
--    hot = course_id 1, pool = course_id 2..3000
-- ============================================================
INSERT INTO my_course (course_id, user_id, title, location, start_date, end_date, type, uploaded, deleted)
SELECT n, 1, 'mycourse benchmark ' || n, 'seoul', DATE '2026-01-01', DATE '2026-01-07', 'DIRECT', false, false
FROM generate_series(1, 3000) AS n;

INSERT INTO day_schedule (day_schedule_id, course_id, day)
SELECT n, n, 1
FROM generate_series(1, 3000) AS n;

-- place_id 1..15000, day_schedule당 5개
INSERT INTO place (place_id, day_schedule_id, place_name, latitude, longitude, place_url, place_location)
SELECT p, ((p - 1) / 5) + 1, 'mycourse place ' || p,
       37.5 + (p % 1000) * 0.0001, 127.0 + (p % 1000) * 0.0001,
       'http://place.local/mycourse/' || p, 'seoul'
FROM generate_series(1, 15000) AS p;

-- place_image_id 1..30000, place당 2개, "private/" prefix (S3Service.PRIVATE_KEY_PREFIX와 동일)
INSERT INTO place_image (place_image_id, place_id, place_images3key)
SELECT i, ((i - 1) / 2) + 1, 'private/mycourse-bench/' || i || '.jpg'
FROM generate_series(1, 30000) AS i;

-- ============================================================
-- 2. uploadcourse 공개 벤치마크 세트 — hidden copy(my_course, course_id 3001..6000)
--    + upload_course(upload_course_id 1..3000). hot = upload_course_id 1, pool = 2..3000
-- ============================================================
INSERT INTO my_course (course_id, user_id, title, location, start_date, end_date, type, uploaded, deleted)
SELECT 3000 + n, 1, 'uploadcourse benchmark ' || n, 'busan', DATE '2026-01-01', DATE '2026-01-07', 'UPLOADED', true, false
FROM generate_series(1, 3000) AS n;

INSERT INTO day_schedule (day_schedule_id, course_id, day)
SELECT 3000 + n, 3000 + n, 1
FROM generate_series(1, 3000) AS n;

-- place_id 15001..30000, day_schedule당 5개
INSERT INTO place (place_id, day_schedule_id, place_name, latitude, longitude, place_url, place_location)
SELECT 15000 + p, 3000 + ((p - 1) / 5) + 1, 'uploadcourse place ' || (15000 + p),
       35.1 + (p % 1000) * 0.0001, 129.0 + (p % 1000) * 0.0001,
       'http://place.local/uploadcourse/' || (15000 + p), 'busan'
FROM generate_series(1, 15000) AS p;

-- place_image_id 30001..60000, place당 2개, "uploads/" prefix (S3Service.PUBLIC_KEY_PREFIX와 동일)
INSERT INTO place_image (place_image_id, place_id, place_images3key)
SELECT 30000 + i, 15000 + ((i - 1) / 2) + 1, 'uploads/uploadcourse-bench/' || (30000 + i) || '.jpg'
FROM generate_series(1, 30000) AS i;

-- upload_course_id 1..3000, course_id = 3001..6000 (1:1)
INSERT INTO upload_course (upload_course_id, course_id, user_id, title, introduction, location,
                            thumbnail_images3key, view_count, heart_count, fork_count, deleted)
SELECT n, 3000 + n, 1, 'uploadcourse benchmark ' || n, 'benchmark seed course', 'busan',
       'uploads/uploadcourse-bench/thumb-' || n || '.jpg', 0, 0, 0, false
FROM generate_series(1, 3000) AS n;

-- ============================================================
-- 3. identity 시퀀스 동기화 — 이 스크립트 이후 앱이 INSERT를 시도할 일은 없지만
--    (읽기 전용 부하 테스트) 혹시 모를 PK 충돌을 예방한다.
-- ============================================================
SELECT setval(pg_get_serial_sequence('users', 'user_id'), (SELECT MAX(user_id) FROM users));
SELECT setval(pg_get_serial_sequence('my_course', 'course_id'), (SELECT MAX(course_id) FROM my_course));
SELECT setval(pg_get_serial_sequence('day_schedule', 'day_schedule_id'), (SELECT MAX(day_schedule_id) FROM day_schedule));
SELECT setval(pg_get_serial_sequence('place', 'place_id'), (SELECT MAX(place_id) FROM place));
SELECT setval(pg_get_serial_sequence('place_image', 'place_image_id'), (SELECT MAX(place_image_id) FROM place_image));
SELECT setval(pg_get_serial_sequence('upload_course', 'upload_course_id'), (SELECT MAX(upload_course_id) FROM upload_course));

COMMIT;

-- ============================================================
-- 4. 검증 (검증 방법 절 "시드 검증" 참고 — 코스당 이미지 10장 기준 기대치)
-- ============================================================
SELECT
    (SELECT count(*) FROM my_course WHERE course_id <= 3000) AS mycourse_courses,       -- 기대: 3000
    (SELECT count(*) FROM my_course WHERE course_id > 3000) AS uploadcourse_hidden_copies, -- 기대: 3000
    (SELECT count(*) FROM upload_course) AS upload_courses,                              -- 기대: 3000
    (SELECT count(*) FROM place_image WHERE place_image_id <= 30000) AS mycourse_images, -- 기대: 30000
    (SELECT count(*) FROM place_image WHERE place_image_id > 30000) AS uploadcourse_images; -- 기대: 30000
