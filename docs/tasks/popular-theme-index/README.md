# 테마 지정 인기 코스 조회의 선형 증가 제거

> **이 작업의 위치** — 인기 코스·상세 조회 성능 개선 사슬의 **6단계**다.
>
> 1. [커넥션 풀 병목 발견](../connection-pool-bottleneck/PRESIGN-BOTTLENECK.md)
> 2. [Redis 캐싱 도입](../redis-caching/README.md)
> 3. [트랜잭션 분리 측정](../popular-tx-separation/README.md)
> 4. [캐싱 효과 측정](../cache-effect-measurement/README.md)
> 5. [인기 코스 N+1 제거](../popular-n-plus-one/README.md)
> 6. **테마 조회 선형 증가 제거** — [규모 곡선 측정](../cache-effect-measurement/scale-curve.md)에서 **발견**됐다

> **결론만 먼저**: 랭킹 쿼리를 ALL용·테마용 **두 개로 쪼개고** `course_keyword`에 복합 인덱스 하나를 추가했다. 50,000건에서 테마 조회가 **5.367ms → 0.053ms(101배)**, 버퍼 **345 → 65**가 됐고 규모 의존성이 사라졌다(3,000→50,000에서 버퍼 65 고정).
>
> **핵심은 인덱스가 아니라 쿼리 형태였다.** 인덱스만 넣으면 기울기만 완만해지고 선형은 남는다(50,000건 1.600ms, 여전히 12.6배 증가). 반대로 쪼개기만 하고 인덱스가 없으면 **기준선보다 20배 나빠진다**(108.183ms). 둘은 독립적인 개선이 아니라 한 쌍이다.

## 원인 — 두 겹이고, 겉의 한 겹이 진짜다

### 1. `OR`이 세미조인 승격을 구조적으로 막는다

변경 전 쿼리는 테마 유무를 하나의 JPQL로 처리했다.

```sql
where ( uc1_0.deleted = false )
  and ( $1 is null
        or exists(select 1 from course_keyword ck1_0
                  where ck1_0.upload_course_id = uc1_0.upload_course_id
                    and ck1_0.keyword_type = $2) )
```

`EXISTS`는 논리적으로 **세미조인**이라, "바깥 행 하나마다 안쪽을 인덱스로 한 번 조회"하는 실행이 가능하다. 그러려면 플래너가 `EXISTS`를 조인으로 **승격**시켜야 하는데, 조인은 "이 두 테이블은 항상 이렇게 엮인다"는 고정 배선이다.

그런데 위 쿼리는 `$1`이 `NULL`이면 `course_keyword`를 쳐다보지도 않고 통과한다 — **엮을지 말지가 실행 시점 파라미터에 달려 있다.** 고정 배선으로 표현할 수 없으니 승격이 불가능하다. PostgreSQL의 서브쿼리 pull-up이 `WHERE`의 `AND` 트리(와 `NOT`)만 재귀하고 `OR` 아래는 열어보지 않는 것이 이 판단의 구현이다.

**이건 비용 비교가 아니라 플래너 전처리 단계의 컷오프다.** 인덱스는 "어느 경로가 싼가"를 겨루는 단계에서 쓰이는데, 이 결정은 그 단계가 시작되기 전에 끝나 있다 — **그래서 인덱스로는 우회할 수 없다.**

### 2. 승격에 실패한 `EXISTS`는 "명단"이 된다

필터 표현식으로 남은 `EXISTS`를 PostgreSQL은 비상관화해 `hashed SubPlan`으로 처리한다. `keyword_type`이 일치하는 행 **전부**로 해시 테이블을 지은 뒤 바깥 행마다 대조하는 방식이다.

