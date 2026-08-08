# TASK-PRESIGN-BOTTLENECK-FIX. 커넥션 풀 병목 해소 계획

> [TASK-PRESIGN-BOTTLENECK.md](TASK-PRESIGN-BOTTLENECK.md)가 원인을 규명한 문제("서명이 `@Transactional` 안에서 실행돼 HikariCP 커넥션을 초 단위로 점유하고, 동시 유저 20명 근처에서 이미 구조적으로 포화된다")에 대한 해결 계획이다. 단계별 우선순위와 각 단계의 근거·트레이드오프를 정리한 문서다. **0단계는 구현·재검증까지 완료했다** — 결과는 해당 절 참고. 1단계 이후는 아직 미착수.

## 배경 요약

- 상세 조회 API(`getPlaceListByDay`, `getDetail`)가 캐시 조회 + CloudFront/S3 이미지 URL 서명을 하나의 `@Transactional(readOnly = true)` 메서드 안에서 처리한다.
- 서명이 끝나야 메서드가 리턴되고, 그래야 HikariCP 커넥션(기본 풀 10개)이 반납된다 — DB 쿼리가 0건(캐시 히트)이어도 마찬가지다.
- 실측 결과 이 시스템은 **동시 유저 20명 근처에서 이미 포화**되고(`hikaricp_connections_active`가 풀 크기 10에 도달, `pending`이 쌓이기 시작), 그 이후 VU를 200까지 늘려도 처리량과 CPU 사용률은 거의 그대로다 — 병목은 CPU가 아니라 커넥션 풀 경합이다.
- 관련 이슈: [#67 perf: 캐시 조회와 CloudFront 서명 로직을 트랜잭션 경계 밖으로 분리](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/67) — 아래 0단계에 해당한다.

## 단계별 계획

### 0단계 — 서명 호출을 트랜잭션 경계 밖으로 분리 (선행 필수, 이슈 #67)

**무엇을**: 캐시 조회는 트랜잭션 시작 전으로, DB 읽기(캐시 미스 시)는 DTO 변환까지만 담당하는 짧은 트랜잭션으로, 서명은 트랜잭션 밖으로 각각 분리한다.

**왜 먼저**: 이걸 안 하면 아래 어떤 단계를 적용해도 "서명 1건이라도 남아있으면 그 1건이 여전히 커넥션을 오래 쥔다"는 구조적 결함이 남는다. 가장 저비용으로 가장 먼저 처리해야 하는 전제조건이다.

**구현 시 주의점**:
- Spring self-invocation 문제 — 같은 클래스 안에서 `this.트랜잭션메서드()` 호출은 `@Transactional`이 무시된다. 별도 협력 빈으로 분리하거나 `TransactionTemplate`으로 블록 단위 트랜잭션을 쓴다.
- mycourse의 `cloudFrontSigningExecutor` 병렬 서명 코드는 그대로 유지하되, 호출 위치만 트랜잭션 밖으로 옮긴다.

**검증**: `scripts/k6/detail-ramping.js` + Prometheus range query로 knee 시계열을 재측정한다. 목표는 "VU 20 근처에서 `pending`이 나타나던 지점"이 훨씬 뒤로 밀리는 것(이상적으로는 HikariCP 기본 풀 10개로도 VU 수백 단위까지 버텨야 한다 — 순수 DB 읽기는 밀리초 단위라 커넥션 회전이 훨씬 빨라지기 때문).

#### 0단계 구현 결과 (실측 완료)

**구현**: 이슈 #67에 대응해 `MyCourseServiceImpl.getPlaceListByDay`, `UploadCourseServiceImpl.getDetail` 두 메서드에서 `@Transactional`을 제거하고, DB 조회(존재·소유권 확인 포함)만 담당하는 별도 협력 빈 `MyCourseDetailReader`/`UploadCourseDetailReader`를 신설해 그쪽에 짧은 `@Transactional(readOnly = true)`을 옮겼다(self-invocation 문제로 같은 클래스 안에서는 트랜잭션을 좁힐 수 없어 별도 빈으로 분리 — `TransactionTemplate`은 채택하지 않음). 캐시 조회·서명·URL 조립은 트랜잭션 밖에서 실행된다. 적용 범위는 실측된 이 두 API로 한정했다(목록 API·`addPlaceImage` 등은 검증되지 않아 제외).

**재검증 방법**: `git stash`로 0단계 적용 전/후 코드를 같은 워크트리에서 전환해가며, 매번 DB 재시딩(`scripts/sql/seed-benchmark.sql`) + Redis flush 후 `scripts/k6/detail-ramping.js`(VU 1→200, pool 모드)를 mycourse/uploadcourse 각각 재실행하고 Prometheus range query로 `hikaricp_connections_pending`/`active`를 확인했다.

**결과 1 — uploadcourse: 뚜렷한 개선**

| | TPS | p95 | `pending` 최대 | `active` 최대 |
|---|---|---|---|---|
| 0단계 적용 전 | 1,521/s | 104ms | 181 | 10 |
| 0단계 적용 후 | 2,054/s | 68ms | **0** | 1 |

uploadcourse는 캐시 히트 경로가 이제 DB 커넥션을 아예 획득하지 않는다. `pending`이 0으로 완전히 사라진 게 그 직접 증거다. TASK-PRESIGN-BOTTLENECK.md 직접 증거 3(SQL 0건인데 pending 급증)이 지목한 경로가 실측으로도 막혔다.

**결과 2 — mycourse: 코드는 맞았지만 `spring.jpa.open-in-view`가 효과를 무력화하고 있었다**

1차 재측정에서 mycourse는 `pending` 최대 186→179로 사실상 무변화였다. 원인을 앱 시작 로그에서 확인: `spring.jpa.open-in-view`가 `application.yml`에 명시돼 있지 않아 Spring Boot 기본값(`true`)으로 켜져 있었다.

```
spring.jpa.open-in-view is enabled by default. Therefore, database queries may be performed during view rendering.
```

OSIV가 켜져 있으면 Hibernate 세션(과 그 밑의 JDBC 커넥션)이 `@Transactional` 경계와 무관하게 **HTTP 요청 시작부터 끝까지** 유지된다. 그래서 0단계로 트랜잭션 경계를 아무리 좁혀도, 요청 자체는 컨트롤러 진입 시점에 커넥션을 물고 응답이 완성될 때까지(=병렬 서명이 끝날 때까지) 반납하지 않았다. uploadcourse가 개선된 건 캐시 히트 경로가 JPA를 아예 안 건드려 OSIV가 커넥션을 열 일 자체가 없었기 때문이고(이번 pool 모드 테스트가 캐시가 빨리 데워져 히트 위주였던 우연), 캐시 미스였다면 uploadcourse도 같은 문제를 겪었을 것이다.

`application.yml`에 `spring.jpa.open-in-view: false`를 추가하고 재기동하자, 이번엔 두 API 모두 500 에러(`org.hibernate.LazyInitializationException: ... Place.placeImages: could not initialize proxy - no Session`)를 냈다. `Place.placeImages`는 `@Fetch(FetchMode.SUBSELECT)`(지연 로딩)인데, 그동안 OSIV가 세션을 계속 열어둔 덕에 트랜잭션 밖에서의 접근이 우연히 성공하고 있었을 뿐이었다 — OSIV를 끄자 원래도 있었던 이 의존성이 드러난 것이다. `MyCourseDetailReader`/`UploadCourseDetailReader`의 트랜잭션이 끝나기 전에 `daySchedule.getPlaces().forEach(place -> place.getPlaceImages().size())`로 강제 초기화해 해결했다.

**결과 3 — mycourse 최종 재측정: 에러는 해결됐지만 근본 병목은 그대로였고, 일부 지표는 오히려 악화됐다**

| | TPS | p95 | `pending` 최대 | `active` 최대 | `acquire_seconds` 최대 | 포화 시작 VU |
|---|---|---|---|---|---|---|
| 0단계 적용 전 (OSIV 켜짐, baseline) | 265/s | 644ms | 186 | 10 | 1.451s | **VU 20** |
| 0단계 적용 후, OSIV 끄기 전 | 283/s | 615ms | 179 | 10 | (미측정) | VU 20 |
| 0단계 + OSIV 끄기 + lazy 수정 (최종) | 245/s | 728ms | 177 | 10 | **2.697s** | **VU 20** |

baseline 대비 최종 상태를 정확한 증감률로 보면: **TPS -7.5%, p95 +13%, `acquire_seconds` 최대값 +86%(1.451s → 2.697s).** "비슷하거나 약간 나빴다"는 이전 서술은 부정확한 완곡화였다 — 정확히는 세 지표 모두 같은 방향(악화)으로 움직였다. `pending`이 0을 넘기 시작하는 VU(포화 시작점)도 0단계 적용 전후로 **VU 20에서 전혀 변하지 않았다** — `scripts/k6/detail-ramping.js`의 VU 단계 경계(60/60/**60→VU20**/90/90/90s)와 Prometheus range query로 "`pending`이 처음 0을 넘는 시점"을 5개 run 전부에서 특정해 직접 비교한 결과다. `pending`은 대기 중인 스레드 "개수"만 보여주는 간접 신호이고, `acquire_seconds`(커넥션을 실제로 기다린 시간 자체)가 VU200 구간 내내 멈추지 않고 계속 상승하는 패턴(baseline·최종 둘 다 동일한 모양)까지 확인했으므로, 이건 일시적 노이즈가 아니라 재현 가능한 포화 패턴이다.

**왜 트랜잭션을 짧게 만들었는데도 포화점이 안 움직였는가 — CPU 포화가 원인이 아니다.** 처음엔 "서명 스레드가 CPU를 다 써서 PostgreSQL이 밀린다"는 가설을 세웠으나, 정작 문제가 된 최종 run 구간의 `process_cpu_usage`(JVM 프로세스 전용)를 뒤늦게 확인한 결과 최대 0.346으로 — baseline(0.353)·1차 재측정(0.354)과 거의 동일했다. **JVM이 전혀 CPU 포화 상태가 아니었으므로 이 가설은 기각한다.**

대신 더 단순하고 직접적인 설명이 있다: **HikariCP 풀 크기(10)와 부하 동시성(VU 200)의 산술적 관계 그 자체**다. mycourse는 캐시가 없어 요청마다 반드시 DB 커넥션이 필요한데, VU 200은 풀 크기의 20배다. 이 20:1 초과수요 상황에서는, 트랜잭션 하나하나가 아무리 짧아도(0초에 가깝지 않은 한) 대기가 발생한다 — CPU 사용률과 무관하게 순수 자원 용량 문제다. uploadcourse가 0단계만으로 `pending`=0까지 간 건, 캐시 히트 경로가 이 20:1 경쟁 자체에 참여하지 않기(=DB 커넥션을 아예 안 씀) 때문이지 트랜잭션이 "더 짧아서"가 아니다.

다만 이걸로 전부 설명되진 않는다 — `MyCourseDetailReader`가 하는 일(`existsById` 2회 + fetch-join 1회 + `placeImages` subselect 1회)이 정말 수 ms 안에 끝난다면, 20:1 경쟁이 있어도 대기 시간이 초 단위까지 쌓이긴 어렵다. `acquire_seconds`(baseline 1.451s → 최종 2.697s)를 역산하면 부하 상황에서 트랜잭션당 실제 점유 시간이 수십~100ms대였을 것으로 추정되는데, "왜 이 짧은 쿼리들이 그렇게 오래 걸렸는가"는 아래에서 실제로 규명했다.

**진짜 원인 — FK 컬럼에 인덱스가 없어 매 요청마다 대형 테이블을 전체 스캔한다.** `place`/`place_image`/`day_schedule` 엔티티 어디에도 `@Index`가 없다. PostgreSQL은 FK 제약을 걸어도 참조하는 쪽 컬럼에 자동으로 인덱스를 만들어주지 않는데, 이 엔티티들이 딱 그 상태였다. `\d place`/`\d place_image`/`\d day_schedule`로 직접 확인한 결과 세 테이블 모두 PK 인덱스(`*_pkey`) 하나뿐이고, `MyCourseDetailReader`가 조인에 쓰는 `place.day_schedule_id`, `place_image.place_id`, `day_schedule.course_id`에는 인덱스가 없었다.

시드 데이터(`scripts/sql/seed-benchmark.sql`)를 그대로 두고 실제 쿼리를 `EXPLAIN ANALYZE`로 직접 실행해 확인했다(부하 없이 단독 실행 기준):

```
-- findByIdWithPlaces 등가 쿼리
Seq Scan on place p  (actual time=2.619..8.624 rows=5)
  Filter: (day_schedule_id = 1500)
  Rows Removed by Filter: 29995        -- place 테이블 3만 행 전체 스캔
Execution Time: 8.877 ms

-- placeImages SUBSELECT 등가 쿼리
Seq Scan on place_image  (actual time=0.062..8.756 rows=60000)
Execution Time: 15.112 ms              -- place_image 테이블 6만 행 전체 스캔
```

부하가 전혀 없는 단독 실행 기준으로도 이 두 쿼리만 합쳐 **24ms**다(참고: `place`/`place_image`는 mycourse 3,000코스 + uploadcourse 히든카피 3,000코스가 같은 테이블을 공유해 각각 3만/6만 행). VU 200에서 200개 동시 요청이 각자 이 전체 스캔을 동시에 돌리면, 버퍼캐시·CPU를 놓고 경합하며 개별 스캔 시간이 눈덩이처럼 불어난다 — `acquire_seconds`가 VU가 오를수록 계속 상승해 VU200에서 1.4~2.7초까지 간 패턴과 정확히 맞아떨어진다.

**이건 0단계·OSIV·서명 CPU와는 완전히 별개의, 더 근본적이고 더 저비용으로 고칠 수 있는 문제다.** `place.day_schedule_id`, `place_image.place_id`(그리고 uploadcourse 쪽에서 같은 패턴을 쓰는 `day_schedule.course_id`)에 인덱스만 추가해도 해결될 가능성이 높다 — **다만 인덱스는 아직 추가하지 않았고, 추가 후 재검증도 하지 않았다.** 이번에 한 건 원인 규명까지이고, 수정과 재측정은 다음 작업으로 남긴다.

**한계**: 각 k6 run은 반복 없이 1회씩만 측정했다. 공유 개발 머신에서 build/재기동/재시딩을 반복하며 얻은 수치라 run 간 배경 부하 차이가 섞여 있을 수 있고, "정확히 몇 % 악화"까지는 신뢰 구간을 못 잡는다. 다만 TPS·p95·`acquire_seconds` 세 지표가 전부 같은 방향(악화)으로 일관되게 움직였다는 점, 그리고 그 원인을 인덱스 누락이라는 구체적 쿼리 계획으로 재현 가능하게 확인했다는 점에서 "0단계+OSIV 끄기가 mycourse를 개선하지 못했다"는 결론 자체는 노이즈로 설명하기 어렵다. `EXPLAIN ANALYZE`는 부하 없는 단독 실행 1회 기준이라, 실제 VU200 동시 부하 상황에서의 쿼리 실행시간(서버 사이드)은 여전히 직접 측정하지 못했다 — `pg_stat_statements`나 슬로우 쿼리 로그를 켜야 확인 가능하다.

**시사점**: mycourse의 진짜 병목은 "트랜잭션이 길다"도 "CPU 경합"도 아니라 **인덱스 누락으로 인한 대형 테이블 전체 스캔**이었다. 0단계(트랜잭션 분리)는 이 문제를 풀 수 없는 종류의 병목을 잡으려 한 것이었다 — 트랜잭션 경계를 아무리 좁혀도 그 안의 쿼리 자체가 느리면 소용이 없다. 인덱스 추가가 이번에 발견된 것 중 가장 저비용·고효과 후보이고, 이게 확인되면 캐시 도입(1단계/uploadcourse식)이나 풀 크기 재검토(4단계)의 필요성도 재평가해야 한다 — 인덱스만으로 VU200까지 버틴다면 그 이상의 구조 변경은 불필요할 수 있다.

**부수적으로 함께 반영한 변경**: `application.yml`에 `spring.jpa.open-in-view: false`를 추가했다 — 0단계가 실제로 의미를 가지려면 필수였던 설정이라 이번 범위에 포함했다. 이 변경이 코드베이스 다른 곳(컨트롤러/뷰 렌더링 중 지연 로딩에 의존하는 곳)에 영향을 줄 수 있어, 전체 테스트(`./gradlew test`)를 통과시켰지만 목(mock) 기반 단위 테스트라 실제 Hibernate 세션 경계 문제는 검증 범위 밖이었다는 점은 유의해야 한다.

---

### 1단계 — mycourse 이미지 접근을 CloudFront Signed URL에서 Signed Cookie로 전환

**무엇을**: 지금은 상세 조회 응답에 담긴 이미지 URL 하나하나(코스당 최대 수십 장)를 개별 서명한다. 대신 코스 열람 시점에 그 코스(또는 사용자) 범위에 대한 **Signed Cookie를 1회만 발급**하고, 이후 이미지 URL은 서명 없는 일반 CloudFront URL로 응답한다. 브라우저가 쿠키를 자동으로 실어 보내면 CloudFront 엣지에서 인가를 검증한다.

**왜**: [AWS 공식 문서](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-choosing-signed-urls-cookies.html)가 정확히 이 상황을 위한 기준을 제시한다 — "여러 개의 제한된 파일에 접근을 제공하려면 Signed Cookie를 쓰라." 이 전환은 서명 연산량 자체를 "요청당 이미지 수"에서 "요청(또는 세션)당 1회"로 줄인다 — 0단계가 "커넥션을 덜 오래 쥔다"는 개선이라면, 이건 "애초에 서명할 일 자체를 줄인다"는 더 근본적인 개선이다.

**트레이드오프 및 검토 필요 사항**:
- CloudFront Signed Cookie는 발급 후 즉시 취소가 어렵다(TTL 만료를 기다려야 함) — 코스 소유자가 코스를 비공개로 전환하거나 이미지를 삭제했을 때의 접근 제어를 어떻게 가져갈지 별도 설계가 필요하다.
- 쿠키 범위(`Path`)를 어떻게 잡을지: 코스 단위(`private/{courseId}/*`)로 좁게 잡을지, 사용자 단위(`private/{userId}/*`)로 넓게 잡아 갱신 빈도를 줄일지 트레이드오프가 있다.
- 모바일 클라이언트(Android 앱, README 기준 이 프로젝트의 실제 FE)가 쿠키 기반 인증을 자연스럽게 다루는지 확인 필요 — 웹 브라우저와 달리 앱은 쿠키 저장소를 직접 관리해야 할 수 있다.

**검증**: 서명 마이크로벤치마크(`./gradlew benchmarkTest`)와 JFR CPU 프로파일(crypto 카테고리 샘플 비율)을 mycourse 상세 조회 부하 전후로 비교한다. 이미지 수와 무관하게 요청당 서명 비용이 상수에 가까워지는지 확인한다.

---

### 2단계(조건부) — Signed URL을 만료시간보다 짧은 TTL로 캐싱

**적용 조건**: 1단계(Signed Cookie 전환)가 당장 부담스럽거나(클라이언트 마이그레이션 비용, 접근 제어 재설계 범위) 보류될 경우의 대안, 또는 1단계 전까지의 과도기적 완화책.

**무엇을**: [CACHING-ROADMAP.md 설계 원칙 1](../CACHING-ROADMAP.md)의 "presigned URL은 캐싱하지 않는다"를 재검토한다. 서명 URL 자체(S3 key가 아니라 완성된 URL)를 Redis에 만료시간보다 충분히 짧은 TTL로 캐싱한다(예: 60분 유효 → 10분 캐시).

**근거**: [Ben Nadel의 케이스 스터디](https://www.bennadel.com/blog/3685-performance-case-study-caching-cryptographically-signed-urls-in-redis-in-lucee-5-2-9-40.htm)가 동일한 패턴으로 p95 URL 생성 시간을 1/3로 줄인 실측 사례를 보고한다.

**트레이드오프**: 1단계보다 효과가 작다(캐시 미스마다 여전히 이미지 수만큼 서명해야 한다). "만료된 URL이 나갈 위험"은 TTL을 만료시간보다 충분히 짧게 잡아 관리해야 한다.

---

### 3단계 — 서명/DB 작업을 Bulkhead 패턴으로 정식 격리

**무엇을**: 0단계가 "트랜잭션 밖으로 뺀다"는 임기응변이라면, 이를 Michael Nygard(*Release It!*)가 정식화한 **Bulkhead 패턴**(서로 다른 성격의 작업을 별도 리소스 풀로 파티셔닝해 한쪽 지연이 다른 쪽으로 전염되지 않게 하는 것)으로 구조화한다. Spring 생태계에서는 Resilience4j `@Bulkhead(type = Bulkhead.Type.THREADPOOL)`가 표준 구현체다.

**현재 상태와의 연결**: `cloudFrontSigningExecutor`(12스레드 전용 풀)가 사실 이 패턴의 절반(서명 스레드풀 격리)은 이미 구현돼 있었다. 다만 그 앞단(HikariCP 풀)이 격리 안 돼 있어서 실제로는 자기 용량(12스레드, 동시성 200에서도 최대 10요청분만 도달)을 다 써본 적이 없었다([TASK-PRESIGN-BOTTLENECK.md의 "PR #61 재해석"](TASK-PRESIGN-BOTTLENECK.md) 참고). 0단계가 적용되면 이 실행자가 비로소 제 역할을 하게 된다.

**검증**: 0단계 적용 후 동시성 200 부하에서 `cloudFrontSigningExecutor`의 큐/활성 스레드 수가 실제로 12에 가깝게 올라가는지 확인한다(현재는 앞단 병목에 가려 도달한 적이 없다).

---

### 4단계(후순위, 신중하게) — 커넥션 풀/DB 계층 튜닝

**적용 시점**: 0~3단계를 적용하고 재측정한 뒤에도 여전히 트래픽 규모상 부족할 때만 검토한다. **지금 문제를 풀 크기로 덮는 용도로 먼저 쓰지 않는다.**

**근거**: [HikariCP 공식 위키](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)는 "작은 풀을 대기 스레드로 포화시켜라"는 원칙과 함께, 스레드 수가 코어 수를 넘으면 컨텍스트 스위칭 오버헤드로 오히려 느려질 수 있다고 경고한다. PostgreSQL은 커넥션 하나당 OS 프로세스 하나라서, [PostgreSQL 위키](https://wiki.postgresql.org/wiki/Number_Of_Database_Connections)에 따르면 커넥션 수를 무작정 늘리면 메모리·컨텍스트 스위칭 비용이 DB 서버를 직접 압박한다.

**대안**: 정말 대량 트래픽 규모로 간다면 PgBouncer(transaction mode) 도입을 검토할 수 있으나, [실무 경고](https://jpcamara.com/2023/04/12/pgbouncer-is-useful.html)가 뚜렷하다 — session-level advisory lock, named prepared statement, `LISTEN`, `CREATE INDEX CONCURRENTLY` 등이 조용히 깨질 수 있다. 도입 시 이 프로젝트가 해당 기능을 쓰는지 먼저 점검해야 한다.

**이번 범위에서는 코드를 변경하지 않는다** — 후속 검토 항목으로만 기록.

---

### 5단계 — 모니터링을 알람으로 승격

**무엇을**: `test/presigned-url-bottleneck`에서 만든 Grafana 대시보드(`Bottleneck Test` 폴더 › `Presign CPU Bottleneck`)의 `hikaricp_connections_pending`을 사후 분석용이 아니라 사전 경보용으로 전환한다 — 예: `hikaricp_connections_pending > 0`이 N초 이상 지속되면 알림.

**왜**: 이번 실험으로 이 지표가 "병목이 이미 시작됐다"는 가장 빠르고 명확한 신호라는 게 실측으로 확인됐다(직접 증거 4 — TPS/CPU가 눈에 띄게 나빠지기 전에 이미 `pending`이 먼저 반응한다).

## 실행 순서와 의존관계

```
0단계 (트랜잭션 분리) ─┬─→ 3단계 (Bulkhead 정식화, 0단계의 효과를 완성시킴)
                        │
                        └─→ 1단계 (Signed Cookie 전환) ─→ 5단계 (알람화)
                              │
                              └─(보류 시 대안)→ 2단계 (TTL 캐싱)

4단계는 0~3단계 재측정 후 필요성 재평가
```

**필수 경로**: 0 → 1 → 3 → 5, 2와 4는 조건부(각 단계 설명 참고).

## 공통 검증 방법

각 단계 적용 후 다음을 반복한다 — [TASK-PRESIGN-BOTTLENECK.md의 "재현 방법"](TASK-PRESIGN-BOTTLENECK.md)과 동일한 도구를 재사용한다.

1. `scripts/sql/seed-benchmark.sql`로 동일 규격 시드
2. `scripts/k6/detail-ramping.js`(VU 1→200)로 부하
3. Prometheus range query로 `hikaricp_connections_active`/`pending`/`process_cpu_usage`를 시간축으로 뽑아 knee 위치 비교
4. `SigningBenchmarkTest`(`./gradlew benchmarkTest`)로 요청당 서명 연산 횟수/비용 변화 확인

**목표 지표**: "VU 20 근처에서 `pending`이 나타나기 시작한다"는 현재 상태가, 각 단계 적용 후 어느 VU까지 밀려나는지를 정량적으로 비교해 단계별 효과를 분리 측정한다.

## 참고 문서

- [TASK-PRESIGN-BOTTLENECK.md](TASK-PRESIGN-BOTTLENECK.md) — 원인 규명(이 계획의 근거)
- [CACHING-ROADMAP.md](../CACHING-ROADMAP.md) — 2단계와 관련된 기존 캐싱 설계 원칙
- GitHub 이슈 [#67](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/67) — 0단계에 대응. 1/3/5단계는 착수 시점에 별도 이슈로 분리하는 것을 검토한다.
