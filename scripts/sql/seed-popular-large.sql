-- ============================================================
-- 인기 코스 규모 곡선(L1) 측정용 시드 증량
--
-- seed-benchmark.sql → seed-popular.sql 순으로 적용한 뒤 이어서 실행한다.
-- upload_course를 3,000건에서 목표 건수(기본 20,000)까지 늘린다.
--
--   psql -h localhost -p 15432 -U postgres -d yourtrip -f scripts/sql/seed-benchmark.sql
--   psql -h localhost -p 15432 -U postgres -d yourtrip -f scripts/sql/seed-popular.sql
--   psql -h localhost -p 15432 -U postgres -d yourtrip \
--        -v target_count=20000 -f scripts/sql/seed-popular-large.sql
--
-- ============================================================
-- 왜 upload_course만 늘리는가
--
-- 규모에 민감한 쪽은 인기 코스의 랭킹 쿼리(findPopularCourseIds)다. 이 쿼리는
-- upload_course와 course_keyword만 훑고 day_schedule/place/place_image는 건드리지 않는다.
-- 상세 조회는 PK 조회라 규모에 거의 무관하므로 함께 늘릴 이유가 없고, 늘리면 RDS
-- db.t3.micro(20GB gp2)의 스토리지·IOPS가 새로운 변수로 들어온다.
--
-- ============================================================
-- 왜 응답 페이로드가 규모와 무관하게 동일한가 (측정의 핵심 전제)
--
-- view_count를 (목표 건수 - id)로 재계산하므로 랭킹은 항상 id 오름차순이고,
-- ALL top5는 규모와 무관하게 언제나 id 1~5다. 테마별 top5도 (n % 7) 배분을 그대로
-- 연장하므로 기존 3,000건 기준과 같은 코스들이 뽑힌다.
--
-- 즉 증량분은 "정렬 대상으로만 존재하고 응답에는 절대 나타나지 않는" 행이다.
-- 규모가 커져도 응답 크기·이미지 개수가 변하지 않으므로, 관측되는 지연 변화는
-- 순수하게 랭킹 쿼리의 스캔/정렬 비용 때문이다. 증량분에 day_schedule·place·
-- place_image를 만들지 않아도 되는 이유이기도 하다 — 절대 조회되지 않는다.
-- ============================================================

\if :{?target_count}
\else
\set target_count 20000
\endif

\echo '목표 upload_course 건수:' :target_count

BEGIN;

-- ============================================================
-- 1. hidden copy (my_course) — upload_course.course_id의 FK 대상
--    기존 시드의 course_id = 3000 + n 규칙을 그대로 연장한다.
--    day_schedule 이하는 만들지 않는다(위 "왜" 참고).
-- ============================================================
INSERT INTO my_course (course_id, user_id, title, location, start_date, end_date, type, uploaded, deleted)
SELECT 3000 + n, 1, 'uploadcourse filler ' || n, 'busan',
       DATE '2026-01-01', DATE '2026-01-07', 'UPLOADED', true, false
FROM generate_series(3001, :target_count) AS n;

-- ============================================================
-- 2. upload_course 증량분
--
-- thumbnail_images3key를 NULL로 둔다. 이 행들은 랭킹 top5에 절대 들어가지 않아
-- 썸네일 URL이 조립될 일이 없는데, 존재하지 않는 S3 key를 박아두면 나중에 이 시드를
-- 다른 측정에 재사용할 때 조용히 깨진 URL을 만들어낸다. NULL이면 매퍼가 null을 반환해
-- "이 행은 콘텐츠가 없는 채움용"임이 드러난다.
-- ============================================================
INSERT INTO upload_course (upload_course_id, course_id, user_id, title, introduction, location,
                           thumbnail_images3key, view_count, heart_count, fork_count, deleted)
SELECT n, 3000 + n, 1, 'uploadcourse filler ' || n, 'scale curve filler', 'busan',
       NULL, 0, 0, 0, false
FROM generate_series(3001, :target_count) AS n;

-- ============================================================
-- 3. mood 키워드 배분 — seed-popular.sql의 (n % 7) 규칙을 그대로 연장
--    테마별 top5가 기존 3,000건 기준과 동일하게 유지된다.
-- ============================================================
INSERT INTO course_keyword (upload_course_id, keyword_type)
SELECT n,
       (ARRAY['HEALING','ACTIVITY','FOOD','SENSIBILITY','CULTURE','NATURE','SHOPPING'])[1 + (n % 7)]
FROM generate_series(3001, :target_count) AS n;

-- ============================================================
-- 4. view_count 전량 재계산
--
-- seed-popular.sql은 (3000 - id)를 썼는데, 그대로 연장하면 id 3001부터 음수가 된다.
-- 전체를 (목표 건수 - id)로 다시 깔면 음수가 사라지고 동점도 생기지 않는다.
-- 절대값은 달라지지만 랭킹은 정렬 순서에만 의존하므로 3,000건 기준 측정과 비교 가능하다.
-- ============================================================
UPDATE upload_course SET view_count = :target_count - upload_course_id;

-- ============================================================
-- 5. identity 시퀀스 동기화
-- ============================================================
SELECT setval(pg_get_serial_sequence('my_course', 'course_id'), (SELECT MAX(course_id) FROM my_course));
SELECT setval(pg_get_serial_sequence('upload_course', 'upload_course_id'), (SELECT MAX(upload_course_id) FROM upload_course));
SELECT setval(pg_get_serial_sequence('course_keyword', 'course_keyword_id'), (SELECT MAX(course_keyword_id) FROM course_keyword));

COMMIT;

-- ============================================================
-- 6. 검증 — seed-popular.sql과 같은 항목을 규모만 바꿔 다시 확인한다
-- ============================================================

-- 총 건수 — 기대: target_count
SELECT count(*) AS upload_courses FROM upload_course;

-- ALL 랭킹 top5 — 기대: upload_course_id 1,2,3,4,5 (규모와 무관하게 고정)
SELECT upload_course_id, view_count
FROM upload_course
ORDER BY view_count DESC
LIMIT 5;

-- FOOD 테마 top5 — 기대: 5건, 3,000건 기준과 동일한 id 집합
SELECT uc.upload_course_id, uc.view_count
FROM upload_course uc
WHERE EXISTS (SELECT 1 FROM course_keyword ck
              WHERE ck.upload_course_id = uc.upload_course_id
                AND ck.keyword_type = 'FOOD')
ORDER BY uc.view_count DESC
LIMIT 5;

-- 테마별 코스 수 — 각 테마가 top5를 채울 만큼(>=5) 있어야 한다
SELECT keyword_type, count(*) AS courses
FROM course_keyword
GROUP BY keyword_type
ORDER BY keyword_type;

-- view_count 동점 여부 — 기대: 0 (동점이 있으면 랭킹이 비결정적이 된다)
SELECT count(*) AS duplicate_view_counts
FROM (SELECT view_count FROM upload_course GROUP BY view_count HAVING count(*) > 1) dup;

-- top5가 전부 썸네일을 가진 실제 코스인지 — 기대: 5
-- 0이 아닌 다른 값이면 채움용 행이 top5에 올라온 것이므로 응답 페이로드가 달라져
-- 규모 간 비교가 깨진다.
SELECT count(*) AS top5_with_thumbnail
FROM (SELECT thumbnail_images3key FROM upload_course ORDER BY view_count DESC LIMIT 5) t
WHERE t.thumbnail_images3key IS NOT NULL;