```
Limit (actual time=5.263..5.272 rows=5.00)
  Buffers: shared hit=345
  ->  Index Scan Backward using idx_upload_course_view_count on upload_course
        Filter: ((NOT deleted) AND (ANY (upload_course_id = (hashed SubPlan 2).col1)))
        Rows Removed by Filter: 25                      ← 바깥은 30행만 본다 (규모 무관)
        SubPlan 2
          ->  Seq Scan on course_keyword                ← 여기만 규모에 비례한다
                Filter: ((keyword_type)::text = 'FOOD'::text)
                rows=7143  Rows Removed by Filter: 42857   ← 50,000행 전부 읽는다
                Buffers: shared hit=341
```

**바깥은 이미 완벽하게 평탄하다.** `Rows Removed by Filter: 25` + 반환 5 = **30행**만 훑는데, 이 값은 세 규모에서 모두 같다(시드가 mood 7종 균등 배분이라 5건 채우는 데 항상 30행). 선형인 것은 오직 해시 빌드다.

그리고 **해시 빌드는 startup cost라 `LIMIT 5`로 줄지 않는다** — 명단은 완성돼야 쓸 수 있기 때문이다. 위 플랜에서 첫 행까지 5.263ms, 나머지 5건 뽑는 데 0.009ms다. 시간의 99.8%가 명단 작성이다.

## 세 선택지와 결정

| 안 | 50,000건 결과 | 판정 |
|---|---|---|
| (a) 인덱스만 추가 | 1.600ms / 35버퍼 — **여전히 선형**(3,000 대비 12.6배) | 기각 |
| (b) 쿼리 분리만 | 108.183ms / 709버퍼 — **기준선보다 20배 나쁨** | 기각 |
| **(c) 분리 + 인덱스** | **0.053ms / 65버퍼 — 평탄** | **채택** |

[scale-curve.md](../cache-effect-measurement/scale-curve.md)가 남긴 "개선 후보 1"은 (a)였고 "캐싱 없이도 테마 경로를 평탄화할 수 있다"고 예측했다. **이번 실측이 그 예측을 반증했다** — 인덱스는 명단 작성을 Seq Scan에서 Index Only Scan으로 바꿔 **읽는 페이지 수만 줄일 뿐**(345 → 35), 여전히 `keyword_type`이 일치하는 N/7행 전부로 명단을 만든다.

(b)를 단독으로 채택하지 않은 이유는 아래 "**코드만 배포하면 더 나빠진다**"에 따로 적었다. 이번 측정에서 가장 중요한 발견이다.

## 변경

**쿼리 분리** — [UploadCourseRepository.java](../../../src/main/java/backend/yourtrip/domain/uploadcourse/repository/UploadCourseRepository.java)

```java
// 변경 전 — 하나로 합쳐져 있었다
List<Long> findPopularCourseIds(@Param("theme") KeywordType theme, Pageable pageable);

// 변경 후 — 둘로 쪼갰다
List<Long> findPopularCourseIds(Pageable pageable);
List<Long> findPopularCourseIdsByTheme(@Param("theme") KeywordType theme, Pageable pageable);
```

테마 쿼리는 `WHERE EXISTS (...)`가 최상위 `AND`에 오는 것 **하나만** 달라졌다. 상관 조건은 기존 `ck.uploadCourse = uc`를 그대로 뒀다 — 캡처한 SQL을 보니 Hibernate가 이미 조인 없이 `ck1_0.upload_course_id = uc1_0.upload_course_id`로 내고 있어, 손대면 측정의 원인 귀속만 흐려지기 때문이다. **이번 변경의 델타는 `OR` 제거 하나뿐이다.**

**분기** — [UploadCoursePopularReader.java](../../../src/main/java/backend/yourtrip/domain/uploadcourse/service/UploadCoursePopularReader.java)에서 `theme == null`로 갈린다. `UploadCourseServiceImpl`과 `PopularCourseCacheWarmer`는 한 줄도 안 고쳤다 — 이미 이 메서드 하나만 거치고, 랭킹 캐시 키 체계(`"ALL"` vs `theme.name()`)도 그대로다.

