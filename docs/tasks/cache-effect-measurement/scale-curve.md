# 규모 곡선 — 인기 코스 랭킹 쿼리 (L1)

> 캐싱 도입의 원래 근거였던 **"데이터가 늘수록 느려진다"** 를 검증한 기록이다.
>
> **결론만 먼저**: 두 경로가 **정반대로 거동한다.** 기본 인기 코스 조회(`ALL`)는 규모를 16.7배 올려도 **완전히 평탄**하고, 테마별 조회(`?theme=...`)는 **약 14배 선형으로 증가**한다. 원인은 `course_keyword`에 인덱스가 PK뿐이라 `EXISTS`가 매번 전체 Seq Scan을 도는 것이다.
>
> 다만 50,000건에서도 **5.8ms**다. 선형 증가는 사실이나 절대값이 아직 작아, **캐싱을 정당화할 만큼 극적이지는 않다.**

## 부하 테스트 대신 쿼리 플랜 분석으로 대체한 이유

계획의 L1은 `seed-popular-large.sql`로 20,000 / 50,000건까지 늘려 **P1(부하 테스트)을 A0·A2에 대해 반복**하는 것이었다. 이를 `EXPLAIN (ANALYZE, BUFFERS)`로 대체했다. 근거는 셋이다.

**1. 신호 대 잡음이 나쁘다.** L1이 보려는 것은 랭킹 쿼리 **1문장**인데, 요청 하나는 **8문장**이다.

| 문장 | 규모 민감도 |
|---|---|
| `findPopularCourseIds` (랭킹) | **민감** ← 보려는 것 |
| `findAllByIdInWithKeywords` | 둔감 (`IN` 5건) |
| `my_course` PK 조회 × 5 (N+1) | 둔감 |
| `users` PK 조회 | 둔감 |

보려는 신호가 SQL 작업의 1/8이고 나머지 7/8이 규모에 둔감한 상수다. 여기에 커넥션 풀 대기(A0는 VU 20부터 이미 포화), JSON 직렬화, CPU가 얹히면 신호가 더 묻힌다.

**2. 질문이 쿼리 수준이다.** "이 쿼리가 규모에 눕는가"는 플랜을 보면 직접 답이 나온다. 부하 테스트는 그 답을 스택 전체로 희석해 간접 관측하는 셈이다.

**3. 비용 차이가 크다.** 부하 테스트는 4 run(약 1시간) + 시딩이고, `EXPLAIN`은 몇 분이다.

**부하 테스트가 여전히 필요한 경우**는 "쿼리 저하가 실제 사용자 지연·처리량으로 이어지는가"를 확인할 때다. 아래 결과처럼 저하 폭이 작으면 그 확인의 값어치도 작다.

## 측정 방법

| | 값 |
|---|---|
| DB | RDS db.t3.micro, PostgreSQL 16.13 (부하테스트 인프라) |
| 규모 | `upload_course` **3,000 / 20,000 / 50,000** (`course_keyword`도 같은 배수로 증가) |
| 시드 | `seed-benchmark.sql` → `seed-popular.sql` → `seed-popular-large.sql` |
| 측정 | `EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)`, 매 규모에서 `ANALYZE` 선행 |
| 스크립트 | `scratchpad/explain-ranking.sql` (미커밋) |

### 리터럴이 아니라 파라미터로 재야 한다

이 측정에서 가장 중요한 함정이다. 대상 쿼리는 `(:theme IS NULL OR EXISTS (...))` 형태인데, **리터럴 `NULL`로 `EXPLAIN`하면 PostgreSQL이 `NULL IS NULL`을 상수 폴딩해 `EXISTS`를 통째로 제거한 플랜**을 보여준다. 그건 운영에서 도는 플랜이 아니다. Hibernate는 파라미터로 바인딩하기 때문이다.

그래서 `PREPARE` + `SET plan_cache_mode = force_generic_plan`으로 **파라미터를 모르는 상태의 플랜**도 함께 확인했다.

**결과적으로 두 플랜은 동일했다.** `ALL` 케이스에서는 SubPlan이 `never executed`로 런타임에 단락된다. 즉 `(? IS NULL OR ...)` 구조가 플래너를 망치지는 않는다 — 사전에 우려했던 지점이지만 실측으로 배제됐다.

## 결과

| `upload_course` | ALL (`theme IS NULL`) | 테마 지정 (`FOOD`) |
|---|---|---|
| 3,000 | **0.043ms** / buffers 3 | 0.402ms / buffers 36 |
| 20,000 | **0.040ms** / buffers 4 | 2.408ms / buffers 155 |
| 50,000 | **0.036ms** / buffers 3 | **5.781ms** / buffers 345 |
| **16.7배 증가 시** | **변화 없음** | **약 14배** |

