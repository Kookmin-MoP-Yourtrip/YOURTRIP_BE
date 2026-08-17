# 인덱스 추가 로컬 실측 결과

> [transaction-separation.md](transaction-separation.md)가 규명한 "mycourse의 진짜 병목은 FK 컬럼 인덱스 누락"이라는 결론을 실제로 고치고 로컬에서 재검증한 기록이다. 여기서 발견한 "로컬 환경 자체의 CPU 경합이 잔여 병목을 설명한다"는 결론이 다음 실측([EC2+RDS 분리 환경 측정 결과](../production/ec2-rds.md))으로 이어진다.

## 인덱스 추가 결과 (실측 완료)

**구현**: `place.day_schedule_id`, `place_image.place_id`, `day_schedule.course_id` 3개 FK 컬럼에 엔티티 `@Table(indexes = @Index(...))` 애노테이션으로 인덱스를 추가했다(`idx_place_day_schedule_id`, `idx_place_image_place_id`, `idx_day_schedule_course_id`) — `UploadCourse.java`에 이미 있던 `idx_upload_course_view_count` 선례를 그대로 따랐다. 범위는 `EXPLAIN ANALYZE`로 Seq Scan을 직접 확인한 이 3개 컬럼으로 한정했다(`CourseKeyword.upload_course_id` 등 검증되지 않은 다른 FK는 제외).

이 프로젝트에는 Flyway/Liquibase가 없어 로컬 벤치마크(`DB_DDL_AUTO=create`)에는 이 애노테이션만으로 즉시 반영되지만, **`.env.example` 기준 실제 배포 환경(`DB_DDL_AUTO=validate`)에서는 이 애노테이션만으로 인덱스가 생성되지 않는다** — validate 모드는 스키마를 검증만 하고 DDL을 실행하지 않기 때문이다. 운영 DB에 실제로 인덱스를 걸려면 별도 수동 DDL이 필요한데, 이번 범위에서는 의도적으로 작성하지 않았다(사용자 결정).

**재검증 방법**: 이전 세션 수치를 재사용하지 않고, 인덱스 적용 전/후를 이번 세션에서 나란히 새로 측정했다 — 공유 개발 머신의 run-to-run 노이즈를 배제하기 위해서다. 매 측정 전 DB 재시딩(`seed-benchmark.sql`) + Redis flush + 앱 재기동을 거쳤다.

**측정 중 발견한 오염과 조치**: mycourse와 uploadcourse를 같은 JVM에서 연달아 측정했더니, `hikaricp_connections_acquire_seconds_max`(Micrometer 롤링 윈도우 최댓값)가 직전 run의 값을 그대로 이어받아 서서히 감쇠하는 현상을 발견했다 — uploadcourse 측정 시작 시점에 mycourse의 최댓값(2.6118s)이 그대로 찍혀 있다가 약 2분에 걸쳐 감쇠해 0에 도달하는 것을 5초 간격 시계열로 직접 확인했다. 이후 도메인을 전환할 때마다 앱을 재기동해 이 오염을 차단했다(따라서 mycourse-after → uploadcourse-after 사이에도 재기동).

**`EXPLAIN ANALYZE` 전/후**: 3개 쿼리 모두 Seq Scan → Index Scan으로 전환됐고, 단독 실행(부하 없음) 기준 실행시간이 극적으로 줄었다.

| 쿼리(대상 컬럼) | 인덱스 전 | 인덱스 후 | 배율 |
|---|---|---|---|
| `place.day_schedule_id` | Seq Scan, 9.424ms (3만 행 중 29,995행 버림) | Index Scan, 0.190ms | ~49배 |
| `place_image.place_id` | Seq Scan, 17.017ms (6만 행 스캔) | Index Scan(Nested Loop), 0.072ms | ~236배 |
| `day_schedule.course_id` | Seq Scan, 7.317ms (6천 행 중 5,999행 버림) | Index Scan(Nested Loop), 0.043ms | ~170배 |

**결과 1 — mycourse: TPS/p95는 개선됐지만 풀 포화 지표는 거의 그대로다**