**인덱스** — [CourseKeyword.java](../../../src/main/java/backend/yourtrip/domain/uploadcourse/entity/CourseKeyword.java)에 `@Table(indexes = @Index(name = "idx_course_keyword_course_type", columnList = "upload_course_id, keyword_type"))`. 이 엔티티에는 `@Table` 자체가 없어 인덱스가 PK뿐이었다.

컬럼 순서는 `upload_course_id`를 앞에 뒀다. probe 조건이 두 컬럼 다 등치라 순서 자체는 무관하지만, 이 순서라야 컬럼 하나만 주어지는 다른 경로들(`findAllByIdInWithKeywords`의 `LEFT JOIN FETCH`, `findAllByKeywords*`의 상관 COUNT, 코스 삭제 시 FK 검사)까지 함께 커버한다. [stage0/local/index.md:7](../connection-pool-bottleneck/stage0/local/index.md)에서 "검증되지 않아 제외"로 남겨뒀던 **누락 FK 인덱스**가 바로 이것이다.

## 실측

### 환경

| | 값 |
|---|---|
| DB | 로컬 PostgreSQL **18.0** (Windows), `.env`의 `localhost:5434/yourtrip` |
| 플래너 설정 | `work_mem=4MB`, `hash_mem_multiplier=2`, `random_page_cost=4`, `effective_cache_size=5GB`, `shared_buffers=160MB`, `max_parallel_workers_per_gather=2` |
| 규모 | `upload_course` **3,000 / 20,000 / 50,000** (`course_keyword`도 같은 배수, 코스당 1건) |
| 시드 | `seed-benchmark.sql` → `seed-popular.sql` → `seed-popular-large.sql` |
| 측정 | `EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)` **5회 중앙값**, 매 셀 앞에 워밍업 1회 |
| 통계 | 인덱스 변경마다 `VACUUM (ANALYZE) course_keyword, upload_course` |

### 프로토콜 — SQL을 손으로 쓰지 않았다

`PREPARE` 본문은 전부 **앱 부팅 로그(`org.hibernate.SQL`)에서 캡처한 문자열 그대로**다. 캐시 워머가 부팅 시 ALL + mood 7종 8개 키를 채우므로, 앱을 띄우기만 하면 두 쿼리가 다 로그에 남는다.

이게 중요한 이유는 캡처해 보니 **`LIMIT`이 리터럴이 아니라 파라미터**였기 때문이다.

```sql
order by uc1_0.view_count desc
fetch first ? rows only          -- 리터럴 5가 아니다
```

`LIMIT`을 `5`로 손수 박으면 플래너가 `tuple_fraction`을 정확히 잡아 **운영보다 낙관적인 플랜**이 나온다. 또 변경 전 쿼리는 `theme`이 파라미터 **2개**(`? is null`용, `keyword_type=?`용)로 갈린다 — `scale-curve.md`의 스크립트는 `$1` 하나로 재사용했으므로 이번과 다르다.

규모 전환은 매번 `TRUNCATE` 후 목표치로 **한 번에** 시딩했다. `seed-popular-large.sql`은 연속 증량 시 `my_course_pkey` 중복으로 깨진다([scale-curve.md의 함정](../cache-effect-measurement/scale-curve.md)).

### 결과 — 매트릭스 (커스텀 플랜 = 운영 플랜, 실행시간 ms / 버퍼)

| 셀 | 구성 | 3,000 | 20,000 | 50,000 | 거동 |
|---|---|---|---|---|---|
| **a** | 기준선(변경 전) | 0.272 / 25 | 1.555 / 141 | **5.367 / 345** | 선형 |
| **b** | 인덱스만 | 0.127 / 9 | 0.597 / 17 | **1.600 / 35** | **선형 잔존** |
| **c** | 분리만 | 1.100 / 25 | 8.200 / 141 | **108.183 / 709** | **악화** |
| **d1** | **분리 + 인덱스** | 0.067 / 65 | 0.083 / 65 | **0.053 / 65** | **평탄** |
| **d2** | + 인덱스 하나 더 | 0.083 / 65 | 0.065 / 65 | 0.067 / 65 | d1과 동일 |
| **e** | ALL 경로(분리 후) | 0.013 / 3 | 0.017 / 3 | 0.019 / 3 | 회귀 없음 |
| **f** | 테마 `SHOPPING` | 0.214 / 73 | 0.086 / 73 | 0.097 / 73 | FOOD와 동일 |