(파라미터 바인딩 = 제네릭 플랜 기준. `Execution Time`)

### ALL — 평탄하다

```
Limit (actual time=0.012..0.014 rows=5)
  Buffers: shared hit=3
  └─ Index Scan Backward using idx_upload_course_view_count on upload_course
       Filter: (NOT deleted) AND (($1 IS NULL) OR (hashed SubPlan 2))
       SubPlan 2
         └─ Seq Scan on course_keyword    (never executed)
```

`view_count` 인덱스를 **내림차순으로 훑다 5건을 찾으면 멈춘다.** 버퍼 3~4개는 인덱스 페이지 몇 장뿐이고 테이블 크기와 무관하다. `LIMIT 5`가 있어야 성립하는 플랜이라, **인덱스와 top-N 쿼리는 세트다** — 인덱스만 추가하고 페이징 없는 쿼리를 그대로 뒀다면 아무 효과도 없었을 것이다.

### 테마 지정 — 선형으로 증가한다

```
Limit (actual time=5.730..5.741 rows=5)
  Buffers: shared hit=345
  └─ Index Scan Backward using idx_upload_course_view_count on upload_course
       Filter: (NOT deleted) AND (($1 IS NULL) OR (hashed SubPlan 2))
       Rows Removed by Filter: 25
       SubPlan 2
         └─ Seq Scan on course_keyword  (actual time=0.010..4.405 rows=7143)
              Filter: (keyword_type)::text = ($1)::text
              Rows Removed by Filter: 42857        ← 42,857행을 버리려고 전부 읽는다
              Buffers: shared hit=341
```

`EXISTS`가 **해시 세미조인**으로 바뀌면서 `course_keyword` 전체를 Seq Scan한다. 해시는 쿼리당 1회만 만들므로 O(N)이지 O(N²)는 아니지만, **N에 정비례한다.**

바깥 `Index Scan`은 규모와 무관하다 — 테마가 7종 균등 배분이라 5건을 찾는 데 항상 약 30행만 보면 된다(`Rows Removed by Filter: 25`가 세 규모에서 모두 동일).

### 원인 — `course_keyword`에 인덱스가 PK뿐이다

```
course_keyword_pkey | UNIQUE INDEX ... (course_keyword_id)
```

`upload_course_id`에도 `keyword_type`에도 인덱스가 없다. 그래서 "이 테마를 가진 코스"를 찾는 유일한 방법이 전체 스캔이다.

## 해석 — 캐싱 정당성에 주는 답

**규모 논거는 절반만 유효하다.**

| 경로 | 규모 반응 | 캐싱의 규모 근거 |
|---|---|---|
| `/popular` (ALL) | 평탄 | **성립하지 않는다** |
| `/popular?theme=...` | 선형 (약 14배) | 성립하나 **절대값이 작다**(50,000건에서 5.8ms) |

요청 하나가 8문장이고 저부하 평균 응답이 8.4ms인 것을 감안하면, 50,000건 규모에서 테마 조회가 대략 1.6배 느려지는 정도다.

**따라서 캐싱의 주된 근거는 규모가 아니다.** [ec2-measurement.md](ec2-measurement.md)에서 측정한 쪽이 훨씬 강하다 — 3,000건이라는 **현재 규모에서 이미** 요청당 SQL 8건 제거(DB 초당 약 7,000쿼리 → 0), 커넥션 점유시간 9.38ms → 3.83ms, TPS **+120.0%**, p95 −49.8%.

규모는 **보조 논거**로, "테마 조회에 한해 선형 증가를 확인했다"로 한정해 쓰는 것이 정확하다.

> **원래 근거와의 관계**: [redis-caching/README.md:39](../redis-caching/README.md)의 "페이징이 없어 데이터가 늘수록 선형으로 느려진다"는 **레거시 쿼리**(`findAllByKeywordsOrderByViewCountDesc` — `Pageable` 없이 전건 반환 + 행별 상관 서브쿼리) 기준이며, 캐싱 도입 시점(`7e74d0d`, 2026-07-31)에는 `view_count` 인덱스조차 없었다(`10a3ec2`, 2026-08-01). **그 서술은 당시 기준으로 정확했다.** 이 문서가 반박하는 것은 "지금의 최적화된 쿼리도 규모에 눕는가"이지 당시 판단이 아니다.

## 이 측정이 드러낸 개선 후보

**둘 다 이번 측정 범위 밖이라 고치지 않았다** — 세 arm의 DB 쿼리는 통제 변수이고, 21개 run이 현재 쿼리 기준으로 쌓여 있다.

### 1. `course_keyword` 복합 인덱스

