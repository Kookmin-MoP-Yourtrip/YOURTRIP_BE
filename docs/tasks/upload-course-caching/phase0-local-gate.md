# Phase 0 로컬 게이트 — 벤치마크 토글 검증

> EC2 비용을 쓰기 전에 **토글이 실제로 작동하는지만** 확인하는 단계다. 처리량이나 포화 VU는 여기서 판단하지 않는다.
>
> **결론만 먼저**: 게이트 **통과**. A1(캐싱 O, 트랜잭션 분리 전)에서 **요청당 SQL 0건 / 커넥션 대여 정확히 1.000회**가 재현됐다 — 가설 H2가 말한 "일은 하나도 안 하면서 커넥션만 점유"다. 세 arm의 응답 본문도 동일해 arm 간 비교가 성립한다.
>
> 다만 **A0의 요청당 SQL이 계획 예측(popular 2 / detail 3)이 아니라 8 / 4로 나왔고**, 그 초과분이 기존 코드의 N+1이었다(아래 "발견 — 미스 경로의 N+1").

## 측정 환경

| | 값 |
|---|---|
| 실행 위치 | 로컬 개발 머신 (Windows) |
| DB | PostgreSQL 18.0, `localhost:5434` (psql 17.4 클라이언트) |
| Redis | `redis:7-alpine` 컨테이너, `localhost:6479`, `--maxmemory 256mb --maxmemory-policy allkeys-lru` |
| 프로필 | **`prod`** (세 arm 공통 — `local`이면 SQL 전량 로깅이 A0에만 비용을 얹는다) |
| 시드 | `seed-benchmark.sql` + `seed-popular.sql` (upload_course 3,000건) |
| 요청 수 | arm·엔드포인트당 **200회** (순차) |
| 대상 | `GET /api/upload-courses/popular`, `GET /api/upload-courses/1` |
| 스크립트 | `scripts/loadtest/phase0-gate.sh` — 측정 후 토글과 함께 제거했다([README.md](README.md#측정-장치는-측정이-끝난-뒤-제거했다)) |

**arm 구성** — 같은 JAR, 프로퍼티만 교체:

| arm | `upload-course-cache` | `upload-course-tx` | 의미 |
|---|---|---|---|
| A0 | `disabled` | `wrapped` | 캐싱 도입 이전 |
| A1 | `enabled` | `wrapped` | 캐싱 O, 트랜잭션 분리 이전 |
| A2 | `enabled` | `separated` | 현재 운영 (기본값) |

매 arm마다 앱 재기동 → 재시딩(`DB_DDL_AUTO=create`) → `FLUSHALL` → 워밍(A1·A2만) 순서를 스크립트로 고정했다.

---

## 결과

| arm | endpoint | 요청 | SQL | **SQL/req** | 커넥션 대여 | **대여/req** |
|---|---|---|---|---|---|---|
| **A0** | popular | 200 | 1,600 | **8.000** | 200 | **1.000** |
| **A0** | detail | 200 | 800 | **4.000** | 200 | **1.000** |
| **A1** | popular | 200 | 0 | **0.000** | 200 | **1.000** |
| **A1** | detail | 200 | 0 | **0.000** | 200 | **1.000** |
| **A2** | popular | 200 | 0 | **0.000** | 0 | **0.000** |
| **A2** | detail | 200 | 0 | **0.000** | 0 | **0.000** |

비율이 전부 정수로 딱 떨어진다 — 200회 구간에 스케줄러 틱이나 잔여 워밍이 섞이지 않았다는 뜻이다.

### 판정 1 — H2 재현 (이 게이트의 핵심)

**A1은 SQL을 한 건도 실행하지 않는데 요청마다 정확히 1회 커넥션을 대여한다.** 캐시가 100% 히트해 DB에서 읽을 것이 없는데도, 메서드 전체를 감싼 `@Transactional(readOnly = true)`이 트랜잭션 begin 시점에 물리 커넥션을 잡기 때문이다(`provider_disables_autocommit` 미설정 + `Connection.setReadOnly(true)`).

`TxWrappedUploadCourseReader`가 분리 이전 상태를 제대로 흉내 내고 있음이 확인됐다. **여기서 대여가 0으로 나왔다면 래퍼가 트랜잭션을 열지 않는 것이므로 EC2로 넘어가면 안 됐다.**

### 판정 2 — A1→A2에서 대여가 사라진다

트랜잭션 경계를 Reader로 좁히자 대여가 1.000 → 0.000이 됐다. 캐시 히트 경로가 풀을 아예 건드리지 않는다.

### 판정 3 — 응답 본문이 세 arm에서 동일

`/popular`, `/popular?theme=FOOD`, `/{id}` 세 응답을 arm별로 저장해 diff한 결과 전부 일치. arm 간 비교의 전제가 성립한다.

---

## 발견 — 미스 경로의 N+1

A0의 SQL이 계획 예측보다 많아, 요청 1건의 SQL을 그대로 뽑아 확인했다.

**`GET /popular` 1건 = 8 문장**

| # | 문장 | 출처 |
|---|---|---|
| 1 | `select uc1_0.upload_course_id from upload_course ... exists(select 1 from course_keyword ...)` | `findPopularCourseIds` |
| 2 | `select distinct uc1_0.*, k1_0.* from upload_course ... left join course_keyword` | `findAllByIdInWithKeywords` |
| 3~7 | `select tc1_0.* from my_course where course_id = ?` **× 5** | **N+1** — `UploadCourse.travelCourse` LAZY |
| 8 | `select u1_0.* from users where user_id = ?` | `UploadCourse.user` LAZY |

**`GET /{id}` 1건 = 4 문장**

| # | 문장 | 출처 |
|---|---|---|
| 1 | `select uc1_0.*, k1_0.* from upload_course ... left join course_keyword` | `findWithTravelCourseAndKeywords` |
| 2 | `select u1_0.* from users where user_id = ?` | `UploadCourse.user` LAZY |
| 3 | `select ds1_0.*, p1_0.* from day_schedule ... left join place` | `findDaySchedulesWithPlaces` |
| 4 | `select pi1_0.* from place_image where place_id in (...)` | `@Fetch(SUBSELECT)` |

`findAllByIdInWithKeywords`는 `keywords`만 `LEFT JOIN FETCH`하고 `travelCourse`는 LAZY로 남겨둔다. 그런데 `UploadCourseMapper.toCourseListItemCacheItem`이 코스별로 `getTravelCourse()`를 건드려, **top5 = 코스 5건마다 `my_course` 조회가 1회씩 추가로 나간다.**

**이건 이번 작업이 만든 것이 아니라 기존 코드에 원래 있던 것이다.** 지금까지 드러나지 않은 이유는 `generate_statistics`가 꺼져 있어 요청당 SQL 수를 아무도 세지 않았기 때문이다(이번에 상시 활성화하면서 보이게 됐다).

### 측정에 미치는 영향

- **히트율 역산의 상수가 바뀐다.** 계획은 "미스 1건당 SQL = popular 2 / detail 3"을 전제로 `미스율 = 요청당 SQL ÷ 미스당 SQL`을 쓰기로 했는데, 실측값은 **popular 8 / detail 4**다. 이 값으로 갱신한다.
- **A0가 더 무거워 보이는 것은 편향이 아니라 실제 동작이다.** A0는 매 요청이 미스이므로 이 N+1을 전부 지불한다. 캐싱은 그 비용을 없앤 게 아니라 **가리고 있었다** — 콜드 스타트/TTL 만료 시에는 지금도 그대로 발생한다.
- **P3(콜드 스탬피드)에서 특히 크게 작용할 것으로 예상된다.** 랭킹 미스 1건이 8 문장을 유발한다.

수정하지 않고 남겨둔다. 이번 측정의 통제 변수는 "DB 쿼리는 현재 형태 그대로"이고, 지금 고치면 A0가 재현하는 대상이 달라진다. 개선안은 측정 종료 후 별건으로 다룬다.

---

## 한계

- **로컬 수치로 처리량·포화 VU를 판단하지 않는다.** 앱·DB·Redis가 한 머신에 있어 커넥션 왕복 비용이 사실상 없고, 그래서 `pending`이 쌓이지 않는다. `popular-tx-separation`에서 같은 변경이 로컬 +4.9% / EC2 +31~40%로 갈렸던 이유가 이것이다.
- **순차 요청 200회라 동시성이 없다.** 커넥션 경합·대기줄은 이 단계에서 관측 대상이 아니다.
- 각 조건 1회 측정이다. 다만 비율이 정수로 떨어져 반복의 필요가 낮다고 판단했다.

## 다음 단계

[README.md](README.md)의 Phase 1(EC2 인기 코스)로 진행한다. 그 전에 EC2 App 인스턴스의 `/opt/app/.env`에 `SPRING_PROFILES_ACTIVE=prod`가 있는지 확인해야 한다 — user-data 템플릿에는 들어갔지만 `user_data`는 최초 부팅 1회만 실행되므로 이미 떠 있는 인스턴스에는 반영되지 않았다.
