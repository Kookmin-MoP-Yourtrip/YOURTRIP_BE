# TASK-PRESIGN-BOTTLENECK. presigned URL CPU 병목 가설 실측 검증

> PR #57("이미지 URL 발급 방식을 CloudFront 기반으로 전환")은 "presigned URL 발급의 CPU 비용이 상세 조회 API의 병목"이라는 가설 위에서 진행됐다. 이 문서는 그 가설을 이미지 URL 발급 방식을 PR #57 이전(S3 presign)으로 되돌린 뒤, CPU 프로파일링(JFR)과 인프라 지표(Prometheus/HikariCP)로 직접 검증한 기록이다.

## 배경 — 지금까지 한 번도 직접 측정하지 않은 가설

가설의 발원지는 [TASK-4.md:91](TASK-4.md)이고, 원문 표현부터가 추정이었다:

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

## 판정

계획 문서의 4-way 판정 기준표(원 가설 성립 / 경쟁 가설① / 경쟁 가설② / 제3 원인)에 실측치를 넣어보면 **어느 한 줄에도 깔끔하게 들어맞지 않는다** — `process_cpu_usage`가 1.0에 붙지도 않았고(0.5~0.57), 그렇다고 "CPU가 낮다"고 하기에는 JFR 서명 샘플 비율이 24~54%로 결코 낮지 않다. 실측이 보여주는 건 **두 요인의 합성**이다.

1. **서명 CPU 비용은 실재한다** (원 가설의 핵심 부분은 참). JFR이 이를 직접 증명한다 — arm C에서 ECDSA 타원곡선 연산이 leaf 프레임 1위, crypto 카테고리가 전체 CPU 샘플의 55%.
2. **그러나 그 자체만으론 관측된 처리량 저하의 규모를 설명하지 못한다.** 마이크로벤치마크 기반 이론 최대 TPS(uploadcourse hot, 동시성 50, arm P)는 3,139인데 실측은 152.6 — 이론치의 4.9%에 불과하다. 순수 CPU 바운드였다면 이만큼 벌어지지 않는다.
3. **격차의 진짜 원인은 "서명이 JDBC 트랜잭션 경계 안에서 실행된다"는 구조다** (경쟁 가설 ①). 서명 CPU 비용(실재하지만 절대값은 작다)이 **커넥션 점유 시간을 증폭**시키고, 그 증폭된 점유 시간이 **HikariCP 풀(기본 10) 앞에서 대기 행렬을 만들며 병목을 극적으로 키운다.** 즉 CPU 비용은 "방아쇠"고, 커넥션 풀 경합이 "증폭기"다.

이 결론은 원 가설을 부정하지 않는다 — 오히려 "presign이 무료가 아니다"는 로드맵의 반성(TASK-4.md)이 방향은 맞았음을 확인해준다. 다만 **처방이 달라진다**: PR #57~#62가 택한 "서명 알고리즘 자체를 가볍게 만든다"(RSA→ECDSA)는 방아쇠(서명 비용)를 줄이는 접근이라 유효했지만, 증폭기(트랜잭션 안에서 커넥션을 쥔 채 서명)를 직접 건드리지 않았다. **서명 호출을 트랜잭션/커넥션 스코프 밖으로 빼내는 것**이 더 근본적인 처방일 수 있다는 뜻이다.

## PR #61(병렬 서명) 재해석

TASK-CLOUDFRONT.md는 PR #61의 병렬화 효과가 미미했던(+2~11%) 원인을 "HikariCP 풀 크기 병목으로 추정된다"고 **추측**으로만 남겼다. 이번 실측이 그 추측을 **직접 뒷받침한다**:

