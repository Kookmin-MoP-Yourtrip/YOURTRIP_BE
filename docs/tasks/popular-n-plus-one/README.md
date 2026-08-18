# 인기 코스 아이템 조회의 N+1 제거

> **이 작업의 위치** — 인기 코스·상세 조회 성능 개선 사슬의 **5단계**다.
>
> 1. [커넥션 풀 병목 발견](../connection-pool-bottleneck/PRESIGN-BOTTLENECK.md)
> 2. [Redis 캐싱 도입](../redis-caching/README.md)
> 3. [트랜잭션 분리 측정](../popular-tx-separation/README.md)
> 4. [캐싱 효과 측정](../cache-effect-measurement/README.md) — 여기서 이 N+1이 **발견**됐다
> 5. **N+1 제거** ([#85](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/85))

> **결론만 먼저**: `UploadCourse`의 두 to-one 연관에 `fetch = LAZY` 두 줄을 붙였다. 캐시 미스 1건당 SQL이 **8 → 2문장**(인기), **4 → 3문장**(상세)이 됐고, LIMIT이 없는 검색 목록은 **3,002 → 1문장**이 됐다. 리포지토리·서비스·매퍼는 한 줄도 고치지 않았다.

## 원인

[캐싱 효과 측정 Phase 0](../cache-effect-measurement/phase0-local-gate.md)에서 `GET /popular` 1건이 예상(2문장)이 아니라 8문장을 낸다는 것이 드러났다. 분해하면 랭킹 1 + `IN` 조회 1 + `my_course` 5 + `users` 1이다.

`UploadCourse.travelCourse`(`@OneToOne`)와 `user`(`@ManyToOne`)에 `fetch` 속성이 없어 **JPA 기본값인 EAGER**로 동작한다. 목록 쿼리는 `keywords`만 `LEFT JOIN FETCH`하므로, Hibernate가 fetch join되지 않은 이 두 연관을 **엔티티마다 세컨더리 select로 채운다.**

**아무도 그 데이터를 읽지 않는데 나간다.** 목록 매퍼(`toCourseListItemCacheItem`, `toListItemResponse`)는 두 연관 중 어느 것도 참조하지 않는다. 이것이 이 N+1의 성격을 규정한다 — 호출 코드를 아무리 읽어도 원인이 보이지 않고, 엔티티 선언에만 있다.

> Phase 0 문서는 이 원인을 한동안 "`travelCourse` LAZY 접근"이자 "매퍼가 `getTravelCourse()`를 건드려서"라고 적고 있었다. **둘 다 사실과 반대**였고, 이번 작업에서 정정했다. 실측 수치(8/4)는 측정 당시 빌드의 사실이므로 그대로 뒀다.

## 두 선택지와 결정

이슈는 두 방식을 열어뒀다 — 쿼리에 `LEFT JOIN FETCH`를 추가하는 좁은 수정과, 두 연관을 LAZY로 돌리는 근본 수정.

| | (a) JPQL에 `LEFT JOIN FETCH` 추가 | **(b) 두 연관 LAZY 전환** |
|---|---|---|
| 인기 코스 미스 | 8 → 2 | 8 → **2** |
| 상세 미스 | 4 → 4 (그대로) | 4 → **3** |
| 검색 목록(3,000 코스) | 3,002 → 그대로 | 3,002 → **1** |
| 내 업로드 코스 목록 | 그대로 | N+1 소멸 |
| 피드 목록(N건) | 그대로 | **−2N 문장** |
| 소유권 체크 | `users` select 1회 유지 | **0회** |
| 변경 파일 | 리포지토리 JPQL | **엔티티 2줄** |
| 앞으로 추가될 쿼리 | EAGER가 남아 자동으로 N+1을 뭄 | 기본이 안전, 필요한 곳만 JOIN FETCH |

**(b)를 골랐다. 결정적인 근거는 저장소 컨벤션이었다** — 전 엔티티의 to-one을 전수 조사하니 `Feed.user`, `Feed.uploadCourse`, `Comment.feed/user`, `FeedLike.user/feed`, `FeedMedia.feed`, `Hashtag.feed`, `TravelCourse.user`, `DaySchedule.course`, `Place.daySchedule`, `PlaceImage.place`, `CourseKeyword.uploadCourse` **13개가 전부 명시 LAZY**였다. 문제의 두 필드만 예외였다. (b)는 성능 개선이자 규약 위반 교정이고, 그래서 저장소 전역 규칙을 잠그는 테스트를 지금 당장 도입할 수 있다.

(a)는 증상만 덮는다. 게다가 `travelCourse`를 fetch join해도 매퍼가 쓰지 않으므로 **쓰지도 않을 컬럼을 한 문장에 몰아 넣는** 형태가 되어 전송량은 그대로 남는다.

## LAZY 전환의 함정 — 검토 결과

`open-in-view: false`인 이 저장소에서 EAGER를 LAZY로 바꾸는 것은 무해한 변경이 아니다. 다섯 가지를 확인했다.

### 1. 소유측 `@OneToOne` + nullable FK에서 LAZY 프록시가 실제로 동작하는가 → 동작한다

Hibernate가 LAZY 선언을 무시하고 즉시 조회하는 조건은 네 가지다. **전부 해당 없다.**

| 조건 | 이 케이스 |
|---|---|
| 역방향(`mappedBy`) `@OneToOne` — FK가 자기 행에 없어 null 여부를 알 수 없다 | `travelCourse`는 `@JoinColumn(name="course_id")`을 가진 **소유측**이라 FK 값이 곧 답이다 |
| 대상 클래스나 getter가 `final` | `TravelCourse`·`User` 모두 아니다 |
| `@NotFound` 지정 | 없다 |
| 인자 없는 생성자 접근 불가 | 둘 다 `@NoArgsConstructor(PROTECTED)` |

> `optional = false`는 **일부러 추가하지 않았다.** 소유측에서는 프록시 가능 여부를 바꾸지 않고 DDL nullability만 바꾸는데, 운영이 `ddl-auto: update`라 `course_id`에 NOT NULL을 거는 것은 이득 없이 기동 실패 위험만 늘린다.

### 2. `getUser().getId()`가 프록시를 초기화하는가 → 하지 않는다

소유권 체크 5곳(`UploadCourseServiceImpl`의 수정·삭제, `MyCourseServiceImpl.forkCourse`, `FeedServiceImpl`의 피드 생성·수정)은 전부 `getUser().getId()` 형태다. Hibernate의 `BasicLazyInitializer`가 식별자 getter 호출을 가로채 DB를 치지 않고 보유 중인 식별자를 반환하므로, **EAGER일 때 나가던 `users` select가 아예 사라진다.**

다만 이 최적화는 **관례에 의존하는 조용한 계약**이다. `@Id`가 필드에 있고(FIELD 접근), 그 필드명에 대응하는 관례적 getter(`getId()`, 반환 타입 일치)가 있어야 성립한다. Lombok `@Getter`를 떼거나 `@Id`를 getter로 옮기면 소리 없이 깨지고 5곳에 select가 부활한다 — `UploadCourseLazyProxyContractTest`가 이 계약을 지킨다.

### 3. 엔티티가 트랜잭션 밖으로 나가는 경로 → 회귀 없다

`UploadCourseServiceImpl.getDetail`은 `@Transactional`이 없고, `UploadCourseDetailReader.read()`의 짧은 트랜잭션이 **끝난 뒤** 매퍼가 `getTravelCourse()`와 `getKeywords()`를 읽는다. 이것이 터지지 않는 이유는 `findWithTravelCourseAndKeywords`의 JPQL에 fetch join이 들어 있기 때문이다 — **JOIN FETCH는 선언된 LAZY를 덮어써 그 자리에서 초기화한다.** 이미 LAZY인 `keywords`가 같은 경로에서 멀쩡히 동작하는 것이 그 증거다.

이 구조 자체는 위험하다. 안전성의 근거가 **다른 파일의 JPQL 문자열 안에만** 있어서, 그 문자열이 바뀌는 순간 런타임에야 터진다(PR #70에서 `Place.placeImages`가 정확히 이 방식으로 `LazyInitializationException`을 냈다). 이번 작업에서 그 원격 의존성을 테스트로 고정했다.

부수 효과로 **견고해진 지점**도 있다. 인기 목록은 이제 `travelCourse`를 아예 건드리지 않으므로, `TravelCourse`가 소프트 삭제된 코스 하나가 목록 전체를 500으로 만들던 경로가 사라진다.

### 4. `equals`/`instanceof`/직렬화 → 문제 없다

`User`·`TravelCourse` 어느 쪽도 `equals`/`hashCode`를 오버라이드하지 않아 프록시가 identity 비교로 처리된다. 엔티티를 `Map` 키로 쓰는 곳이 없고(`existingDayMap` 등은 전부 `getId()`가 키), 컨트롤러는 DTO만 반환하며, Redis 캐시와 이벤트도 record DTO만 담는다. Jackson이 프록시를 만나는 경로가 원천 차단돼 있다.

### 5. 프록시가 메서드 인자로 흘러가는 곳

`MyCourseServiceImpl.forkCourse`가 `copyMyCourseWithSchedule(uploadCourse.getTravelCourse(), ...)`로 넘긴다. `findWithTravelCourseById`가 fetch join하므로 실체 엔티티이고, 설령 프록시여도 `@Transactional` 안이라 초기화 1회로 끝난다.

## 실측

### 환경

| | 값 |
|---|---|
| 실행 위치 | 로컬 개발 머신 (Windows) |
| DB | PostgreSQL 18.0, `localhost:5434` |
| Redis | `redis:7-alpine`, `localhost:6479` |
| 프로필 | `prod` (`local`은 SQL 전량 로깅이 before arm에만 비용을 얹는다) |
| 시드 | `seed-benchmark.sql` + `seed-popular.sql` (upload_course 3,000건, **소유자 1명**) |
| 지표 | `/actuator/prometheus`의 `hibernate_statements_total{status="prepared"}` 증분 |

**EC2 부하테스트는 하지 않았다.** 이번에 바꾸는 것은 처리량이 아니라 **환경과 무관한 결정론적 문장 수**이고, arm 간 처리량 비교가 없다. 게다가 A0 토글은 캐싱 효과 측정 종료 후 제거돼 EC2 프로토콜 재현에는 그것을 되살리는 비용이 든다.

### 프로토콜

문장 수가 정수라 요청 1건이면 충분하다. `FLUSHALL`로 미스를 강제하고 요청 전후 증분을 읽는 것을 **5회 반복**해 동일값을 확인했다.

두 arm은 **같은 시드 위에서** 쟀다. before 측정 후 `DB_DDL_AUTO=none`으로 재기동해 시드를 보존했다(원래 프로토콜은 arm마다 재시딩하는데, 그러면 시드 자체가 통제 변수가 된다).

### 결과 — 요청당 SQL 문장 수

| 엔드포인트 | before | after | |
|---|---|---|---|
| `GET /api/upload-courses/popular` | **8** | **2** | 랭킹 1 + `IN` 조회 1만 남는다 |
| `GET /api/upload-courses/popular?theme=FOOD` | **8** | **2** | |
| `GET /api/upload-courses/{id}` | **4** | **3** | 쓰이지도 않던 `users` 조회가 사라진다 |
| `GET /api/upload-courses?sortType=NEW` | **3,002** | **1** | LIMIT이 없어 코스 수에 비례했다 |

5회 반복 전부 동일값이었다(편차 0).

**before의 8과 4는 [Phase 0 게이트](../cache-effect-measurement/phase0-local-gate.md)의 기록과 정확히 일치한다.** 다른 세션·다른 시점인데도 재현됐다는 뜻이고, 그래서 이 측정이 그 기록의 후속으로 읽힌다.

**검색 목록의 3,002가 이 작업의 진짜 크기다.** 인기 코스는 top5로 제한돼 8문장에 그쳤지만, 같은 원인이 LIMIT 없는 경로에서는 코스 수만큼 곱해지고 있었다. 이 경로는 캐싱 효과 측정의 대상이 아니라 아무도 재본 적이 없었다.

> **8문장은 바닥값이었다.** 시드가 전 코스를 단일 사용자가 소유해 `users` 조회가 1회로 접힌 값이다. 소유자를 5명으로 흩어놓은 H2 테스트에서는 같은 경로가 **12문장**으로 나온다(랭킹 1 + `IN` 1 + `my_course` 5 + `users` 5). 검색 목록의 3,002도 같은 이유로 바닥값이다 — 소유자가 흩어지면 6,001에 가까워진다.

### 응답 동등성

`/popular`, `/popular?theme=FOOD`, `/{id}`, `/?sortType=NEW` 네 응답을 arm별로 저장해 diff한 결과 **전부 일치**했다. 특히 `travelCourse`에서 오는 유일한 노출 필드인 상세 응답의 `startDate`/`endDate`가 정상이다.

### 소유권 체크 스모크

프록시 위에서 도는 것이 이번 변경의 유일한 행동 변화라 실제 스택에서 확인했다.

| 시나리오 | 결과 |
|---|---|
| 타인 코스 삭제 | `NOT_OWNED_UPLOAD_COURSE` (403) |
| 본인 코스 삭제 | 204 |
| 본인 코스 fork | `CANNOT_FORK_OWNED_COURSE` |
| 타인 코스 fork | 소유권 체크 통과 후 `getTravelCourse()` 역참조까지 진입 |

마지막 항목은 S3 복사 단계에서 실패하는데, 시드의 S3 키가 실제 버킷에 없기 때문이고 이 변경과 무관하다. **그 지점까지 도달했다는 것 자체가 소유권 체크와 `getTravelCourse()` 초기화가 모두 성공했다는 증거다.**

## 회귀를 막는 3층 테스트

이 N+1이 **아무도 모르게 오래 존재했다**는 사실이 설계의 출발점이다. 원인이 호출 코드가 아니라 엔티티 선언에 있어서 코드 리뷰로 잡히지 않고, `generate_statistics`가 꺼져 있던 동안에는 지표로도 보이지 않았다. 그래서 **선언·문장 수·프록시 계약** 세 층에서 각각 다른 회귀를 잡는다.

| 테스트 | 잡는 회귀 | 못 잡는 것 |
|---|---|---|
| `EntityFetchStrategyTest` | `fetch` 누락 또는 EAGER 복귀. `domain` 하위 **모든** 엔티티를 스캔해 전역 규칙으로 건다 | 선언만 본다. `mappedBy` 역방향 `@OneToOne`처럼 LAZY로 선언해도 Hibernate가 무시하는 매핑은 통과시킨다 |
| `UploadCourseSqlStatementCountTest` | 실제 발행 문장 수 증가 — 매퍼가 새 연관을 건드리거나, JPQL에서 fetch join이 빠지거나, 위의 "선언은 LAZY인데 실제로는 EAGER"인 경우 | 문장의 내용은 보지 않는다 |
| `UploadCourseLazyProxyContractTest` | 안전성의 **논증 자체**. JOIN FETCH가 트랜잭션 밖 접근을 보장하는지, `getId()`가 프록시를 초기화하지 않는지 | |

### 문장 수 테스트가 무의미해지지 않게 하는 3가지

`@DataJpaTest` 슬라이스로 돌린다(Redis·S3·CloudFront·Gemini 빈을 아예 로딩하지 않는다). 세는 값은 `Statistics.getPrepareStatementCount()`인데, **Micrometer가 `hibernate_statements_total{status="prepared"}`로 내보내는 바로 그 카운터**다 — 테스트가 세는 수와 부하테스트 대시보드가 세는 수가 같다.

1. **소유자를 코스마다 다르게 흩어놓는다.** 부하테스트 시드가 단일 소유자라 `users` 조회가 1회로 접혀 N+1의 실제 크기가 8로 과소평가됐던 바로 그 함정이다. 흩어놓아야 EAGER 회귀가 코스 수만큼 곱해져 드러난다 — 실제로 수정 전 이 테스트는 `2`가 아니라 **`12`**를 보고했다.
2. **측정 직전 `em.flush(); em.clear();`** 시드 INSERT가 1차 캐시에 남아 있으면 이후 조회가 세컨더리 select를 아예 내지 않아 테스트가 조용히 통과한다.
3. **시딩 INSERT를 센 뒤 `statistics.clear()`.**

### 수정 전 실패값 — 테스트가 실제로 무언가를 잡는다는 증거

| 테스트 | 수정 전 | 수정 후 |
|---|---|---|
| 인기 코스 미스 경로 | 12 | **2** |
| 상세 미스 경로 | 4 | **3** |
| 검색 목록 | 11 | **1** |
| 내 업로드 코스 목록 | 3 | **1** |
| `getUser()` 프록시 여부 | 초기화됨(EAGER) | **프록시 유지** |
| 전역 fetch 규칙 | `UploadCourse.travelCourse`·`user` 2건 위반 | **위반 0** |

전역 스캔이 **정확히 그 두 필드만** 지목한 것이, "저장소의 나머지 to-one 13개는 이미 LAZY"라는 조사 결과의 실행 가능한 증명이다.

## 곁다리로 드러난 것 — 테스트 H2에 `day_schedule` 테이블이 없었다

문장 수 테스트를 처음 돌렸을 때 `Table "day_schedule" not found`가 났다. 원인은 이 작업과 무관한 기존 결함이다.

`DaySchedule.day`의 컬럼명 `day`가 **H2 2.x의 예약어**라 `create table day_schedule`이 문법 오류로 실패한다. PostgreSQL에서 `day`는 예약어가 아니라 운영에는 없는 문제이고, `MODE=PostgreSQL`도 키워드 목록까지는 맞춰주지 않는다.

**더 나쁜 것은 실패 방식이다.** Hibernate는 DDL 실패를 `WARN`으로만 남기고 기동을 계속하므로, 테이블이 없는 채로 앱이 뜨고 **그 테이블을 처음 쓰는 순간에야** 터진다. 기존 테스트가 전부 Mockito 단위테스트이거나 `@MockBean` 기반 E2E라 이 테이블을 건드리지 않아 지금까지 드러나지 않았다.

H2 URL에 `NON_KEYWORDS=DAY`를 추가해 해결했다.

## 남은 것

- **락이 지키는 범위는 여전히 좁다.** 미스 1건이 8문장에서 2문장으로 줄었으므로 분산 락이 지키는 비율은 "8문장 중 1문장"에서 "2문장 중 1문장"이 됐지만, 락이 랭킹 쿼리에만 걸려 있다는 **구조는 그대로다**([scenarios.md](../cache-effect-measurement/scenarios.md)). EC2에서 재측정하지는 않았다.
- **`getAllForSearch`에는 페이지네이션이 없다.** 이번 수정으로 3,002문장이 1문장이 됐지만, 그 1문장이 여전히 코스 3,000건을 전부 메모리로 올린다. 별건이다.
