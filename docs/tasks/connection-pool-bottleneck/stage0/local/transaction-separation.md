# 0단계(트랜잭션 분리) 로컬 실측 결과

> [TASK-PRESIGN-BOTTLENECK-FIX.md](../../TASK-PRESIGN-BOTTLENECK-FIX.md)의 0단계(서명 호출을 트랜잭션 경계 밖으로 분리, 이슈 #67)를 로컬 개발 머신에서 구현·재검증한 기록이다. 여기서 발견한 "mycourse가 개선되지 않은 진짜 원인"이 다음 실측([인덱스 추가 결과](index.md))으로 이어진다.

## 0단계 구현 결과 (실측 완료)

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

**이건 0단계·OSIV·서명 CPU와는 완전히 별개의, 더 근본적이고 더 저비용으로 고칠 수 있는 문제다.** `place.day_schedule_id`, `place_image.place_id`(그리고 uploadcourse 쪽에서 같은 패턴을 쓰는 `day_schedule.course_id`)에 인덱스만 추가해도 해결될 가능성이 높다 — **다만 인덱스는 아직 추가하지 않았고, 추가 후 재검증도 하지 않았다.** 이번에 한 건 원인 규명까지이고, 수정과 재측정은 다음 작업으로 남긴다(→ [인덱스 추가 결과](index.md)).

**한계**: 각 k6 run은 반복 없이 1회씩만 측정했다. 공유 개발 머신에서 build/재기동/재시딩을 반복하며 얻은 수치라 run 간 배경 부하 차이가 섞여 있을 수 있고, "정확히 몇 % 악화"까지는 신뢰 구간을 못 잡는다. 다만 TPS·p95·`acquire_seconds` 세 지표가 전부 같은 방향(악화)으로 일관되게 움직였다는 점, 그리고 그 원인을 인덱스 누락이라는 구체적 쿼리 계획으로 재현 가능하게 확인했다는 점에서 "0단계+OSIV 끄기가 mycourse를 개선하지 못했다"는 결론 자체는 노이즈로 설명하기 어렵다. `EXPLAIN ANALYZE`는 부하 없는 단독 실행 1회 기준이라, 실제 VU200 동시 부하 상황에서의 쿼리 실행시간(서버 사이드)은 여전히 직접 측정하지 못했다 — `pg_stat_statements`나 슬로우 쿼리 로그를 켜야 확인 가능하다.

**시사점**: mycourse의 진짜 병목은 "트랜잭션이 길다"도 "CPU 경합"도 아니라 **인덱스 누락으로 인한 대형 테이블 전체 스캔**이었다. 0단계(트랜잭션 분리)는 이 문제를 풀 수 없는 종류의 병목을 잡으려 한 것이었다 — 트랜잭션 경계를 아무리 좁혀도 그 안의 쿼리 자체가 느리면 소용이 없다. 인덱스 추가가 이번에 발견된 것 중 가장 저비용·고효과 후보이고, 이게 확인되면 캐시 도입(1단계/uploadcourse식)이나 풀 크기 재검토(4단계)의 필요성도 재평가해야 한다 — 인덱스만으로 VU200까지 버틴다면 그 이상의 구조 변경은 불필요할 수 있다.

**부수적으로 함께 반영한 변경**: `application.yml`에 `spring.jpa.open-in-view: false`를 추가했다 — 0단계가 실제로 의미를 가지려면 필수였던 설정이라 이번 범위에 포함했다. 이 변경이 코드베이스 다른 곳(컨트롤러/뷰 렌더링 중 지연 로딩에 의존하는 곳)에 영향을 줄 수 있어, 전체 테스트(`./gradlew test`)를 통과시켰지만 목(mock) 기반 단위 테스트라 실제 Hibernate 세션 경계 문제는 검증 범위 밖이었다는 점은 유의해야 한다.

## 참고 문서

- [TASK-PRESIGN-BOTTLENECK-FIX.md](../../TASK-PRESIGN-BOTTLENECK-FIX.md) — 이 실측이 속한 단계별 계획 문서
- [index.md](index.md) — 여기서 규명한 인덱스 누락 문제를 실제로 고친 후속 실측
- [TASK-PRESIGN-BOTTLENECK.md](../../TASK-PRESIGN-BOTTLENECK.md) — 원인 규명(이 실험의 근거)
