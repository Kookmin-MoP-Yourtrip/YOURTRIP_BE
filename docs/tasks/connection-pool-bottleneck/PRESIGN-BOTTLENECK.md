# TASK-PRESIGN-BOTTLENECK. presigned URL CPU 병목 가설 실측 검증

> **이 작업의 위치** — 인기 코스·상세 조회 성능 개선은 네 단계로 이어졌다. **이 문서는 1단계다.**
>
> 1. **커넥션 풀 병목 발견** — presign이 트랜잭션 안에서 커넥션을 초 단위로 점유
> 2. [Redis 캐싱 도입](../redis-caching/README.md) — DB 접근 자체를 줄인다
> 3. [트랜잭션 분리 측정](../popular-tx-separation/README.md) — 캐시 히트인데도 커넥션을 잡던 구조를 걷어낸다
> 4. [캐싱 효과 측정](../cache-effect-measurement/README.md) — 세 상태(A0·A1·A2)를 한 표에 놓고 각 단계의 몫을 분해한다

> PR #57("이미지 URL 발급 방식을 CloudFront 기반으로 전환")은 "presigned URL 발급의 CPU 비용이 상세 조회 API의 병목"이라는 가설 위에서 진행됐다. 이 문서는 그 가설을 이미지 URL 발급 방식을 PR #57 이전(S3 presign)으로 되돌린 뒤, CPU 프로파일링(JFR)과 인프라 지표(Prometheus/HikariCP)로 직접 검증한 기록이다.

## 배경 — 지금까지 한 번도 직접 측정하지 않은 가설

가설의 발원지는 [redis-caching/task/detail-cache.md:91](../redis-caching/task/detail-cache.md)이고, 원문 표현부터가 추정이었다:

> "동시성 50 하에서 이 서명 연산 자체가 새로운 병목으로 등장한 **것으로 보인다**"

근거는 "DB 쿼리를 600회→1회로 없앴는데 TPS는 1.6배밖에 안 올랐다"는 잔차 기반 소거법이었다. 이후 PR #57/#61/#62에서 4차례 A/B 벤치마크(TASK-CLOUDFRONT.md)를 했지만 전부 end-to-end TPS 비교였고, **CPU 프로파일링·마이크로벤치마크·`process.cpu.usage` 관측은 한 번도 없었다.**

이 작업을 설계하며 기존 문서의 절대 수치를 단순 CPU 모델에 넣어보니 잘 맞지 않는다는 것도 발견했다:

| | 요청당 서명 | 관측 TPS(RSA/ECDSA, TASK-CLOUDFRONT.md) | 12코어 이론 처리량(개략) |
|---|---|---|---|
| RSA-2048 | 10회 | 23~30 | 수천 회/초 |
| ECDSA P-256 | 10회 | 63~105 | 수십만 회/초 |

서명 연산만으로는 CPU를 거의 안 쓰는데 TPS는 3배 차이가 났다 — "서명 CPU가 병목"이라는 설명과 이 수치가 잘 안 맞았다. 그래서 이번 검증은 원 가설 외에 두 개의 경쟁 가설을 함께 세워 판정했다.

- **경쟁 가설 ① — 서명이 JDBC 커넥션을 쥔 채 실행된다.** `getPlaceListByDay`/`getDetail` 모두 `@Transactional(readOnly = true)`이고 URL 발급이 그 트랜잭션 경계 안에서 일어난다. HikariCP 풀이 기본값 10인 상태에서 서명 시간만큼 커넥션 점유 시간이 늘어나면, 병목은 CPU가 아니라 **커넥션 점유 시간**이다.
- **경쟁 가설 ② — 로깅이 CPU를 먹고 있다.** `format_sql: true`, `generate_statistics: true`, `org.hibernate.SQL: debug`, `org.springframework.security: DEBUG`가 전부 켜진 채로 과거 4차례 벤치마크가 측정됐다.

## 측정 설계

### arm 구성