**사전에 등록한 판정 기준**과 대조하면 넷 다 충족한다.

- *인덱스만으로 부족*(버퍼 ≥5배 감소 **그리고** 시간 ≥3배 증가) → 버퍼 9.9배 감소, 시간 **12.6배 증가** ✅
- *분리만으로 부족*(개선폭 20% 미만) → 개선이 아니라 **20배 악화** ✅
- *목표 달성*(`Nested Loop Semi Join` 등장 **그리고** 50,000이 3,000의 3배 이내) → 버퍼 **65로 완전 동일**, 시간은 오히려 감소 ✅
- *인덱스 1개로 충분*(d2 − d1이 노이즈) → 버퍼 동일, 시간차 노이즈 범위 ✅ → **`(keyword_type, upload_course_id)`는 넣지 않는다**

### 기준선 재현 — 원 측정과 대조

| | 3,000 | 20,000 | 50,000 |
|---|---|---|---|
| 원 측정 (RDS db.t3.micro, PG 16.13) | 0.402ms / 36 | 2.408ms / 155 | 5.781ms / **345** |
| 이번 기준선 (로컬, PG 18.0) | 0.272ms / 25 | 1.555ms / 141 | 5.367ms / **345** |

절대값은 머신·버전이 달라 차이가 나지만 **50,000건의 버퍼 345가 정확히 일치**하고, 플랜 모양(`hashed SubPlan` + `Seq Scan`)과 `Rows Removed by Filter: 25`도 같다. 기준선은 재현됐다고 본다.

### 목표 플랜 — 명단이 사라졌다

```
Limit (actual time=0.023..0.061 rows=5.00)
  Buffers: shared hit=65
  ->  Nested Loop Semi Join
        ->  Index Scan Backward using idx_upload_course_view_count on upload_course
              Filter: (NOT deleted)
              rows=30.00                                    ← 규모와 무관하게 30행
              Buffers: shared hit=4
        ->  Index Only Scan using idx_course_keyword_course_type on course_keyword
              Index Cond: ((upload_course_id = uc1_0.upload_course_id)
                           AND (keyword_type = 'FOOD'::text))
              Heap Fetches: 0
              loops=30                                      ← 행마다 한 번씩 조회
              Buffers: shared hit=61
```

`loops=30`이 이 개선의 전부다. 명단을 미리 만드는 대신 **바깥 행마다 한 번씩 인덱스를 찔러본다.** 30 × 약 2버퍼 = 61버퍼가 되고, 30은 규모와 무관하므로 65버퍼가 세 규모에서 고정된다. `Heap Fetches: 0`은 인덱스만으로 끝나 힙을 아예 안 건드린다는 뜻이다.

버퍼가 기준선(25)보다 오히려 많은 3,000건 구간이 있는데, 명단 방식은 규모에 비례하고 이쪽은 상수라 20,000건부터 역전된다(141 vs 65).

### `plan_cache_mode` — 운영은 커스텀 플랜을 받는다

`LIMIT`이 파라미터라 제네릭 플랜에서 `tuple_fraction`이 0.10으로 잡혀 최악 플랜이 강제될 수 있다는 우려가 있었다. 세 규모 모두에서 확인했다.

```
execute q_theme('FOOD',5)  ×10  →  generic_plans=0  custom_plans=10
execute q_legacy(...)      ×10  →  generic_plans=0  custom_plans=10
```

`auto` 모드가 제네릭 플랜의 추정 비용이 커스텀 평균보다 커서 계속 기각한다. **조치 불필요**이고, `Pageable`을 버리고 `LIMIT 5` 리터럴 네이티브 쿼리로 내리는 대안(계획서의 대응 B)은 쓰지 않았다.