| | TPS | p95 | `pending` 최대 | `active` 최대 | `acquire_seconds` 최대 | 포화 시작 VU |
|---|---|---|---|---|---|---|
| 인덱스 전 | 86.42/s | 1.88s | 189 | 10 | 2.6118s | VU~20 |
| 인덱스 후 | 110.89/s | 1.63s | 181 | 10 | 2.7286s | VU~20 |
| 증감 | **+28.3%** | **-13.3%** | -4.2% | 0 | +4.5% | 동일 |

인덱스는 개별 쿼리 실행시간을 49~236배 줄였지만, HikariCP 풀 포화를 나타내는 세 지표(`pending`, `acquire_seconds`, 포화 시작 VU) 중 어느 것도 유의미하게 개선되지 않았다 — `acquire_seconds`는 오히려 미세하게 늘었다(노이즈 범위로 판단한다). TPS(+28%)·p95(-13%) 개선은 실재하지만, `EXPLAIN ANALYZE`가 보여준 49~236배라는 배율에 비하면 작다.

이 결과는 "쿼리가 느려서 커넥션을 오래 쥔다"는 문제는 인덱스로 해결됐지만, 그 위에 있는 "동시 요청 200개가 커넥션 10개를 두고 경쟁한다"는 20:1 구조적 과잉수요 문제는 인덱스로 풀리지 않는다는 0단계 문서의 결론과 방향이 일치한다. 다만 쿼리가 50~200배 빨라졌다면 풀의 회전율도 그만큼 빨라져 대기시간이 상당히 줄어드는 게 자연스러운 기대인데, 왜 `acquire_seconds` 절대값 자체가 거의 줄지 않았는지는 별도로 규명했다(아래 "추가 규명" 참고).

**결과 2 — uploadcourse: 캐시 미스 경로도 원래 풀 경합이 없었다(변화 없음 자체가 결과)**

| | TPS | p95 | `pending` 최대 | `active` 최대 | `acquire_seconds` 최대 |
|---|---|---|---|---|---|
| 인덱스 전(오염 보정 후 추정치) | 1,581.2/s | 82.06ms | 0 | 3 | ~0.0013s |
| 인덱스 후(깨끗한 JVM에서 측정) | 1,760.8/s | 71.02ms | 0 | 1 | 0.0010s |
| 증감 | +11.4% | -13.5% | 동일 | -2 | 사실상 동일 |

uploadcourse는 인덱스 적용 전에도 `pending=0`이었다(0단계에서 이미 확인된 대로, 캐시 히트 위주 트래픽이 DB 커넥션을 거의 안 쓰기 때문). 인덱스 적용 후에도 `pending=0`, `acquire_seconds`도 사실상 0으로 동일하다 — `day_schedule.course_id` 인덱스가 uploadcourse의 캐시 미스 경로(`UploadCourseDetailReader.read()`)를 실제로 개선했다는 직접 증거는 이번 부하 프로파일(pool 모드, 캐시가 점점 데워지는 구조)에서는 나오지 않았다. TPS·p95가 소폭 개선(+11%/-14%)된 건 인덱스 덕에 캐시 미스 시 쿼리 자체가 빨라진 결과로 보이지만, 애초에 풀 경합이 없었으니 "병목 해소"라 부르긴 어렵다.

**추가 규명 — 왜 `acquire_seconds`가 거의 개선되지 않았는가**: 위 표만 보면 앞뒤가 안 맞는다. 커넥션 점유시간이 정말 쿼리 속도만큼(수십~수백 배) 줄었다면 Little's Law에 따라 풀의 처리 용량도 그만큼 늘어야 하는데 TPS는 28%만 늘었다. 이를 별도로 규명했다 — 기존에 수집해둔 Prometheus 데이터 재해석(Phase A) → 실제 SQL 재검증(Phase B) → JFR 프로파일링(Phase C) 순서로 진행했다.