| arm | 코드 | 포트 |
|---|---|---|
| **P (presign)** | 이 브랜치 — `ea909d5`(presign 코드 삭제 커밋)를 역-diff로 복원하고 mycourse/uploadcourse 상세조회 경로 9곳을 `s3Service.getPresignedUrl()`로 되돌림. PR #61의 `CompletableFuture` 병렬 서명도 함께 제거해 **가설이 세워진 시점(PR #57 이전)의 코드와 100% 동일하게** 맞췄다. | 8080 |
| **C (cloudfront)** | `f8386e6`(현재 main HEAD, PR #62까지 반영된 ECDSA CloudFront) — 별도 워크트리 | 8081 |

두 arm은 항상 순차 실행했다(CPU 병목 실험에서 두 JVM이 코어를 나눠 쓰면 실험 자체가 무의미해진다).

### 계측

- **JFR**: `java -XX:StartFlightRecording=name=bench,settings=profile -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -jar ...`로 세션 전체(모든 run)를 하나의 연속 레코딩으로 남기고, 세션 종료 시 `jcmd <PID> JFR.dump`로 덤프했다. `scripts/jfr/parse-execution-samples.mjs`로 `jdk.ExecutionSample` 이벤트를 파싱해 관심 패키지(AWS SDK 서명/crypto/Hibernate/HikariCP/logback)가 CPU 샘플 스택에 등장하는 비율을 집계했다.
- **Prometheus/Grafana**: `docker-compose.yml`에 `scripts/grafana/provisioning`을 볼륨 마운트해 전용 대시보드(`Bottleneck Test` 폴더 › `Presign CPU Bottleneck`, 8패널)를 자동 provisioning했다. `process_cpu_usage`, `hikaricp_connections_pending`, `hikaricp_connections_usage_seconds`, `tomcat_threads_busy_threads` 등을 `max_over_time`으로 세션 전체 피크를 조회했다.
- **마이크로벤치마크**: `SigningBenchmarkTest`(`@Tag("benchmark")`, `./gradlew benchmarkTest`)로 S3 presign / CloudFront ECDSA / 무서명 public URL 1회 비용을 워밍업 2,000회 + 측정 10,000회 반복으로 측정해, "이론 최대 TPS = 코어수 ÷ (이미지수 × 1회 비용)"을 계산했다.

### 시드와 부하

`scripts/sql/seed-benchmark.sql`로 TASK-CLOUDFRONT.md 규격(mycourse/uploadcourse 각 3,000코스=hot 1+pool 2,999, 코스당 이미지 10장)을 재현했다. `scripts/k6/detail-fixed.js`(동시성 50/200 × 600건, 과거 측정과 비교용)와 `scripts/k6/detail-ramping.js`(VU 1→5→10→20→50→100→200, 각 단계 60~90초, **포화점 규명용**) 두 프로파일을 도입했다 — 과거 4차례 측정은 전부 "고정 동시성 두 점"만 봐서 포화점 자체를 관측한 적이 없었다.

## 결과 — 고정 프로파일 (16 run, 과거 측정 비교용)

| 도메인 | 시나리오 | 동시성 | arm | TPS | p50 | p95 | 성공률 |
|---|---|---|---|---|---|---|---|
| uploadcourse | single(hot) | 50 | P | 152.6 | 325.0ms | 371.2ms | 100% |
| uploadcourse | single(hot) | 50 | C | 317.2 | 128.1ms | 378.4ms | 100% |
| uploadcourse | single(hot) | 200 | P | 162.7 | 1160.3ms | 1364.3ms | 100% |
| uploadcourse | single(hot) | 200 | C | 669.5 | 247.8ms | 395.7ms | 92.3% |
| uploadcourse | pool | 50 | P | 110.7 | 444.2ms | 656.9ms | 100% |
| uploadcourse | pool | 50 | C | 139.4 | 355.3ms | 499.6ms | 100% |
| uploadcourse | pool | 200 | P | 140.5 | 1412.2ms | 1765.4ms | 84.5% |
| uploadcourse | pool | 200 | C | 267.1 | 686.7ms | 903.8ms | 85.3% |
| mycourse | single(hot) | 50 | P | 105.1 | 403.3ms | 989.6ms | 100% |
| mycourse | single(hot) | 50 | C | 148.2 | 315.8ms | 601.2ms | 100% |
| mycourse | single(hot) | 200 | P | 123.4 | 1006.3ms | 3119.3ms | 84.3% |
| mycourse | single(hot) | 200 | C | 169.6 | 953.3ms | 2057.2ms | 100% |
| mycourse | pool | 50 | P | 137.9 | 328.9ms | 723.2ms | 100% |
| mycourse | pool | 50 | C | 185.5 | 253.6ms | 418.9ms | 100% |
| mycourse | pool | 200 | P | 117.2 | 1355.5ms | 3384.8ms | 100% |
| mycourse | pool | 200 | C | 147.1 | 1199.8ms | 2236.9ms | 100% |

(동시성 200 조합의 84~93% 성공률은 과거 문서와 동일하게 관찰된 Windows 개발 머신 Tomcat `maxThreads`(200) 경계 현상이다. 이번 측정은 p99를 별도로 뽑지 않았다 — k6 기본 summary 통계에 없어 누락됐다. 이는 이번 측정의 명백한 한계로 남긴다.)

`arm C`가 모든 조합에서 `arm P`보다 빠르다 — PR #57~#62가 실제로 유효한 성능 개선이었다는 것은 이 측정으로도 재확인된다. **이 문서가 답하려는 질문은 "어느 쪽이 빠른가"가 아니라 "그 차이의 물리적 원인이 CPU인가"다.**

## 결과 — ramping 프로파일 (4 run, 이번 실험의 핵심)

| 도메인 | arm | 총 요청 | 평균 TPS | p50 | p95 | 성공률 |
|---|---|---|---|---|---|---|
| uploadcourse(pool) | P | 103,920 | 230.6 | 152.7ms | 709.7ms | 100% |
| uploadcourse(pool) | C | 689,112 | **1530.7** | 22.4ms | 97.9ms | 100% |
| mycourse(pool) | P | 80,708 | 178.9 | 165.9ms | 1.04s | 100% |
| mycourse(pool) | C | 272.3 | 272.3 | 117.7ms | 631.5ms | 100% |

uploadcourse는 arm C가 arm P보다 **6.6배** 높은 처리량을 냈다(서명 자체가 없는 공개 URL vs 매 요청 10회 presign). mycourse는 arm C(ECDSA)가 arm P(S3 presign)보다 **1.5배** 높다.

## 직접 증거 1 — JFR CPU 프로파일 (세션 전체, 관심 패키지 포함 샘플 비율)

| 카테고리 | arm P (presign) | arm C (cloudfront) |
|---|---|---|
| 총 샘플 수 | 15,801 | 35,359 |
| **서명 관련(`software.amazon.awssdk.*`)** | **24.54%** | **54.02%** |
| **crypto(`java.security.*`/`sun.security.*`)** | **26.77%** | **55.28%** |
| hibernate | 10.31% | 6.33% |
| hikaricp | 1.03% | 0.66% |
| logging(logback) | 1.63% | 1.26% |

arm C의 최상위(leaf) 프레임 1위는 `sun.security.util.math.intpoly.IntegerPolynomialP256.carryReduce`(9.84%) — **ECDSA P-256 타원곡선 연산 그 자체가 CPU 프로파일러에 직접 찍힌 것**이다. 이것이 이번 검증이 확보한, 지금까지 한 번도 없었던 **직접 증거**다.

**경쟁 가설 ② 기각**: logging 카테고리는 두 arm 모두 1.3~1.6%로, 판정 기준표의 "추가 확인 run이 필요한 임계값"(15%)에 한참 못 미친다. 로깅 설정(`format_sql`/`show-sql`/`SQL: debug`)이 과거 측정을 유의미하게 왜곡했다고 볼 근거는 없다.

## 직접 증거 2 — HikariCP 커넥션 점유 (Prometheus, 세션 전체 peak)

| 지표 | arm P | arm C |
|---|---|---|
| `process_cpu_usage` (JVM 프로세스, 0~1) | 0.566 | 0.496 |
| `system_cpu_usage` (OS 전체, 0~1) | 1.0 | 1.0 |
| **`hikaricp_connections_pending` (대기 요청 수, max)** | **188** | **189** |
| **`hikaricp_connections_usage_seconds_max` (커넥션 최대 점유 시간)** | **6.945초** | **15.05초** |
| `tomcat_threads_busy_threads` (max) | 200 (=maxThreads 전량 소진) | (동일 현상 추정) |

`hikaricp_connections_usage_seconds_max`가 압도적으로 중요한 숫자다. 마이크로벤치마크상 이미지 10장 서명의 순수 비용은 3.2~3.8ms(382us×10 / 320us×10)인데, 실측 최대 커넥션 점유 시간은 **6.9~15초** — 순수 서명 비용의 **1,000배 이상**이다. 이는 서명 자체가 느려서가 아니라, **다수 요청이 각자 트랜잭션(=커넥션) 안에서 서명을 마칠 때까지 경쟁적으로 CPU를 기다리며 커넥션을 쥐고 있기 때문**이다 — 풀 크기가 기본값 10인데 동시성 200이 몰리면, 10개 커넥션이 각각 초 단위로 묶이면서 나머지 190에 가까운 요청이 전부 대기열(`pending`)에 쌓인다.

## 직접 증거 3 — 이 병목은 uploadcourse "캐시 히트" 경로에도 그대로 적용된다

캐싱 로드맵(TASK-3/4)의 전제는 "인기 코스는 캐시가 거의 항상 히트하므로 DB 부하가 없다"였다. 이 병목이 **DB 쿼리가 실제로 발생할 때만** 문제라면, 캐시 히트율이 높은 인기 코스는 안전해야 한다. 이를 별도로 검증했다.

**방법**: `uploadcourse` id=1을 두 번 조회해 Redis 캐시를 워밍업했다 — 첫 요청에서 `hibernate_statements_total`이 +4(진짜 DB 조회), 두 번째 요청부터는 **0 증가**(순수 캐시 히트)임을 확인했다. 이 워밍업된 상태 그대로 동시성 200으로 15.4초간 3,000건을 요청했고, 부하 종료 후에도 `hibernate_statements_total`이 **전혀 증가하지 않았음**을 재확인했다 — 이 부하 동안 SQL은 단 한 줄도 실행되지 않았다.

**그런데도 관측된 HikariCP peak (Prometheus, 이 burst 구간만)**:

| 지표 | 값 |
|---|---|
| `hikaricp_connections_pending` | **189** |
| `hikaricp_connections_active` | **10** (풀 전량 소진) |
| `process_cpu_usage` | 0.65 |

DB 쿼리가 0건인데도 대기 큐가 189까지 쌓였다 — 세션 전체(쿼리가 실제 발생한 구간 포함) 측정치와 사실상 같은 규모다. 원인은 [UploadCourseServiceImpl.java:163](../../../src/main/java/backend/yourtrip/domain/uploadcourse/service/UploadCourseServiceImpl.java)의 `getDetail`이 캐시 히트/미스 분기 전체를 **메서드 하나를 통째로 감싼 `@Transactional(readOnly = true)`** 안에 두고 있기 때문이다. Spring이 트랜잭션을 시작하는 시점에 — 그 트랜잭션 안에서 쿼리를 한 번도 안 날려도 — HikariCP 커넥션을 확보하며(read-only/isolation level을 설정하려면 물리 커넥션이 필요), 그 커넥션은 메서드가 리턴할 때까지, 즉 캐시 히트 분기의 서명 루프(`buildDaySchedulesFromCache`)가 끝날 때까지 반납되지 않는다.

**결론**: 이 병목의 조건은 "DB를 실제로 건드리는가"가 아니라 **"presign/서명 호출이 `@Transactional` 메서드 경계 안에 있는가"** 하나뿐이다. mycourse(캐싱 자체가 없어 항상 해당), uploadcourse 캐시 미스, **그리고 uploadcourse 캐시 히트**까지 전부 이 조건을 만족한다 — 즉 이미지 URL을 만드는 이 코드베이스의 상세조회 경로 전체가 예외 없이 영향권 안에 있다. 캐싱이 DB 부하를 없앤 것은 사실이지만(TASK-3/4가 실측한 대로), 그것이 이 커넥션 풀 경합 문제까지 없애주지는 못한다.

## 직접 증거 4 — 포화점(knee)은 VU 200이 아니라 VU 10~20 부근이다

ramping 프로파일을 도입한 목적이 "고정 동시성 두 점만으로는 처리량이 어디서 꺾이는지 알 수 없다"였는데, 앞의 표들은 그 ramping run 7분 30초 전체의 **평균**만 보여줄 뿐 실제 꺾이는 지점을 짚지는 못했다. 이를 보완하기 위해 uploadcourse(pool) ramping을 k6 요청 단위 시계열(`--out json`)과 Prometheus 5초 간격 range query를 함께 남기며 재실행하고, VU 단계(1→5→10→20→50→100→200)에 맞춰 15초 단위로 재집계했다.

| offset | VU 단계 | TPS(15초 평균) | 평균 `process_cpu_usage` | max `pending` | max `active`(풀 크기=10) |
|---|---|---|---|---|---|
| 45s | ~5 | 181.5 | 0.20 | 0 | 4 |
| 75s | ~10 | 262.7 | 0.35 | 0 | 6 |
| 90s | ~10 | 265.3 | 0.36 | 0 | 7 |
| **120s** | **~20** | **264.1** | **0.37** | **0** | **10 ← 풀 전량 소진 시작** |
| 135s | ~20 | 265.3 | 0.42 | **2 ← 대기 시작** | 10 |
| 180s | ~50 | 272.5 | 0.40 | 10 | 10 |
| 270s | ~100 | 258.4 | 0.39 | 41 | 10 |
| 360s | ~200 | 232.5 | 0.39 | 92 | 10 |
| 435s | ~200 | 228.0 | 0.41 | 175 | 10 |

네 지표가 전부 같은 지점(offset 90~135초, VU 10~20)에서 동시에 꺾인다.

1. **TPS가 여기서 사실상 멈춘다.** VU 5→10 구간에서 182→265로 뛴 뒤, VU를 10→20→50→100→200으로 10배 넘게 늘려도 TPS는 260 근방에서 더 오르지 않는다(후반부엔 오히려 소폭 하락).
2. **`hikaricp_connections_active`가 VU=20 지점에서 정확히 10(풀 크기)에 도달**하고, 이후 끝까지 10에 고정된다.
3. **`pending`이 그 직후부터 나타나 VU가 늘수록 거의 선형으로 계속 쌓인다**(2 → 10 → 41 → 92 → 175). 한 번도 0으로 돌아오지 않는다.
4. **가장 결정적인 신호는 CPU다.** VU=20에서 37%를 찍은 뒤, VU를 200까지 10배 더 늘려도 `process_cpu_usage`는 33~42% 구간에서 그대로 머문다 — 부하를 10배 더 줬는데 CPU 사용률은 안 늘어난다. 늘어난 요청 대부분이 실제로 연산 중이 아니라 커넥션을 못 받아 대기 중이기 때문이다.

**"직접 증거 2"의 세션 전체 peak(`process_cpu_usage` 0.566)와의 차이**: 그 값은 이 ramping run이 아니라 20개 run 전체 중 어디선가 찍힌 최댓값이다. 이 ramping run 단독으로는 CPU가 35~42%를 넘긴 적이 없다 — 아마 동시성 200이 처음부터 한 번에 몰리는 고정 run(ramping처럼 서서히 올라가지 않음)의 초반 순간이었을 가능성이 높지만, 이는 추정이며 별도로 같은 방식의 시계열 분석이 필요하다.

**결론**: 이 시스템은 동시 유저 20명 근처에서 이미 구조적으로 막힌다. 그 이후 VU를 아무리 늘려도(50→100→200) 처리량도 CPU 사용률도 거의 안 바뀌고 대기 큐만 계속 길어진다 — "CPU가 부족해서"가 아니라 "커넥션 10개가 전부라서"라는 것을 가장 직접적으로 보여주는 시계열 증거다.

**이 결과는 arm P의 uploadcourse에 한정된다는 점을 명확히 해둔다.** arm C의 uploadcourse는 `cloudFrontService.getPublicUrl()`(순수 문자열 조합, 서명 없음)을 쓰므로 트랜잭션 안에서 하는 일이 사실상 없고, 같은 방식으로 시계열을 뜯어봐도 이런 이른 포화는 나타나지 않을 것으로 예상된다(실측하진 않았다) — ramping 결과에서 arm C uploadcourse가 arm P보다 6.6배 높은 처리량(1530.7 vs 230.6 TPS)을 낸 것도 "커넥션을 오래 쥘 이유 자체가 없다"는 이 설명과 정합적이다.

## 판정

계획 문서의 4-way 판정 기준표(원 가설 성립 / 경쟁 가설① / 경쟁 가설② / 제3 원인)에 실측치를 넣어보면 **어느 한 줄에도 깔끔하게 들어맞지 않는다** — `process_cpu_usage`가 1.0에 붙지도 않았고(0.5~0.57), 그렇다고 "CPU가 낮다"고 하기에는 JFR 서명 샘플 비율이 24~54%로 결코 낮지 않다. 실측이 보여주는 건 **두 요인의 합성**이다.

1. **서명 CPU 비용은 실재한다** (원 가설의 핵심 부분은 참). JFR이 이를 직접 증명한다 — arm C에서 ECDSA 타원곡선 연산이 leaf 프레임 1위, crypto 카테고리가 전체 CPU 샘플의 55%.
2. **그러나 그 자체만으론 관측된 처리량 저하의 규모를 설명하지 못한다.** 마이크로벤치마크 기반 이론 최대 TPS(uploadcourse hot, 동시성 50, arm P)는 3,139인데 실측은 152.6 — 이론치의 4.9%에 불과하다. 순수 CPU 바운드였다면 이만큼 벌어지지 않는다.
3. **격차의 진짜 원인은 "서명이 JDBC 트랜잭션 경계 안에서 실행된다"는 구조다** (경쟁 가설 ①). 서명 CPU 비용(실재하지만 절대값은 작다)이 **커넥션 점유 시간을 증폭**시키고, 그 증폭된 점유 시간이 **HikariCP 풀(기본 10) 앞에서 대기 행렬을 만들며 병목을 극적으로 키운다.** 즉 CPU 비용은 "방아쇠"고, 커넥션 풀 경합이 "증폭기"다.
4. **이 증폭기는 DB 부하와 독립적으로 작동한다** (직접 증거 3). 캐시 히트로 쿼리가 0건이어도 서명이 `@Transactional` 메서드 안에 있는 한 커넥션은 그대로 잡힌다 — "캐싱이 잘 되면 안전하다"는 암묵적 전제가 이 병목에는 통하지 않는다.
5. **포화점은 놀랍도록 이르다** (직접 증거 4). uploadcourse(pool) ramping을 시계열로 뜯어보면 VU 10~20 부근에서 이미 `hikaricp_connections_active`가 풀 크기(10)에 도달하고 `pending`이 쌓이기 시작하며, `process_cpu_usage`는 그 지점(35~42%)에서 멈춘 채 VU를 200까지 10배 더 늘려도 더 이상 오르지 않는다. "동시성 200에서 병목이 생긴다"가 아니라 "동시 유저 20명만 돼도 이미 병목 구간에 들어가 있다"가 더 정확한 서술이다.

이 결론은 원 가설을 부정하지 않는다 — 오히려 "presign이 무료가 아니다"는 로드맵의 반성(TASK-4.md)이 방향은 맞았음을 확인해준다. 다만 **처방이 달라진다**: PR #57~#62가 택한 "서명 알고리즘 자체를 가볍게 만든다"(RSA→ECDSA)는 방아쇠(서명 비용)를 줄이는 접근이라 유효했지만, 증폭기(트랜잭션 안에서 커넥션을 쥔 채 서명)를 직접 건드리지 않았다. **서명 호출을 트랜잭션/커넥션 스코프 밖으로 빼내는 것**이 더 근본적인 처방일 수 있다는 뜻이다.

## PR #61(병렬 서명) 재해석

TASK-CLOUDFRONT.md는 PR #61의 병렬화 효과가 미미했던(+2~11%) 원인을 "HikariCP 풀 크기 병목으로 추정된다"고 **추측**으로만 남겼다. 이번 실측이 그 추측을 **직접 뒷받침한다**:

- 병렬화는 "한 요청 안에서 N장의 이미지를 동시에 서명"하는 최적화다 — 요청 하나의 서명 소요 시간(따라서 그 요청의 커넥션 점유 시간)은 줄일 수 있다.
- 그러나 **동시성 200에서는 서로 다른 200개 요청이 각자 병렬 서명을 시도**하며 12개 물리 코어를 놓고 경쟁한다. 시스템 전체가 이미 CPU 경합 상태(system_cpu_usage가 세션 중 1.0을 찍음)라면, "요청 내부 병렬화"가 실제로 확보할 수 있는 여유 코어가 없어 기대만큼의 단축 효과가 나지 않는다.
- 즉 병렬화는 "커넥션을 쥐는 시간을 줄인다"는 방향 자체는 맞았지만, **애초에 코어가 부족한 상황에서는 병렬화도 결국 같은 자원(CPU)을 두고 경쟁**하므로 효과가 희석된다. 진짜 레버리지는 (a) 서명 비용 자체를 낮추거나(ECDSA 전환, 이미 함) (b) 서명을 트랜잭션 밖으로 빼서 커넥션 점유 자체를 없애는 것이다.

### 병렬화 효과가 더 희석되는 또 다른 이유 — 서명 실행자(executor)는 커넥션 풀보다 뒤에 있다

위 설명(코어 경쟁)과는 별개로, `getPlaceListByDay`의 코드 구조 자체에 병렬화의 효과를 원천적으로 제한하는 지점이 하나 더 있다.

`cloudFrontSigningExecutor`(`CloudFrontExecutorConfig`, 이 머신에서 코어 수만큼인 12스레드)는 요청마다 새로 만드는 게 아니라 **앱 전체가 공유하는 싱글턴 빈**이다. 그런데 이 executor에 서명 작업을 던지는 코드는 `@Transactional(readOnly = true)`로 감싸인 메서드 안에 있고, 그 메서드는 커넥션을 먼저 획득해야 실행이 시작된다. 즉:

1. HikariCP 풀(10개) → **트랜잭션 메서드에 진입할 수 있는(=서명 작업을 executor에 제출할 수 있는) 요청이 애초에 최대 10개로 제한**된다.
2. 11번째 요청은 `HikariDataSource.getConnection()` 단계에서 블로킹되므로, DB 조회는커녕 서명 작업을 executor에 제출하는 코드 줄까지 아예 도달하지 못한다.
3. 그 결과 **12스레드짜리 `cloudFrontSigningExecutor`는 동시성 200 상황에서도 실제로는 최대 10개 요청분의 작업(이미지 10장 기준 최대 100개 작업)만 받아본 적이 없다** — 자기 용량(12스레드)을 한 번도 다 채워보지 못한 채, 그보다 앞단의 더 작은 병목(커넥션 풀 10개)에 항상 가려져 있었던 셈이다.

정리하면 PR #61의 병렬화가 기대만큼 효과를 못 낸 이유는 두 겹이다 — **(a) 코어 자체가 부족해서 병렬화해도 나눠 쓸 여유가 없었고, (b) 그마저도 커넥션 풀이 먼저 막아서 병렬 실행자 풀의 용량 자체를 다 써본 적이 없었다.** (b)는 이번에 코드를 다시 읽으며 새로 확인한 지점이다.

## 발견한 부가 이슈 (이번 범위 밖, 기록만 남김)

- **arm P의 JFR leaf 프레임에서 `java.net.URL.<init>`, `org.springframework.boot.loader.jar.NestedJarFile.hasEntry`, `ZipContent.getFirstLookupIndex` 등 Spring Boot 실행 가능 fat jar의 nested-jar 클래스로더 관련 프레임이 유의미하게 등장한다**(수 % 대). `S3Presigner.presignGetObject`가 내부적으로 `new URL(...)`을 호출하는데, 이것이 fat jar 실행 환경(`java -jar`, 이번 벤치마크와 실제 배포 방식 동일)에서 nested-jar 프로토콜 핸들러 조회를 유발해 예상외의 비용을 더할 가능성이 있다. 다만 이 가설은 마이크로벤치마크(일반 클래스패스에서 실행, fat jar 아님)에서도 동일하게 느린 수치(382us)가 나온 것과는 완전히 정합하지 않아, 후속 검증이 필요한 별도 주제로 남긴다.
- **JFR `dumponexit=true` + 강제 종료(`taskkill /F`) 조합은 빈 파일을 만든다** — 셧다운 훅이 생략되기 때문. 앱이 살아있는 상태에서 `jcmd <PID> JFR.dump`로 명시적으로 덤프하는 방식이 안전하다.

## 이번 작업에서 얻은 교훈 (포트폴리오 포인트)

1. **"병목을 고쳤다"와 "병목의 원인을 안다"는 다른 주장이다.** PR #57~#62의 end-to-end A/B 벤치마크는 "무엇이 빨라졌는가"에는 정확했지만 "왜 빨라졌는가"의 물리적 메커니즘까지는 증명하지 못했다. CPU 프로파일링(JFR)과 인프라 지표(HikariCP)를 직접 붙였을 때만, 처음으로 "서명 CPU가 방아쇠, 커넥션 풀 경합이 증폭기"라는 구체적 메커니즘이 드러났다.
2. **잔차 기반 추론(TASK-4.md)은 방향은 맞아도 메커니즘까지 알려주지 않는다.** "DB 쿼리를 없앴는데 TPS가 그만큼 안 올랐다 → 뭔가 다른 게 있다"는 추론은 유효한 신호였지만, 그 "뭔가"가 CPU 그 자체인지 CPU가 유발하는 2차 효과(커넥션 점유)인지는 end-to-end 측정만으로는 구분할 수 없었다.
3. **이론적 상한(마이크로벤치마크 기반)과 실측치의 괴리 자체가 진단 도구가 된다.** 실측이 이론 최대의 5% 미만이라는 사실 하나가, "CPU가 유일한 병목"이라는 가설을 통계적으로 반증하고 다른 증폭 기전을 찾게 만든 결정적 단서였다.
4. **판정 기준을 미리 정해두어도(4-way 표) 현실은 합성 원인일 수 있다.** 실측 전에는 "CPU냐 커넥션이냐 로깅이냐"를 배타적 선택지로 설계했지만, 실측 결과는 "CPU 비용(실재)이 커넥션 경합(진짜 증폭기)을 유발"하는 인과 사슬이었다. 사전 설계한 판정표가 실측과 깨끗하게 안 맞을 때, 그 불일치 자체를 억지로 끼워 맞추지 않고 정직하게 재해석하는 것이 이번 작업에서 가장 중요했던 지점이다.
5. **"캐싱이 잘 되고 있다"는 사실이 관련 병목까지 없애준다고 넘겨짚으면 안 된다.** 병목의 원인 분석이 끝난 뒤 나온 질문("이 병목은 캐시 안 되는 mycourse 한정 아니냐?")에 답하려고 uploadcourse 캐시 히트 경로만 따로 실측했더니, DB 쿼리가 0건인데도 동일한 규모의 HikariCP 경합이 재현됐다. 캐싱은 DB 부하를 없애는 데는 성공했지만(TASK-3/4가 이미 증명), `@Transactional` 경계 설계가 잘못돼 있으면 "DB를 안 쓰는 캐시 히트 요청"조차 커넥션 풀을 갉아먹을 수 있다 — 캐싱과 트랜잭션 경계는 서로 다른 문제이고, 하나를 고쳤다고 다른 하나가 저절로 좋아지지 않는다.

## 재현 방법

```bash
# 시드
"C:\Program Files\PostgreSQL\17\bin\psql.exe" -h localhost -p 5434 -U postgres -d yourtrip -f scripts/sql/seed-benchmark.sql

# 빌드 + 실행 (JFR 포함)
./gradlew bootJar
java -XX:StartFlightRecording=name=bench,settings=profile -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -Dserver.port=8080 -jar build/libs/yourtrip-0.0.1-SNAPSHOT.jar

# 고정 프로파일
k6 run -e DOMAIN=uploadcourse -e MODE=single -e CONCURRENCY=50 scripts/k6/detail-fixed.js

# ramping 프로파일 (포화점 규명)
k6 run -e DOMAIN=uploadcourse -e MODE=pool scripts/k6/detail-ramping.js

# JFR 덤프 (앱을 강제 종료하기 전에!)
jcmd <PID> JFR.dump filename=results/dump.jfr
jfr print --events jdk.ExecutionSample --stack-depth 64 results/dump.jfr | node scripts/jfr/parse-execution-samples.mjs

# 서명 마이크로벤치마크
./gradlew benchmarkTest

# Grafana 대시보드
docker compose up -d prometheus grafana
# http://localhost:3000 (admin/admin) → Dashboards → Bottleneck Test → Presign CPU Bottleneck
```

## 한계

- **12코어 dev 머신 ↔ 배포 타겟 t3.micro(1코어)의 괴리**는 이번에도 해소되지 않았다. CPU 경합이 핵심 기전이므로, 코어 수가 극단적으로 적은 실제 배포 환경에서는 이번에 관측된 "system_cpu_usage가 세션 중 1.0을 찍는" 현상이 훨씬 쉽게, 훨씬 오래 발생할 가능성이 높다 — 즉 이번 실측이 오히려 **과소평가**됐을 수 있다.
- **개발 머신의 다른 프로세스(Docker Desktop, IDE, Gradle 데몬 등)가 `system_cpu_usage`(OS 전체)에 섞여 들어간다.** `process_cpu_usage`(JVM 프로세스 전용)를 1차 지표로 삼은 이유가 이것이지만, 완전히 격리된 환경은 아니었다.
- **p99 지연시간을 이번 k6 스크립트가 기본 summary에 포함하지 않아 누락됐다.** 과거 측정과의 완전한 수치 비교에는 재측정이 필요하다.
- **JFR 분석은 세션 전체(모든 run 합산) 기준이라 시나리오별로 분리되지 않는다.** 고정 프로파일과 ramping 프로파일의 CPU 프로파일을 구분하려면 run마다 별도 덤프가 필요하다 — 이번엔 시간 비용 때문에 세션당 1회 덤프로 타협했다.
- **포화점(knee) 시계열 분석(직접 증거 4)은 uploadcourse(pool)/arm P 조합 하나만 수행했다.** mycourse나 arm C(ECDSA)에서도 VU 10~20 부근에서 같은 패턴이 나타나는지는 검증하지 않았다 — 서명 비용이 다르므로(ECDSA가 더 무거움) 정확한 knee 위치는 다를 수 있다.
- **Prometheus는 데이터를 영속 볼륨 없이 컨테이너 안에만 저장한다**(TASK-0.md에서 의도적으로 그렇게 설계). 측정 세션 도중 컨테이너가 재기동되면 그 시점까지의 시계열이 전부 사라진다 — 실제로 이번 작업 중 한 번 이 문제로 최초 ramping run의 상세 시계열을 잃어버려 재측정해야 했다. `직접 증거 2`의 세션 전체 peak(`process_cpu_usage` 0.566)가 정확히 어느 run에서 나왔는지 사후에 특정하지 못하는 것도 이 때문이다.
