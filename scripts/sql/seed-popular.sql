-- ============================================================
-- 인기 코스(/api/upload-courses/popular) 부하 테스트용 시드 보강
--
-- seed-benchmark.sql을 먼저 실행한 뒤 이어서 적용한다. 기존 파일을 수정하지 않고
-- 분리한 이유: 상세조회 측정(stage0/stage1)의 시드 조건을 건드리면 그 실측들과의
-- 비교 가능성이 깨진다.
--
-- seed-benchmark.sql만으로는 인기 코스 경로를 측정할 수 없다:
--   1. upload_course.view_count가 3,000건 전부 0이라 ORDER BY viewCount DESC가
--      전부 동점 — top5가 비결정적이라 run 간 비교가 성립하지 않는다.
--   2. course_keyword에 행이 하나도 없어 ?theme=FOOD 같은 테마 조회가
--      EXISTS 조건(UploadCourseRepository.findPopularCourseIds)에 걸려 항상 빈
--      배열을 반환한다 — 아이템 캐시(MGET/파이프라인 SET) 경로를 아예 안 탄다.
--
-- 사용법 (SSM 포트포워딩으로 RDS 터널을 연 상태에서):
--   psql -h localhost -p 15432 -U postgres -d yourtrip -f scripts/sql/seed-benchmark.sql
--   psql -h localhost -p 15432 -U postgres -d yourtrip -f scripts/sql/seed-popular.sql
-- ============================================================

BEGIN;

-- ============================================================
-- 1. view_count 분포 부여
--
-- random()을 쓰지 않는다 — run마다 top5가 달라지면 before/after 비교가 오염되고,
-- 캐시에 담기는 아이템 집합도 매번 바뀌어 아이템 캐시 히트율이 흔들린다.
-- id가 작을수록 조회수가 높은 결정적 분포라, ALL 랭킹 top5는 항상 id 1~5다.
-- ============================================================
UPDATE upload_course SET view_count = 3000 - upload_course_id;

-- ============================================================
-- 2. mood 키워드 배분
--
-- validateThemeIsMoodOrNull이 허용하는 mood 7종(KeywordType.findByCategory("mood"))을
-- 균등 배분한다. 코스당 정확히 1개씩이라 테마별 top5가 서로 겹치지 않는다
-- (테마 t의 top5 = view_count가 가장 높은, 즉 id가 가장 작은 해당 테마 코스 5개).
-- 결과적으로 랭킹 캐시 키 8개(ALL + mood 7종) × 각 5건 = 아이템 캐시 최대 40개가
-- 워밍 대상이 된다.
-- ============================================================
INSERT INTO course_keyword (upload_course_id, keyword_type)
SELECT n,
       (ARRAY['HEALING','ACTIVITY','FOOD','SENSIBILITY','CULTURE','NATURE','SHOPPING'])[1 + (n % 7)]
FROM generate_series(1, 3000) AS n;

SELECT setval(pg_get_serial_sequence('course_keyword', 'course_keyword_id'),
              (SELECT MAX(course_keyword_id) FROM course_keyword));

COMMIT;

-- ============================================================
-- 3. 검증
-- ============================================================

-- 테마별 코스 수 — 각 테마가 top5를 채울 만큼(>=5) 있어야 한다. 기대: 각 428 또는 429
SELECT keyword_type, count(*) AS courses
FROM course_keyword
GROUP BY keyword_type
ORDER BY keyword_type;

-- ALL 랭킹 top5 — 기대: upload_course_id 1,2,3,4,5 (view_count 2999..2995)
SELECT upload_course_id, view_count
FROM upload_course
ORDER BY view_count DESC
LIMIT 5;

-- FOOD 테마 top5 — 기대: 5건 (빈 결과면 course_keyword 시드가 실패한 것)
SELECT uc.upload_course_id, uc.view_count
FROM upload_course uc
WHERE EXISTS (SELECT 1 FROM course_keyword ck
              WHERE ck.upload_course_id = uc.upload_course_id
                AND ck.keyword_type = 'FOOD')
ORDER BY uc.view_count DESC
LIMIT 5;

-- view_count 동점 여부 — 기대: 0 (동점이 있으면 랭킹이 비결정적이 된다)
SELECT count(*) AS duplicate_view_counts
FROM (SELECT view_count FROM upload_course GROUP BY view_count HAVING count(*) > 1) dup;