*Phase A — 카운터 지표로 실제 커넥션 점유시간 직접 계산*: `hikaricp_connections_acquire_seconds_max`는 Micrometer 롤링 윈도우 최댓값이라 절대 최댓값이 아니라는 한계가 이미 알려져 있었다. 대신 카운터형 지표(`hikaricp_connections_usage_seconds_sum`/`_count`)의 구간 증분으로 "평균 커넥션 점유시간"을 직접 계산했다.

| | 인덱스 전 | 인덱스 후 | 증감 |
|---|---|---|---|
| 평균 커넥션 점유시간(`usage_seconds_sum`/`_count`) | 46.8ms | 36.1ms | **-22.9%** |

TPS 개선폭(+28.3%)과 정확히 같은 방향·같은 크기로 움직인다 — 인덱스 효과 자체는 실재하고 정직하게 반영돼 있다. 다만 `EXPLAIN ANALYZE`가 보여준 49~236배와는 자릿수가 다르다. 추가로 `tomcat_threads_busy_threads`가 인덱스 전/후 모두 **200/200(설정된 `maxThreads`와 정확히 일치)**으로 포화돼 있었다는 것도 확인했다 — `pending`(181~189)은 "바쁜 Tomcat 스레드(200) - 실제 DB 커넥션(10)"과 거의 일치한다. VU200에서는 거의 모든 요청 처리 스레드가 SQL을 실행하는 게 아니라 HikariCP 큐에서 순번을 기다리며 멈춰 있다는 뜻이다. `process_cpu_usage`(JVM)는 전/후 모두 0.18~0.21로 낮아 CPU 포화 가설은 다시 기각했다.

*Phase B — 실제 Hibernate SQL이 정말 인덱스를 타는지 재확인*: `Place.placeImages`의 SUBSELECT 전략이 실제로 발행하는 SQL은 이전 `EXPLAIN ANALYZE` 테스트보다 복잡하다(day_schedule↔place 조인이 서브쿼리 안에 포함됨). `show-sql`로 캡처한 정확한 SQL을 그대로 `EXPLAIN ANALYZE`에 넣어보니 3개 인덱스(`idx_day_schedule_course_id`, `idx_place_day_schedule_id`, `idx_place_image_place_id`) 모두 정상 사용됐고 실행시간 0.155ms였다 — "인덱스가 실제 쿼리엔 덜 먹혔을 것"이라는 가설은 기각됐다. 워밍업된 상태에서 `MyCourseDetailReader`의 트랜잭션(쿼리 4개)을 5회 직접 호출해 Hibernate `Session Metrics`를 확인한 결과도 1.8~3.6ms로 빨랐다.

*Phase C — JFR 프로파일링으로 격리 측정(수 ms)과 실측 점유시간(36~47ms)의 간극 추적*: 고정 동시성 VU200(TPS 145.2/s, p95 1.82s) 부하를 걸며 실행 중인 JVM에 JFR을 붙였다.
- `jdk.CPULoad`(JVM 프로세스가 아니라 이 개발 머신 전체 CPU)를 시간대별로 보면, k6 부하 시작 전엔 15~22%였다가 VU200 부하가 걸리는 동안 **50~73%까지 상승**했다(12코어 AMD Ryzen 5 7530U 기준).
- GC 정지시간은 이미 미미함을 확인했다(전체 윈도우의 0.15% 수준).
- 결정적으로, `jdk.SocketRead` 이벤트(임계값 이상만 샘플링됨) 15건 중 14건이 PostgreSQL(포트 5434)로부터 응답을 기다리는 소켓 읽기였고 **지속시간이 10.3~42.5ms**였다 — 같은 쿼리를 `psql EXPLAIN ANALYZE`로 단독 실행했을 때(<1ms)의 10~40배다.