다만 `force_generic_plan`을 강제하면 셀 c가 `Nested Loop` 대신 `Hash Semi Join`으로 바뀌어 108.183ms → 13.126ms가 된다. **제네릭 플랜이 더 나은 유일한 구간**이며, 인덱스가 있는 d1에서는 두 모드가 사실상 동일하다(0.053 vs 0.082).

## 발견 — 코드만 배포하면 오히려 더 나빠진다

셀 c가 단순히 "개선이 없다"가 아니라 **기준선보다 20배 나쁘다**는 것이 이번 측정의 가장 중요한 결과다. 플랜을 보면 이유가 분명하다.

```
Nested Loop Semi Join
  Join Filter: (uc1_0.upload_course_id = ck1_0.upload_course_id)   ← Index Cond가 아니다
  Rows Removed by Join Filter: 178600
```

`OR`을 없애 세미조인 승격은 **성공했는데**, probe할 인덱스가 없으니 플래너가 조인 조건을 `Index Cond`가 아니라 `Join Filter`로 처리한다. 바깥 30행마다 안쪽을 통째로 훑으며 178,600행을 버린다. 즉:

> **쿼리 분리는 인덱스가 있을 때만 개선이고, 없으면 악화다.** 두 변경은 독립적으로 배포 가능한 항목이 아니다.

이번 작업은 **운영 반영용 수동 DDL을 범위에서 제외**했다(사용자 결정, [stage0/local/index.md:68](../connection-pool-bottleneck/stage0/local/index.md)의 선례와 동일). 그런데 배포 환경은 `ddl-auto=validate`라 **`@Table(indexes=...)` 애노테이션만으로는 인덱스가 생성되지 않고, `validate`는 인덱스를 검증조차 하지 않아 조용히 누락된 채로 뜬다.**

**따라서 이 브랜치를 그대로 배포하면 운영은 셀 c 상태가 된다.** 현재 규모(3,000건 가정)에서도 0.272ms → 1.100ms로 **4배 느려지고**, 규모가 커질수록 격차가 벌어진다.

**배포 전 반드시 운영 DB에 인덱스를 먼저 만들어야 한다.**

```sql
-- 트랜잭션 블록 안에서 실행 불가. psql -1 금지, 한 문장씩 자동커밋으로.
-- PgBouncer(transaction mode)를 경유하면 CONCURRENTLY가 깨지므로 DB에 직접 접속한다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_course_keyword_course_type
    ON course_keyword (upload_course_id, keyword_type);

-- ANALYZE만으로는 relallvisible이 갱신되지 않아 Index Only Scan이 선택되지 않는다.
VACUUM (ANALYZE) course_keyword;

-- CONCURRENTLY 실패 시 INVALID 인덱스가 남고, IF NOT EXISTS가 그걸 "이미 있음"으로 건너뛴다.
SELECT indexrelid::regclass, indisvalid FROM pg_index WHERE indrelid = 'course_keyword'::regclass;
```

인덱스는 기존 쿼리에 무해하므로 **코드 배포보다 먼저** 넣어도 안전하다. 순서를 뒤집으면 그 사이 구간이 셀 c다.

## 회귀를 막는 테스트

테스트 DB는 H2라 **실행계획을 잴 수 없다.** 테스트가 지키는 것은 결과 동등성·정렬·문장 수·분기 라우팅뿐이고, "Nested Loop Semi Join이 선택되는가"라는 플랜 계약은 이 문서의 EXPLAIN 기록이 지킨다.

**신규** [UploadCoursePopularThemeQueryTest](../../../src/test/java/backend/yourtrip/domain/uploadcourse/repository/UploadCoursePopularThemeQueryTest.java) 7건 — 분리하면서 **테마 분기를 타는 테스트가 하나도 없다는 것**이 드러나 만들었다.