- 병렬화는 "한 요청 안에서 N장의 이미지를 동시에 서명"하는 최적화다 — 요청 하나의 서명 소요 시간(따라서 그 요청의 커넥션 점유 시간)은 줄일 수 있다.
- 그러나 **동시성 200에서는 서로 다른 200개 요청이 각자 병렬 서명을 시도**하며 12개 물리 코어를 놓고 경쟁한다. 시스템 전체가 이미 CPU 경합 상태(system_cpu_usage가 세션 중 1.0을 찍음)라면, "요청 내부 병렬화"가 실제로 확보할 수 있는 여유 코어가 없어 기대만큼의 단축 효과가 나지 않는다.
- 즉 병렬화는 "커넥션을 쥐는 시간을 줄인다"는 방향 자체는 맞았지만, **애초에 코어가 부족한 상황에서는 병렬화도 결국 같은 자원(CPU)을 두고 경쟁**하므로 효과가 희석된다. 진짜 레버리지는 (a) 서명 비용 자체를 낮추거나(ECDSA 전환, 이미 함) (b) 서명을 트랜잭션 밖으로 빼서 커넥션 점유 자체를 없애는 것이다.

## 발견한 부가 이슈 (이번 범위 밖, 기록만 남김)

- **arm P의 JFR leaf 프레임에서 `java.net.URL.<init>`, `org.springframework.boot.loader.jar.NestedJarFile.hasEntry`, `ZipContent.getFirstLookupIndex` 등 Spring Boot 실행 가능 fat jar의 nested-jar 클래스로더 관련 프레임이 유의미하게 등장한다**(수 % 대). `S3Presigner.presignGetObject`가 내부적으로 `new URL(...)`을 호출하는데, 이것이 fat jar 실행 환경(`java -jar`, 이번 벤치마크와 실제 배포 방식 동일)에서 nested-jar 프로토콜 핸들러 조회를 유발해 예상외의 비용을 더할 가능성이 있다. 다만 이 가설은 마이크로벤치마크(일반 클래스패스에서 실행, fat jar 아님)에서도 동일하게 느린 수치(382us)가 나온 것과는 완전히 정합하지 않아, 후속 검증이 필요한 별도 주제로 남긴다.
- **JFR `dumponexit=true` + 강제 종료(`taskkill /F`) 조합은 빈 파일을 만든다** — 셧다운 훅이 생략되기 때문. 앱이 살아있는 상태에서 `jcmd <PID> JFR.dump`로 명시적으로 덤프하는 방식이 안전하다.

## 이번 작업에서 얻은 교훈 (포트폴리오 포인트)

1. **"병목을 고쳤다"와 "병목의 원인을 안다"는 다른 주장이다.** PR #57~#62의 end-to-end A/B 벤치마크는 "무엇이 빨라졌는가"에는 정확했지만 "왜 빨라졌는가"의 물리적 메커니즘까지는 증명하지 못했다. CPU 프로파일링(JFR)과 인프라 지표(HikariCP)를 직접 붙였을 때만, 처음으로 "서명 CPU가 방아쇠, 커넥션 풀 경합이 증폭기"라는 구체적 메커니즘이 드러났다.
2. **잔차 기반 추론(TASK-4.md)은 방향은 맞아도 메커니즘까지 알려주지 않는다.** "DB 쿼리를 없앴는데 TPS가 그만큼 안 올랐다 → 뭔가 다른 게 있다"는 추론은 유효한 신호였지만, 그 "뭔가"가 CPU 그 자체인지 CPU가 유발하는 2차 효과(커넥션 점유)인지는 end-to-end 측정만으로는 구분할 수 없었다.
3. **이론적 상한(마이크로벤치마크 기반)과 실측치의 괴리 자체가 진단 도구가 된다.** 실측이 이론 최대의 5% 미만이라는 사실 하나가, "CPU가 유일한 병목"이라는 가설을 통계적으로 반증하고 다른 증폭 기전을 찾게 만든 결정적 단서였다.
4. **판정 기준을 미리 정해두어도(4-way 표) 현실은 합성 원인일 수 있다.** 실측 전에는 "CPU냐 커넥션이냐 로깅이냐"를 배타적 선택지로 설계했지만, 실측 결과는 "CPU 비용(실재)이 커넥션 경합(진짜 증폭기)을 유발"하는 인과 사슬이었다. 사전 설계한 판정표가 실측과 깨끗하게 안 맞을 때, 그 불일치 자체를 억지로 끼워 맞추지 않고 정직하게 재해석하는 것이 이번 작업에서 가장 중요했던 지점이다.

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