`(keyword_type, upload_course_id)` 인덱스를 넣으면 Seq Scan이 Index Scan으로 바뀔 가능성이 크다. **캐싱 없이도 테마 경로를 평탄화할 수 있다**는 뜻이라, 그 자체로 전후 실측이 가능한 독립 항목이다.

> **정정 (2026-08-18, [popular-theme-index](../popular-theme-index/README.md))**: 착수해 실측한 결과 **이 예측은 절반만 맞았다.** 인덱스는 실제로 Seq Scan을 Index Only Scan으로 바꿔 버퍼를 345 → 35로 줄이지만, **선형 증가는 사라지지 않는다**(50,000건 1.600ms, 3,000건 대비 12.6배). 해시 빌드가 여전히 `keyword_type`이 일치하는 N/7행 전부를 읽기 때문이다 — 읽는 페이지 수만 줄 뿐 읽는 행 수는 그대로다.
>
> 진짜 원인은 인덱스 부재가 아니라 **`(:theme IS NULL OR EXISTS ...)`의 `OR` 구조**였다. PostgreSQL의 서브쿼리 pull-up이 `AND` 트리만 재귀하고 `OR` 아래는 열어보지 않아, `EXISTS`가 세미조인으로 승격될 기회 자체를 못 얻는다. 쿼리를 ALL용·테마용으로 **쪼개고** 인덱스를 함께 넣어야 `Nested Loop Semi Join`이 성립해 평탄해진다(50,000건 **0.053ms / 버퍼 65**, 3,000건과 버퍼 동일).
>
> 컬럼 순서도 `(upload_course_id, keyword_type)`으로 뒤집었다 — probe 조건이 둘 다 등치라 이 쿼리에는 순서가 무관하지만, 이 순서라야 누락된 FK 인덱스 역할까지 겸한다. `(keyword_type, upload_course_id)`는 추가해도 이득이 없어 넣지 않았다.
>
> **주의**: 쿼리 분리만 배포하고 인덱스가 없으면 기준선보다 **20배 나빠진다**(50,000건 108ms). 두 변경은 한 쌍이다.

### 2. 일반 목록 조회의 레거시 쿼리

`findAllByKeywordsOrderByViewCountDesc`는 **지금도 [UploadCourseServiceImpl.java:246](../../../src/main/java/backend/yourtrip/domain/uploadcourse/service/UploadCourseServiceImpl.java:246)에서 쓰인다.** 페이징이 없어 전건을 반환하고 상관 서브쿼리가 행마다 돌며, 캐싱도 인덱스 최적화도 적용되지 않았다. 규모가 커지면 `/popular`보다 이쪽이 먼저 무너진다.

## 알게 된 함정 — `seed-popular-large.sql`은 연속 증량을 못 한다

3,000건 기준에서만 동작한다. 20,000 → 50,000으로 이어서 올리면 **`my_course_pkey` 중복 키로 트랜잭션이 통째로 롤백**된다(증분 hidden copy의 id가 겹친다).

계획의 L1 절차가 "3,000 → 20,000 → 50,000 순차 증량"이었으므로, **부하 테스트로 진행했다면 두 번째 규모 전환에서 조용히 깨졌을 것이다** — 시딩 실패 후에도 이전 규모로 run이 돌아 "규모를 올렸는데 변화가 없다"는 잘못된 결론이 나올 수 있었다.

**올바른 절차**는 규모를 바꿀 때마다 앱을 재기동해 스키마를 새로 만들고(`DB_DDL_AUTO=create`) 목표치로 한 번에 시딩하는 것이다 — `run.sh switch <arm> <target_count>`가 이 순서를 따른다.

## 한계

- **쿼리 수준 측정이다.** 이 지연이 부하 상황에서 실제 사용자 지연·처리량으로 어떻게 나타나는지는 재지 않았다. 저하 폭이 작아 그 확인의 값어치가 낮다고 판단했다.
- **단발 실행이다.** 각 규모·경로마다 `EXPLAIN ANALYZE` 1회이고 반복하지 않았다. 다만 세 규모의 버퍼 수(36 → 155 → 345)가 행 수에 정비례해 추세 자체는 견고하다.
- **버퍼가 전부 `shared hit`이다.** 디스크 읽기(`read`)가 없어 **완전히 캐시된 상태**의 측정이다. 실제 운영에서 버퍼 풀을 벗어나면 저하 폭이 더 커진다.
- **`FOOD` 테마 하나만 쟀다.** 7종이 균등 배분이라 다른 테마도 같을 것으로 보나 확인하지 않았다.

## 참고 문서

- [ec2-measurement.md](ec2-measurement.md) — 캐싱 효과 본 측정 (P1·P3·P5·D2·D3)
- [README.md](README.md) — arm 설계와 진행 상황
- [redis-caching/README.md](../redis-caching/README.md) — 캐싱 도입 당시의 설계와 근거