| 테스트 | 지키는 것 |
|---|---|
| `themeQuery_ReturnsOnlyCoursesHavingThatKeyword` | 테마 필터가 실제로 거른다 |
| `themeQuery_OrdersByViewCountDesc` | 정렬 |
| `themeQuery_ExcludesSoftDeletedCourse` | `@SQLRestriction` 보존 |
| `themeQuery_DoesNotDuplicate_WhenCourseHasSameKeywordTwice` | 세미조인 의미 |
| `themeQuery_ReturnsEmpty_WhenNoCourseHasTheme` | 경계 |
| `allQuery_MatchesLegacyNullThemeSemantics` | 분리 전후 동등성 |
| `themeMissPath_IssuesExactlyTwoStatements` | 문장 수(ALL 경로와 짝) |

**수정** [UploadCoursePopularReaderTest](../../../src/test/java/backend/yourtrip/domain/uploadcourse/service/UploadCoursePopularReaderTest.java) — `theme` 유무가 각각 올바른 메서드로 가고 **다른 쪽은 호출되지 않는지**를 고정한다. 두 쿼리를 다시 합치는 회귀를 막는 유일한 런타임 계약이다.

### 이 테스트가 실제로 무언가를 잡는다는 증거

`EXISTS`를 `JOIN`으로 바꾸는 회귀를 일부러 주입했더니 **2건이 실패**했다.

```
UploadCoursePopularThemeQueryTest > 테마 조회는 조회수 내림차순으로 정렬된다 FAILED
UploadCoursePopularThemeQueryTest > 같은 키워드를 두 번 가진 코스도 한 번만 반환한다 FAILED
```

`JOIN`은 한 코스가 같은 키워드를 두 번 가지면 중복 행을 낸다. 이를 `DISTINCT`로 막으면 top-N 인덱스 스캔이 Sort/Unique로 퇴화해 **규모 의존성이 되살아난다** — 그래서 `EXISTS`를 유지해야 한다.

## 응답 동등성

50,000건 시드에서 구·신 쿼리를 mood 7종 + ALL 전부에 대해 직접 대조했다. **8개 경로 모두 반환 id가 완전히 일치**한다.

```
HEALING     [7,14,21,28,35]    ACTIVITY [1,8,15,22,29]    FOOD    [2,9,16,23,30]
SENSIBILITY [3,10,17,24,31]    CULTURE  [4,11,18,25,32]   NATURE  [5,12,19,26,33]
SHOPPING    [6,13,20,27,34]    ALL      [1,2,3,4,5]
```

실제 엔드포인트로도 확인했다 — `GET /api/upload-courses/popular`는 id 1,2,3…, `?theme=FOOD`는 id 2, 9, 16…으로 위 결과와 같다. (FOOD가 `2, 9, 16, 23, 30`인 것은 시드의 `1 + (n % 7)` 배분에서 `n % 7 == 2`인 코스이기 때문이다.)

## 알게 된 함정

**1. `ANALYZE`만으로는 Index Only Scan이 안 골라진다.** Index Only Scan의 비용 산정은 `pg_class.relallvisible`에 의존하는데 이 값은 **`VACUUM`만 갱신한다.** 대량 INSERT 직후 `relallvisible=0`이면 플래너가 랜덤 힙 페치를 가정해 Seq Scan을 유지한다 — "인덱스를 넣었는데 아무 변화가 없다"는 **거짓 음성**이 나온다. 매 인덱스 변경 후 `VACUUM (ANALYZE)`를 넣어 회피했다.

**2. `plan_cache_mode`의 의미가 쿼리마다 뒤집힌다.** 변경 전 쿼리는 리터럴 `NULL`로 EXPLAIN하면 상수 폴딩으로 `EXISTS`가 통째로 사라지므로 `force_generic_plan`이 필수다. 반면 분리된 테마 쿼리에서는 제네릭 플랜이 오히려 운영과 다른 플랜을 강제한다. **어느 한 모드로 통일해 재면 한쪽이 틀린다** — 그래서 두 모드를 다 재고 `pg_prepared_statements`로 운영 플랜을 별도 판정했다.