**결론**: 추가 지연은 애플리케이션 코드에도, 쿼리 실행계획에도 있지 않다(둘 다 인덱스 적용 후 정상 동작 확인). JDBC가 PostgreSQL의 응답을 기다리는 소켓 읽기 구간 자체가 부하 아래서 10~40배 느려지는데, 이게 JVM 프로세스의 CPU 사용량으로는 거의 안 보인다(`process_cpu_usage` 0.2 미만). 반면 머신 전체 CPU는 같은 부하 구간에서 50~73%까지 뚜렷하게 올라간다. 가장 근거 있는 해석은: **이 벤치마크가 앱·PostgreSQL·Redis·Prometheus·Grafana·k6 부하생성기를 전부 코어 12개짜리 개발 노트북 한 대에서 동시에 돌리는 비격리 환경**이라, VU200에서 발생하는 OS 레벨 스레드/프로세스 스케줄링 경합이 PostgreSQL 백엔드 프로세스의 응답 지연으로 이어진다는 것이다 — 인덱스로 고칠 수 있는 영역(쿼리 실행계획) 밖에 있는, 벤치마크 환경 자체의 구조적 잡음이다.

이 결론에도 한계가 있다: PostgreSQL 서버 프로세스 자체를 별도로 프로파일링하지는 못했다(`psql EXPLAIN ANALYZE`는 연결 1개 기준이라, 실제 10-way 동시 연결 상황에서 서버 사이드가 정확히 뭘 하는지는 직접 보지 못했다). 완전히 결정적으로 규명하려면 부하생성기(k6)·앱·DB를 서로 다른 머신으로 분리해 재측정해야 하는데 이는 이번 범위를 벗어난다. 다만 CPU·GC·쿼리플랜을 하나씩 배제하고 남은 게 "PostgreSQL 응답 소켓 대기"뿐이라는 점, 그 소켓 대기가 머신 전체 CPU가 올라가는 구간과 시간적으로 정확히 일치한다는 점에서, "공유·비격리 벤치마크 환경의 스케줄링 잡음"이라는 결론은 노이즈로 치부하기 어려운 수렴적 증거를 갖고 있다.

**한계**:
- 각 run은 반복 없이 1회씩만 측정했다(0단계 재검증 때와 동일한 한계).
- 엔티티 애노테이션 방식은 `DB_DDL_AUTO=validate`인 실제 배포 환경에는 인덱스를 반영하지 않는다 — 운영 반영에는 별도 수동 DDL이 필요하며, 이번 범위에서는 의도적으로 작성하지 않았다.

**시사점**: 인덱스 추가는 "쿼리가 느리다"는 문제 자체는 확실히 해결했다(`EXPLAIN ANALYZE`와 Hibernate `Session Metrics`로 직접 증명). 그러나 mycourse의 최종 처리량은 여전히 VU~20 근처에서 포화되고 `pending`이 180대까지 쌓인다 — 인덱스만으로는 20:1 풀 크기 문제를 풀 수 없다는 게 이번 실측으로 다시 확인됐고, 그 잔여 병목의 상당 부분이 이번 벤치마크 환경 자체의(공유 머신에서 부하생성기와 시스템이 CPU를 나눠 쓰는) 구조적 한계라는 것도 함께 드러났다. TASK 문서의 1단계(Signed Cookie 전환)·4단계(풀 크기 재검토)의 필요성은 이번 결과로 오히려 강화된다: mycourse처럼 캐시가 없는 경로는 커넥션 풀 크기 자체를 늘리거나(4단계), 애초에 DB 접근 자체를 줄이는 캐싱 도입이 필요해 보인다.

이 벤치마크 환경 자체의 한계를 없애기 위해 EC2+RDS로 분리한 환경에서 재검증한 기록은 [ec2-rds.md](../production/ec2-rds.md) 참고(다만 그 재검증은 이번 인덱스가 아니라 0단계를 다시 확인한 것이고, 인덱스 자체의 EC2+RDS 재검증은 아직 미착수다).

## 참고 문서

- [PRESIGN-BOTTLENECK-FIX.md](../../PRESIGN-BOTTLENECK-FIX.md) — 이 실측이 속한 단계별 계획 문서
- [transaction-separation.md](transaction-separation.md) — 인덱스 누락 문제를 처음 규명한 선행 실측
- [ec2-rds.md](../production/ec2-rds.md) — 이 문서가 제안한 환경 분리 재검증의 실행 결과(0단계 대상)
