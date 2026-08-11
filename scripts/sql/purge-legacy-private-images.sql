-- 구 형식(private/{yyyy-MM-dd}/{uuid}) 비공개 이미지 폐기 절차
--
-- 배경: 1단계에서 비공개 S3 key를 private/{courseId}/{uuid}로 바꿨다(MediaKeys 참고).
-- 상세조회는 이제 코스당 서명 1회로 private/{courseId}/* 정책을 발급하므로, 구 형식 key를
-- 가진 이미지는 그 스코프 밖이라 영구적으로 403이 된다. 하위호환 분기를 두지 않기로 했으므로
-- 구 데이터는 폐기한다(docs/tasks/connection-pool-bottleneck/stage1/design-and-poc.md).
--
-- !!! 이 절차는 되돌릴 수 없다 !!!
--   - S3 버저닝이 Disabled다(terraform/s3.tf) — 삭제한 오브젝트는 복구 수단이 없다.
--   - 백업을 뜨지 않기로 결정했다. 따라서 아래 "선행 조건"을 전부 통과한 뒤에만 실행한다.
--
-- 선행 조건 (하나라도 미달이면 중단)
--   1. 와일드카드 PoC 6개 판정 통과 (stage1/design-and-poc.md에 기록됨)
--   2. 서명 전환 코드가 배포되어 있고, 실제 상세조회 응답의 URL이 200을 받는 것을 확인
--      (같은 쿼리스트링을 다른 이미지에 붙여도 200, 다른 코스에 붙이면 403)
--   3. 수동 E2E 1회: 코스 생성 → 이미지 업로드 → 상세조회에서 이미지 로드 →
--      fork 후에도 로드(copyToPrivate 경로 검증)
--   4. 아래 STEP 1의 카운트 교차 검증 통과
--
-- 부하테스트 환경은 DB_DDL_AUTO=create라 재기동마다 스키마가 초기화되므로, 이 절차가
-- 실제로 의미를 갖는 건 개발/운영 DB뿐이다.


-- ============================================================
-- STEP 1. 사전 카운트 교차 검증 (읽기 전용)
-- ============================================================
-- (a)와 (b의 DIRECT+FORK 합)이 일치해야 한다. 어긋난다면 "코스 type과 key prefix가
-- 대응한다"는 전제가 깨진 것이므로, 그 상태로 삭제하면 공개 이미지를 날리거나 비공개
-- 잔재를 남긴다. 반드시 원인부터 규명하고 중단한다.

-- (a) private key를 가진 행 수
SELECT count(*) AS private_key_rows
FROM place_image
WHERE place_images3key LIKE 'private/%';

-- (b) 소유 코스 type 기준 분포 (place → day_schedule → my_course 3-hop)
SELECT c.type, count(*) AS rows
FROM place_image pi
JOIN place p        ON p.place_id = pi.place_id
JOIN day_schedule d ON d.day_schedule_id = p.day_schedule_id
JOIN my_course c    ON c.course_id = d.course_id
GROUP BY c.type
ORDER BY c.type;

-- (c) 구 형식만 골라낸 수 — 실제 삭제 대상. STEP 3의 삭제 건수와 일치해야 한다.
--     이미 새 형식(private/{courseId}/)인 행은 건드리지 않는다.
SELECT count(*) AS legacy_format_rows
FROM place_image pi
JOIN place p        ON p.place_id = pi.place_id
JOIN day_schedule d ON d.day_schedule_id = p.day_schedule_id
JOIN my_course c    ON c.course_id = d.course_id
WHERE c.type IN ('DIRECT', 'FORK')
  AND pi.place_images3key LIKE 'private/%'
  AND pi.place_images3key NOT LIKE 'private/' || d.course_id || '/%';


-- ============================================================
-- STEP 2. S3에서 지울 key 목록 추출 (읽기 전용)
-- ============================================================
-- 이 목록을 파일로 저장한 뒤 STEP 4에서 사용한다. psql 예:
--   \copy (<아래 SELECT>) TO 'legacy-private-keys.txt'
--
-- 주의: 앱 IAM 유저(yourtrip-s3-app-user)에는 s3:ListBucket 권한이 없다(PoC에서 확인).
-- 따라서 `aws s3 rm --recursive`는 AccessDenied로 실패한다 — 버킷을 훑는 대신 DB가 알고
-- 있는 key만 개별 삭제해야 한다. 이게 오히려 안전하다(예상 못 한 오브젝트를 지울 위험 없음).

SELECT pi.place_images3key
FROM place_image pi
JOIN place p        ON p.place_id = pi.place_id
JOIN day_schedule d ON d.day_schedule_id = p.day_schedule_id
JOIN my_course c    ON c.course_id = d.course_id
WHERE c.type IN ('DIRECT', 'FORK')
  AND pi.place_images3key LIKE 'private/%'
  AND pi.place_images3key NOT LIKE 'private/' || d.course_id || '/%'
ORDER BY pi.place_images3key;


-- ============================================================
-- STEP 3. DB 행 삭제 (S3보다 먼저)
-- ============================================================
-- 순서가 중요하다: S3를 먼저 지우면 "DB에는 행이 있는데 오브젝트가 없는" 구간이 생겨
-- 사용자에게 깨진 이미지가 보인다. DB를 먼저 지우면 그 구간에는 이미지가 안 보일 뿐이다.
--
-- 삭제 조건을 두 개(type + key 형식) 모두 AND로 묶는 이유: type 필터는 "비공개 코스"라는
-- 도메인 기준이고, LIKE 조건은 CloudFront가 실제로 보는 기준이다. 하나만 쓰면 전제가
-- 틀렸을 때 조용히 잘못된 범위를 지운다.
--
-- 코스/장소 행은 남긴다 — 이미지 없는 코스로 유지된다(코스 자체를 지우지 않기로 결정).

BEGIN;

DELETE FROM place_image pi
USING place p, day_schedule d, my_course c
WHERE pi.place_id = p.place_id
  AND p.day_schedule_id = d.day_schedule_id
  AND d.course_id = c.course_id
  AND c.type IN ('DIRECT', 'FORK')
  AND pi.place_images3key LIKE 'private/%'
  AND pi.place_images3key NOT LIKE 'private/' || d.course_id || '/%';

-- 삭제 건수가 STEP 1의 (c)와 일치하는지 확인한 뒤에만 COMMIT한다.
-- 어긋나면 ROLLBACK.
COMMIT;


-- ============================================================
-- STEP 4. S3 오브젝트 삭제 (셸에서 수행)
-- ============================================================
-- STEP 2에서 뽑은 목록으로 개별 삭제한다.
--
--   while read -r key; do
--     aws s3api delete-object --bucket "$S3_BUCKET" --key "$key" >/dev/null \
--       && echo "deleted: $key"
--   done < legacy-private-keys.txt
--
--   aws cloudfront create-invalidation --distribution-id "$DIST_ID" --paths "/private/*"
--
-- 삭제 확인(404가 기대값):
--   aws s3api head-object --bucket "$S3_BUCKET" --key "<key>"   # NoSuchKey여야 정상


-- ============================================================
-- STEP 5. 사후 검증 (읽기 전용, 기대: 0)
-- ============================================================
-- 남아 있는 모든 비공개 key가 소유 코스의 서명 스코프 안에 있어야 한다.
-- 0이 아니면 그 이미지는 상세조회에서 403이 되고 k6의 partial_responses로 드러난다.

SELECT count(*) AS mismatched_private_keys
FROM place_image pi
JOIN place p        ON p.place_id = pi.place_id
JOIN day_schedule d ON d.day_schedule_id = p.day_schedule_id
WHERE pi.place_images3key LIKE 'private/%'
  AND pi.place_images3key NOT LIKE 'private/' || d.course_id || '/%';