**3. `TaskStop`으로 gradle을 죽여도 앱은 안 죽는다.** `bootRun`을 중단해도 포크된 Java 프로세스가 8080을 계속 잡고 있어 다음 기동이 `Port 8080 was already in use`로 실패한다. 포트 점유 PID를 직접 `taskkill`해야 한다.

## 한계

- **로컬 PostgreSQL 18.0에서 쟀다.** 원 측정(RDS db.t3.micro, PG 16.13)과 머신도 메이저 버전도 다르다. 전후를 같은 세션에서 나란히 재 비교 자체는 유효하지만([stage0/local/index.md](../connection-pool-bottleneck/stage0/local/index.md)의 선례), **절대값을 원 측정과 직접 비교하면 안 된다.** 버퍼 345 일치로 기준선 재현은 확인했다.
- **`ddl-auto=validate` 운영 DB에는 인덱스가 반영되지 않는다.** 위 "코드만 배포하면 더 나빠진다" 참고. 이번 범위에서 수동 DDL을 만들지 않은 것이 그대로 배포 위험으로 남아 있다.
- **시드가 코스당 키워드 1개다.** 실제 운영은 `KeywordType` 21종이 5개 카테고리에 걸쳐 있어 코스당 약 5행이다. 그러면 셀 a/b의 스캔 대상이 5배 커져 **현행의 기울기는 과소평가돼 있고**, 셀 d는 영향이 없다(NL은 여전히 바깥 30행 × probe 1회). 즉 **운영 형태로 재면 개선 폭이 더 커진다.**
- **희소 테마는 평탄하지 않을 수 있다.** NL은 바깥을 조회수 순으로 훑으며 5건을 찾으므로, 테마가 희귀할수록(전체의 0.1% 등) 훑는 행이 늘어난다. 시드는 7종 균등(1/7)이다. 플래너가 `keyword_type` 통계로 이를 추정해 희소하면 알아서 Hash로 전환하므로 안전하긴 하지만, **"테마 조회는 항상 평탄"이라고 말하면 안 된다.**
- **부하 테스트는 하지 않았다.** 쿼리 수준 개선이고 이 경로는 Redis 랭킹 캐시(8키, 부팅 시 웜업, TTL 30분) 뒤의 폴백이라, 스택 전체 부하로 확인할 값어치가 낮다고 판단했다(원 측정이 내린 것과 같은 판단).
- **`work_mem` 한계는 확인하지 않았다.** 해시 엔트리가 `work_mem × hash_mem_multiplier`(여기선 8MB, 약 26만 엔트리)를 넘으면 변경 전 쿼리는 해시를 포기하고 상관 SubPlan 재실행으로 되돌아가 선형이 아니라 수직으로 꺾인다. mood 균등 배분 기준 `upload_course` 약 180만 건 지점이라 이번 규모(최대 50,000)에서는 도달하지 않는다.

## 남은 것

- **운영 DDL 반영** — 위 `CREATE INDEX CONCURRENTLY`. 배포의 **선행 조건**이다.
- **`validate`가 인덱스 누락을 못 잡는 구조적 공백** — 이번 인덱스만의 문제가 아니다. `UploadCourse`·`Place`·`DaySchedule`·`PlaceImage`의 기존 인덱스 4개도 같은 상태다.
- **`findAllByKeywordsOrderByViewCountDesc`** — [UploadCourseServiceImpl.java:242](../../../src/main/java/backend/yourtrip/domain/uploadcourse/service/UploadCourseServiceImpl.java)의 검색 경로. 페이징도 캐싱도 인덱스 최적화도 없어 규모가 커지면 `/popular`보다 먼저 무너진다.

## 참고 문서

- [scale-curve.md](../cache-effect-measurement/scale-curve.md) — 이 문제를 발견한 규모 곡선 측정
- [stage0/local/index.md](../connection-pool-bottleneck/stage0/local/index.md) — 인덱스 추가·검증의 선례와 `validate` 공백
- [popular-n-plus-one/README.md](../popular-n-plus-one/README.md) — 직전 단계
