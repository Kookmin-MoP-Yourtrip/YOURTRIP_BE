# AbortPolicy 전환 + CloudFrontSigningGate 검증 결과

> [callerruns-verification.md](callerruns-verification.md)가 실측으로 확정한 원인 — `cloudFrontSigningExecutor`의 `CallerRunsPolicy`가 VU200 근처에서 큐+풀 용량을 초과해 Tomcat 요청 스레드로 서명 작업이 역류하고, 그 CPU 경합이 HikariCP 커넥션 점유시간을 늘림 — 에 대한 해결책을 설계하고 EC2에서 재검증한 기록이다. 원인 규명(4번째 실측 라운드까지)은 [callerruns-verification.md](callerruns-verification.md)를, 이 문서는 그 해결책(거부 정책 전환 + 요청 단위 세마포어 게이트)의 설계·구현·검증만 다룬다.

## CallerRunsPolicy 도입 경위

`CallerRunsPolicy`가 왜 애초에 선택됐는지를 git 히스토리로 규명했다. Spring `ThreadPoolTaskExecutor`의 기본 거부 정책은 `AbortPolicy`다(`ExecutorConfigurationSupport`가 `new ThreadPoolExecutor.AbortPolicy()`로 초기화) — 즉 `CallerRunsPolicy`는 누군가 명시적으로 덮어쓴 선택이었다.

[306505b](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/commit/306505b) "perf: 나의 코스 상세조회 Signed URL 발급 병렬화 및 개인키 캐싱"에서 `CloudFrontExecutorConfig.java`가 처음 생성된 그 커밋에 이유가 주석으로 남아 있다: `// 큐가 가득 차면 제출 스레드가 직접 실행해 자연스럽게 degrade.` 같은 커밋 메시지의 "일부 이미지 서명이 실패해도 전체 요청을 실패시키지 않는다"는 fail-open 철학과 일관된 선택이었다.

**그런데 그 시점의 `getPlaceListByDay`에는 `@Transactional(readOnly = true)`가 붙어 있었다** — 서명이 트랜잭션 안에서 실행됐다는 뜻이다. 그러면 HikariCP 풀(10)이 동시에 executor에 태스크를 제출할 수 있는 요청 수를 자동으로 10개로 묶는다. 요청당 태스크 수(이미지 10장 기준)를 곱해도 최대 동시 태스크는 100개로, 큐 용량(100)을 넘지 못한다 — **`CallerRunsPolicy`가 발동할 조건이 구조적으로 성립하지 않았다.** 실제로 같은 커밋의 문서 변경분(`TASK-CLOUDFRONT.md`)에는 "재측정 결과: (추후 채움)"이라고만 적혀 있어, 이 분기가 도입 후 한 번도 실행되지도, 재측정되지도 않았음을 확인할 수 있다.

0단계(트랜잭션 분리)가 이 우연한 유량제한을 걷어내자, 4개월 가까이 도달 불가능했던 분기가 갑자기 주경로가 됐다 — 초당 약 1,450회, 총 413,366회. **이건 "잘못된 선택을 했다"는 이야기가 아니라, "한 곳의 최적화(0단계)가 다른 곳(CallerRunsPolicy)의 암묵적 전제를 조용히 무너뜨렸다"는 이야기다.** fail-open 철학 자체는 유지하되(입장 게이트 거부 시 이미지 개별 실패가 아니라 요청 단위 503으로 전환하는 트레이드오프는 별도로 있다 — 아래 "구현 방식 검토" 참고), 실행 메커니즘을 `AbortPolicy` + 요청 단위 세마포어 게이트로 교체한 근거가 이 경위에서 나온다.

## `PRESIGN-BOTTLENECK-FIX.md` 3단계 서술 정정

[PRESIGN-BOTTLENECK-FIX.md의 3단계](../../PRESIGN-BOTTLENECK-FIX.md)는 "`cloudFrontSigningExecutor`가 Bulkhead 패턴의 절반은 이미 구현돼 있었고, 0단계가 적용되면 이 실행자가 비로소 제 역할을 하게 된다"고 온건하게 서술했다. [callerruns-verification.md](callerruns-verification.md)의 실측은 이 서술을 정정한다 — 실행자가 "제 역할을 하게" 된 게 아니라 **압도적으로 오버플로우해 CallerRunsPolicy로 새는 상태**였다.

**"절반 구현"이라는 표현을 더 정확히 하면**: 별도 스레드풀로 서명 작업을 분리해둔 것 자체는 Bulkhead의 겉모습(구조)을 갖췄지만, Bulkhead의 실질적 목적("한쪽의 과부하가 다른 쪽으로 전염되지 않게 막는 것")은 지금 구조에서 보장되지 않는다. `CallerRunsPolicy`는 큐가 가득 찼을 때 "거부된 작업을 제출 스레드가 직접 실행"하는 정책인데, 여기서 제출 스레드는 Tomcat 요청 스레드다 — 즉 격리벽이 가장 필요한 과부하 순간에 격리벽 스스로가 열려 서명 작업이 요청 스레드로 역류한다. 즉 "3단계(Bulkhead 정식화)"는 없던 걸 새로 만드는 게 아니라, **지금의 무늬만 격리인 구조를 실제로 격리가 보장되는 구현으로 교체하는 것**을 뜻한다 — 이 실측이 그 교체의 필요성을 뒷받침한다.

### 구현 방식 검토 — Resilience4j를 쓰지 않고 직접 구현한 이유

이 절은 처음엔 Resilience4j `@Bulkhead(type = Bulkhead.Type.THREADPOOL)`로의 전환을 제안했다. 실제 설계 단계에서 두 타입(THREADPOOL/SEMAPHORE)을 모두 검토했고, 최종적으로는 어느 쪽도 채택하지 않고 `AbortPolicy` 전환 + `java.util.concurrent.Semaphore` 기반 `CloudFrontSigningGate`를 직접 구현했다.

- **`Bulkhead.Type.THREADPOOL` 기각**: 내부가 `ThreadPoolExecutor` + bounded queue이고, 가득 차면 `BulkheadFullException`을 던진다 — `ThreadPoolExecutor` + `AbortPolicy`와 **기능적으로 동일**하다(예외 이름만 다르다). 의존성 2개(`resilience4j-spring-boot3`, `spring-boot-starter-aop`)를 추가해 한 줄로 되는 걸 얻는 셈이다. 게다가 이 코드베이스의 서명은 요청 1건이 이미지 수만큼(장당 1개) 태스크를 fan-out하는 구조인데, ThreadPoolBulkhead의 거부는 태스크 단위라 "10장을 한 묶음으로 예약"할 수 없어 부분 응답이 상시화된다. 자기 executor를 소유해 `ExecutorServiceMetrics` 배선도 다시 짜야 하고, 반환 타입을 `CompletableFuture`로 강제해 서비스 시그니처를 오염시킨다.
- **`Bulkhead.Type.SEMAPHORE` 기각(다만 THREADPOOL보다 훨씬 가까운 후보였다)**: 내부가 `Semaphore` + `tryAcquire(maxWaitDuration)`이라 최종 채택안과 하는 일이 사실상 같고, 프로그래밍 방식으로 쓰면 AOP 없이 `resilience4j-bulkhead` 코어 하나로 충분하다. 기각한 이유는 **비례성**이다 — 게이트 전체(약 60줄) 중 permit 획득/반납은 6줄뿐이고, 나머지(이미지 수 하드 캡 검사, 태스크 제출과 `RejectedExecutionException` catch, 데드라인 안전망, 부분 응답 수확)는 어차피 직접 작성해야 한다. 그 6줄은 JDK 표준 클래스이자 Resilience4j가 감싸고 있는 바로 그 클래스다. 메트릭 이름도 `resilience4j.*`로 붙어 기존 `cloudfront.signing.*`과 혼재한다. 이 판단은 CircuitBreaker·RateLimiter·Retry 중 하나라도 함께 도입할 때(예: Gemini·Kakao·S3 외부 호출에 Retry 도입) 뒤집을 수 있다 — 그때는 한 번의 도입으로 여러 개를 쓰므로 비례가 맞는다.
- **최종안**: `CloudFrontExecutorConfig`의 거부 정책을 `CallerRunsPolicy` → `AbortPolicy`로 바꾸고, `CloudFrontSigningGate`(요청 단위 `Semaphore`)를 앞단에 둬 부하 차단을 태스크가 아니라 요청 단위로 옮겼다. `permits`는 `(풀 크기 × 지연예산) / (요청당 이미지 수 × 서명 1회 비용)`으로 산정하고 환경변수로 노출해, 값을 극단적으로 크게(예: 100000) 두면 게이트가 사실상 비활성화되어 "AbortPolicy 단독" 동작과 같아진다 — 재배포 없이 A/B 측정이 가능하다. 세부 구현(permit 인플레이션 방지, `orTimeout()` 대신 요청당 데드라인 1개, executor 큐 사이징 등)은 [MyCourseServiceImpl.java](../../../../../src/main/java/backend/yourtrip/domain/mycourse/service/MyCourseServiceImpl.java)와 [CloudFrontSigningGate.java](../../../../../src/main/java/backend/yourtrip/global/cloudfront/service/CloudFrontSigningGate.java)를 참고.

### 실무 근거 — CallerRunsPolicy가 격리 목적 풀에 부적절하다는 지적이 실제로 있는가

위 정정이 이 저장소만의 해석이 아니라는 근거를 별도로 조사했다.

- **Resilience4j `ThreadPoolBulkhead`는 실제로 CallerRunsPolicy 방식을 쓰지 않는다** — 큐+풀이 가득 차면 즉시 `BulkheadFullException`으로 거부한다([resilience4j.readme.io/docs/bulkhead](https://resilience4j.readme.io/docs/bulkhead)). "진정한 스레드 격리는 전용 스레드풀에서만 실행할 때 성립하며, 오버플로우를 호출 스레드로 넘기는 순간 그 격리는 깨진다"는 게 이 설계의 근거로 통용된다.
- **`ThreadPoolExecutor.CallerRunsPolicy`의 Java 공식 Javadoc**(Java SE 21)은 이 정책을 "새 작업이 제출되는 속도를 늦추는 간단한 피드백 제어 메커니즘"이라고 설명한다 — 이 설명은 **제출 스레드가 오직 이 executor에 작업을 넘기는 일만 하는 producer**라는 상황을 전제한다. 지금 사례처럼 제출 스레드가 `maxThreads=200`짜리 공유·유한 자원(Tomcat)이고 다른 무관한 요청도 처리해야 한다면 이 전제가 깨진다.
- **SEI CERT(카네기멜론 소프트웨어공학연구소) Java 시큐어 코딩 표준 `TPS01-J`**: "경계가 있는 스레드풀 안에서 상호의존적인 작업을 실행하지 마라"([cmu-sei.github.io](https://cmu-sei.github.io/secure-coding-standards/sei-cert-oracle-coding-standard-for-java/rules/thread-pools-tps/tps01-j)) — Tomcat 풀이 signing 풀에 의존하고, signing 풀의 오버플로우가 다시 Tomcat 풀로 역류하는 지금 구조가 정확히 이 패턴에 해당한다.
- **CPU-bound 작업과 I/O-bound 작업을 같은 풀에 섞지 말라는 원칙**은 여러 스레드풀 튜닝 자료에 공통으로 등장한다 — CPU-bound 작업은 스레드 수를 코어 수에 근접하게 유지해야 컨텍스트 스위칭 오버헤드를 피할 수 있고, I/O-bound 작업은 코어 수보다 훨씬 많은 스레드가 유리하다는 반대되는 요구사항을 갖기 때문이다. 이번 사례는 CPU-bound 풀(ECDSA 서명)의 오버플로우가 I/O-bound 특성의 공유 풀(Tomcat)로 역류한 것이라 이 원칙을 정면으로 위반한다.

**"느려지더라도 처리하는 게 아예 거부하는 것보다 낫지 않은가"라는 반론에 대한 답**: 이 직관은 제출 스레드가 유휴 대기 중이던 I/O-bound 상황(예: 네트워크 응답을 기다리며 어차피 놀고 있던 스레드)에는 타당하다 — 이때 CallerRunsPolicy는 손실이 적은 자연스러운 배압(backpressure)이다. 하지만 이번 사례처럼 제출 스레드가 CPU가 희소한 환경에서 다른 중요한 작업(DB 트랜잭션 처리)과 코어를 직접 두고 경합하게 되는 CPU-bound 상황에서는, "느려도 처리"가 실제로는 "무관한 작업까지 함께 느려짐"으로 번져 전체 시스템 처리량을 더 크게 깎아먹는다 — [callerruns-verification.md](callerruns-verification.md)의 실측(CPU 98%, Tomcat 스레드 80%가 서명 연산 점유, 응답 지연 증가)이 그 근거다. 실무에서 흔히 쓰는 대안(AbortPolicy+재시도, 429 응답, DiscardOldestPolicy, 속도 제한, 별도 큐잉)은 전부 "격리된 자원의 과부하를 무관한 공유 자원으로 전가하지 않는다"는 방향으로 수렴한다.

## EC2 실측 검증

핵심 설계 판단, 구현, EC2 측정 결과를 아래 순서로 정리한다.

### 핵심 설계 판단

**AbortPolicy 전환만으로도 CPU 격리는 복원된다.** 큐에 들어간 태스크를 기다리는 Tomcat 스레드는 park 상태(CPU 0%)고, 거부된 태스크는 아예 실행되지 않는다. 그런데 그것만으로는 부족한 이유가 있다.

t3.small 실측 역산(서명 1회 ≈ 0.7ms, 스레드 2개) 기준, AbortPolicy 단독의 VU200 평형 상태:

| | 값 |
|---|---|
| 서명 처리 상한 | 2,857 signs/s = **285 req/s** |
| 큐(100) 최대 대기 | 약 35ms → 응답이 빨라짐 |
| 닫힌 루프 제공 부하 | 200 VU / 0.05s ≈ **4,000 req/s** |
| 거부되는 서명 비율 | **약 93%** (요청당 10장 중 평균 0.7장만 성공) |

즉 **모든 응답이 조금씩 망가진 채 `200 OK`로 나간다.** [detail-ramping.js:42](../../../../../scripts/k6/detail-ramping.js)의 체크는 `status is 200`뿐이라 이 브라운아웃이 지표에 잡히지도 않는다. 사용자에게는 "사진이 사라진 화면"으로 보인다.

**세마포어 게이트의 가치는 부하 차단을 태스크 단위가 아니라 요청 단위로 옮기는 것**이다. 같은 부하에서 285 req/s는 이미지가 전부 붙은 완전한 응답을 받고 나머지는 명확한 503을 받는다. 처리량은 동일하다(수식상 permits가 소거된다 — permits는 처리량이 아니라 지연 예산을 정한다). 바뀌는 것은 피해의 분포다.

부차적으로 게이트는 **고아 태스크**(10장 중 7번째에서 거부돼도 앞의 6개는 이미 CPU를 씀)를 제거하고, 지연을 큐 크기라는 간접 손잡이 대신 명시적 예산으로 제어한다.

### 측정 방법

앞서 정정한 구현(`AbortPolicy` + `CloudFrontSigningGate`, permits는 EC2 실측 서명비용 368us/op 기준 54로 산정)을 App EC2(t3.small)에서 재기동해 검증했다. `CLOUDFRONT_SIGNING_PERMITS` 환경변수만 바꿔 같은 배포로 두 arm을 순차 측정했다:

- **Run A**(게이트 사실상 비활성, `PERMITS=100000`) — AbortPolicy 단독 효과만 분리 관측
- **Run B**(게이트 활성, `PERMITS=54`)
- **Run C**(`CloudFrontExecutorConfig`의 거부 정책만 `AbortPolicy`→`CallerRunsPolicy`로 임시로 되돌리고, 나머지는 Run A와 완전히 동일: `PERMITS=100000`, 큐 640) — [callerruns-verification.md](callerruns-verification.md)의 4번째 라운드(닫힌 루프로 측정)와 같은 정책을 **Run A/B와 동일한 열린 루프 조건에서** 재측정해, 부하 모델 차이 없이 "CallerRunsPolicy vs AbortPolicy"만 순수 비교하기 위해 추가했다. 측정 후 코드는 즉시 `AbortPolicy`로 원복했다(커밋 대상 아님, 측정 전용 임시 변경).

부하는 `scripts/k6/detail-arrival-rate.js`(열린 모델, `ramping-arrival-rate`, 도착률 10→50→100→200→400 req/s, 290초)로 mycourse만 걸었다 — `detail-ramping.js`(닫힌 루프)는 A/B/C 어느 것에도 쓰지 않았다(위 "핵심 설계 판단"에서 다룬 것처럼 닫힌 루프에서는 성공률이 거부 속도의 함수가 돼 개선할수록 지표가 나빠지는 역설이 생기기 때문). 체크도 `status`뿐 아니라 응답에 담긴 이미지 개수(기대값 10장)까지 확인했다.

`permits`(54)·큐 크기(640) 산정에 쓴 서명 1회 비용(368us/op)은 `SigningBenchmarkTest`와 동일한 로직을 EC2에서 직접 실행해(`BenchmarkMain`, JUnit/Gradle 없이 `app.jar`의 BOOT-INF 클래스패스만으로 컴파일·실행) 얻은 값이다 — dev 머신 기반 최초 추정치(0.7ms)보다 낮게 나왔다.

### 측정 결과

세 arm(Run C·A·B) 모두 같은 열린 루프(`detail-arrival-rate.js`), 같은 시드, 같은 인스턴스에서 측정해 직접 비교 가능하다. [callerruns-verification.md](callerruns-verification.md)의 4번째 라운드(닫힌 루프)는 부하 모델이 달라 이 문서에는 싣지 않았다.

| 지표 | Run C: CallerRunsPolicy | Run A: AbortPolicy 단독 | Run B: AbortPolicy+게이트 |
|---|---|---|---|
| HikariCP 평균 커넥션 점유시간(mycourse) | 16.28ms | **5.79ms** | **6.95ms** |
| JFR `http-nio-*` 스레드의 `presign_or_signing` 히트 비율 | 35.86% | **0.00%** | **0.00%** |
| JFR `http-nio-*` 스레드의 `crypto` 히트 비율 | 51.39% | 57.37% | 29.78% |
| 응답 이미지 완전성(200 응답 중 10장 전부 수신) | 88.4%(브라운아웃 11.6%) | 50.6%(브라운아웃 49.4%) | 78.0%(브라운아웃 21.0%) |
| 실행자 레벨 큐+풀 오버플로우 발생 횟수 | 203,146(요청 스레드로 역류) | 203,779(태스크 조용히 폐기) | 60,788(태스크 조용히 폐기) |
| 게이트 레벨 요청 거부(503) | 0(게이트 비활성) | 0(게이트 비활성) | 15,140 |
| 게이트 데드라인 안전망 발동(2초 초과) | **3,943** | 0 | 774 |
| CPU(CloudWatch, 구간 피크) | 99.42% | 99.1% | 99.7% |
| 달성 처리량(`http_reqs`) | 111.10 req/s | 151.25 req/s | 151.46 req/s |
| `http_req_duration` avg / p95 | **3.02s** / **7.63s** | 569.13ms / 2.63s | 505.48ms / 2.37s |

**판정 1 — HikariCP 점유시간은 압도적으로 회복됐다.** 목표(0단계 이전 수준인 47.3ms 이하 복귀)를 Run A·B 모두 큰 폭으로 넘어섰다(5.79ms/6.95ms, 목표 대비 12~14% 수준). `AbortPolicy` 전환만으로도 이미 달성됐고, 게이트 추가가 이 지표를 더 개선하지는 않았다(오히려 소폭 상승 — permit 대기·태스크 재제출 오버헤드로 추정, 그래도 목표치의 15% 수준이라 무시할 크기).

**판정 2 — 격리가 완전히 복원됐다는 가장 정밀한 증거는 `presign_or_signing`이지 `crypto`가 아니었다.** 사전에 세운 판정 기준("http-nio 스레드의 crypto 비율 < 5%")은 이번 실측으로 반증됐다 — Run A/B 모두 crypto 비율이 30~57%로 5%에 한참 못 미쳤다. 그런데 CloudFront 서명 프레임만 특정하는 `presign_or_signing` 카테고리는 두 arm 모두 **정확히 0.00%**였다. 원인을 추적한 결과, `crypto` 카테고리는 `java.security.*`/`sun.security.*` 패키지 전체를 포함해 **매 요청 필수적으로 발생하는 JWT(HS256) 서명 검증**(`io.jsonwebtoken`이 내부적으로 `javax.crypto.Mac` 사용)까지 잡아낸다 — 이건 CallerRunsPolicy와 무관하게 항상 존재하는 정상 비용이다. 즉 **사전에 세운 판정 기준 자체가 부정확했다**는 것을 실측이 드러냈고, 실제로 문제를 특정하는 지표는 `presign_or_signing`이었다. 이 정정 자체를 결과로 남긴다 — 판정 기준을 미리 정해도 실측이 그 기준의 결함을 드러낼 수 있다는 사례다.

**판정 3 — 게이트는 브라운아웃을 절반 이하로 줄였지만 0으로 만들지 못했다.** AbortPolicy 단독(Run A)에서는 응답의 49.4%가 이미지 일부 누락(평균 10장 중 상당수)이었는데, 이게 `status: 200`으로 나가 k6 기본 체크로는 전혀 안 잡혔다 — 정확히 이 검증이 우려했던 "모든 응답이 조금씩 망가진 채 나간다"는 브라운아웃이 실측으로 확인됐다. 게이트(Run B)는 이를 21.0%까지 줄였고, 남은 15,140건은 태스크가 아니라 **요청 단위의 명확한 503**으로 처리됐다(`cloudfront_signing_gate_rejected_total`이 정확히 이 수와 일치). 다만 게이트를 통과한 요청 중에도 6,485건(21%)이 여전히 이미지 일부를 못 받았다 — `cloudfront_signing_gate_submit_rejected_total`(=executor 레벨 `cloudfront_signing_rejected_total`) 60,788건이 그 원인이다. **큐(640) 사이징이 실전 경합 부하에서는 부족했다.** 사이징 공식(`queue ≥ permits × 이미지수 = 540`)에 쓴 서명비용(368us/op)은 **단일 스레드가 경합 없이 반복 실행한 값**인데, 실제로는 여러 요청이 동시에 permit을 쥐고 동시에 태스크를 쏟아내는 순간 큐가 이론치보다 빨리 찬다 — 경합 하의 실효 서명비용이 벤치마크보다 높았다는 뜻이다. `cloudfront_signing_gate_deadline_exceeded_total`도 774건 발동해, "정상 부하에서는 발동 금지"라고 설계했던 안전망이 실제로는 발동할 만큼 부하가 컸다는 것도 함께 드러났다.

**판정 4 — 게이트의 안전장치(permit 인플레이션 방지)는 설계대로 동작했다.** 세 run 종료 후에도 `cloudfront_signing_gate_permits_available`은 항상 초기값(54 또는 100000)으로 정확히 돌아왔다 — 부하 15만 건 이상을 거치는 동안 permit 누수가 전혀 없었다는 뜻이다. 단위 테스트(`CloudFrontSigningGateTest.signAll_PermitCountIsInvariant`류)로 잡으려 했던 바로 그 버그 클래스가 실제 부하에서도 발생하지 않았음을 확인했다.

**판정 5 — Run C(같은 열린 루프에서 측정한 CallerRunsPolicy)로 순수 비교하면, AbortPolicy가 HikariCP 점유시간·처리량·지연 세 축 모두에서 명확히 우월했다.** 부하 모델 차이를 걷어내고 Run C→A만 비교하면(둘 다 게이트 비활성, `PERMITS=100000`, 차이는 거부 정책 하나뿐):

- HikariCP 점유시간: 16.28ms(Run C) → 5.79ms(Run A) — **2.8배 개선**. 참고용이던 닫힌 루프 수치(53.3ms)보다 Run C가 이미 낮게 나온 것도 흥미로운데, 열린 루프의 `dropped_iterations`가 CallerRunsPolicy 아래서 유독 컸다(12,805건 — Run A의 943건, Run B의 852건보다 10배 이상)는 점을 보면, 응답이 워낙 느려져(avg 3.02s) k6가 `vus_max`(1,000)에 막혀 실제 동시 부하 자체가 제한됐을 가능성이 있다 — 순수하게 "정책 때문"이라기보다 "느려진 결과로 부하가 스스로 줄어든 효과"가 일부 섞였을 수 있다는 뜻이다(아래 "한계" 참고).
- 처리량: 111.10 req/s(Run C) → 151.25 req/s(Run A) — **36% 더 높다.**
- 지연: avg 3.02s/p95 7.63s(Run C) → avg 569ms/p95 2.63s(Run A) — **5~3배 개선.**
- JFR `presign_or_signing`이 `http-nio-*` 스레드에서 35.86%(Run C, CallerRunsPolicy가 실제로 발동했다는 직접 증거) → 0.00%(Run A)로 완전히 사라졌다.

**다만 흥미로운 역설도 함께 드러났다 — 완전성(브라운아웃)만큼은 Run C가 오히려 Run A보다 나았다(11.6% vs 49.4%).** 이유는 두 정책의 "실패 시맨틱"이 근본적으로 다르기 때문이다.

- **CallerRunsPolicy는 큐+풀이 가득 차도 태스크를 버리지 않는다** — 제출 스레드가 그 자리에서 직접 실행해서라도 끝낸다. 그래서 큐+풀 오버플로우가 203,146번 일어났어도(Run A의 203,779번과 거의 같은 규모) 이미지는 결국 대부분 서명된다(완전성 88.4%) — 다만 그 대가로 모든 요청의 지연이 늘어난다.
- **AbortPolicy는 큐+풀이 가득 차면 태스크를 즉시 폐기한다** — `cloudfront_signing_rejected_total`(Run A 203,779건)만큼의 이미지가 영영 서명되지 않는다. 그 대신 폐기가 즉각적이라 다른 요청의 지연에는 영향을 안 준다.

Run C에 남은 11.6%의 브라운아웃도 CallerRunsPolicy 자체가 만든 게 아니다 — 게이트의 2초 데드라인 안전망(`cloudfront_signing_gate_deadline_exceeded_total` 3,943건)이 극단적으로 느려진 일부 요청을 강제로 끊어낸 결과이고, 이 수는 `partial_responses`(3,943건)와 정확히 일치한다.

**정리하면 두 정책은 "지연 vs 완전성"의 트레이드오프 관계다** — CallerRunsPolicy는 느리지만 결국 다 해주고, AbortPolicy는 빠르지만 일부를 버린다. 이번 작업이 AbortPolicy를 택한 근거(HikariCP 격리 복원)는 이 트레이드오프와는 **별개의, 더 근본적인 문제**(내 요청의 지연이 아니라 무관한 다른 요청의 DB 처리가 굶주리는 것)를 해결하기 위함이었다 — 게이트는 AbortPolicy가 가져온 "완전성 손실"이라는 부작용을, 조용한 브라운아웃 대신 요청 단위의 명확한 거부로 다시 통제하려는 절충안이다.

**결론**: 원래 목표(DB 트랜잭션과 CPU 격리를 복원해 HikariCP 점유시간을 회복하는 것)는 `AbortPolicy` 전환만으로 이미 완전히 달성됐다 — 이번엔 부하 모델까지 통제한 Run C→A 비교로 그 인과관계가 한 번 더, 더 깨끗하게 확인됐다(16.28ms→5.79ms, 2.8배). 게이트는 그 위에 "브라운아웃 완화"라는 별도 가치를 더했지만(49.4%→21.0%), 완전히 없애지는 못했다. 큐 사이징을 실전 경합 계수를 반영해 다시 조정(예: 640 → 900~1000대, 또는 permits를 낮춰 재계산)하고 재측정하는 것이 다음 후속 과제로 남는다 — 이번 범위에서는 여기까지만 진행한다.

### 부록 — k6 원본 처리량·지연 수치 상세

위 "측정 결과" 표에 처리량·avg/p95는 이미 반영했다. 여기서는 min/med/max 등 나머지 세부값을 포함한 Run C·A·B의 전체 k6 원본 출력을 남긴다.

| 지표 | Run C: CallerRunsPolicy | Run A: AbortPolicy 단독 | Run B: AbortPolicy+게이트 |
|---|---|---|---|
| k6 스크립트 / 부하 모델 | `detail-arrival-rate.js` / 열린 루프(10→400 req/s) | `detail-arrival-rate.js` / 열린 루프(10→400 req/s) | `detail-arrival-rate.js` / 열린 루프(10→400 req/s) |
| 총 요청 수 | 33,992건 | 45,855건 | 45,944건 |
| `http_req_duration` min / med | 9.61ms / 2.27s | 5.72ms / 50.18ms | 7.29ms / 179.98ms |
| `http_req_duration` p90 / max | 6.82s / 10.43s | 2.05s / 5.38s | 1.56s / 5.48s |
| 성공(200) 응답만의 avg / p95 | (전체와 동일 — 503이 없으므로) | (전체와 동일 — 503이 없으므로) | 615.31ms / 2.69s |
| `http_req_failed`(non-2xx 비율) | 0.00%(게이트 비활성) | 0.00%(게이트 비활성) | 32.95%(15,140건 — 전부 게이트 503) |
| `dropped_iterations`(k6 VU 부족으로 미시도) | **12,805건** | 943건 | 852건 |
| `vus_max` | 1,000(사전 할당 300) | 1,000(사전 할당 300) | 1,000(사전 할당 300) |

**참고**: [callerruns-verification.md](callerruns-verification.md) 4번째 라운드(`detail-ramping.js`, 닫힌 루프)의 "체크 통과율 100%"는 이 정책이 안전했다는 뜻이 아니다 — 그 스크립트의 체크는 `status is 200`뿐이고, CallerRunsPolicy는 fail-open이라 이미지가 몇 장 빠지든 무조건 200을 반환한다. 이미지 완전성 체크가 없던 그 라운드에서는 브라운아웃이 있었는지조차 알 방법이 없었고, 위 Run C가 같은 정책을 이미지 완전성 체크와 함께(그리고 부하 모델까지 통일해) 재측정해 그 사각지대를 뒤늦게 메웠다.

**Run A vs Run B를 읽는 법**: Run A의 avg(569ms)가 Run B의 avg(505ms, 503 포함)보다 오히려 높게 나온다고 해서 "게이트가 없을 때가 더 느리다"로 읽으면 안 된다 — Run B의 505ms는 순식간에 끝나는 503 15,140건이 섞여 평균을 아래로 끌어내린 값이고, 성공(200) 응답만 떼어 보면 Run B가 오히려 더 높다(615ms). 게이트를 통과한 요청은 permit 대기·큐 재시도 등 추가 단계를 거치므로 개별 요청의 지연이 조금 늘고, 대신 그 요청이 받는 응답은 이미지가 더 온전하다(브라운아웃 49.4%→21.0%)는 트레이드오프로 해석해야 한다.

## 채택하지 않은 대안

| 항목 | 이유 |
|---|---|
| Resilience4j `Bulkhead.Type.THREADPOOL` | "구현 방식 검토" 참고 — 기능적으로 `AbortPolicy`와 동일하면서 의존성만 추가 |
| Resilience4j `Bulkhead.Type.SEMAPHORE` | "구현 방식 검토" 참고 — 비례성 문제로 기각(외부 호출에 Retry 등을 함께 도입할 때 재검토 가능) |
| 큐 축소(100→20) + CallerRunsPolicy 유지 | 큐를 줄이면 발동 빈도가 오히려 증가한다(더 빨리 가득 참) — 역류 총량이 늘어난다 |
| 태스크별 `orTimeout()` | future를 실패시킬 뿐 실행 중인 서명 연산을 취소하지 못해 CPU 포화를 악화시킨다 |
| `tryAcquire(n)`(이미지 수 비례 permit 획득) | 불공정 세마포어에서 기아, 공정 세마포어에서 head-of-line 블로킹, `n > permits`면 영구 503 — 대신 요청당 permit 1개 + 이미지 수 하드 캡 채택 |

## 한계

- 각 지표는 반복 없이 1회 측정이다(Run C 포함).
- t3.small 인프라 선택("실제 배포 스펙과 동일 유지" 원칙에서 의도적으로 벗어남)은 [callerruns-verification.md의 한계](callerruns-verification.md#한계)와 동일하게 적용된다.
- **큐 사이징(640)이 이 부하 아래서는 부족했다** — 위 "판정 3" 참고. 재조정·재측정은 후속 과제로 남긴다.
- **Run C는 `dropped_iterations`(12,805건)가 다른 두 arm(943건/852건)보다 10배 이상 커서, 도착률을 400 req/s로 고정한 "열린 루프"가 이 arm에서는 완벽하게 열려있지 않았을 가능성이 있다** — 응답이 워낙 느려져(avg 3.02s) k6가 `vus_max`(1,000)에 막히며 자기제어 효과가 일부 되살아났을 수 있다(위 "판정 5" 참고). Run C의 HikariCP 점유시간(16.28ms)·처리량(111.10 req/s)이 실제보다 유리하게 나왔을 가능성을 배제할 수 없다 — `preAllocatedVUs`/`maxVUs`를 더 크게 잡고 재측정하면 이 우려를 없앨 수 있다.
- Run C는 `CloudFrontExecutorConfig`의 거부 정책만 `CallerRunsPolicy`로 임시로 되돌려 측정한 뒤 즉시 `AbortPolicy`로 원복했다 — 이 변경은 커밋 이력에 남지 않는다(측정 전용).
- mycourse(pool 모드)만 측정했다 — uploadcourse는 서명 경로가 아니라 이 검증과 무관하다.
- `terraform apply` 도중 겪었던 보안그룹 규칙 state drift 등 인프라 이슈는 [callerruns-verification.md의 한계](callerruns-verification.md#한계)에 기록돼 있다.

## 참고 문서

- [callerruns-verification.md](callerruns-verification.md) — 이 문서가 해결하는 문제(CallerRunsPolicy가 원인임)를 확정한 선행 실측
- [PRESIGN-BOTTLENECK-FIX.md](../../PRESIGN-BOTTLENECK-FIX.md) — 3단계를 포함한 전체 계획 문서
- [CloudFrontExecutorConfig.java](../../../../../src/main/java/backend/yourtrip/global/cloudfront/config/CloudFrontExecutorConfig.java) — `AbortPolicy` 전환·큐 사이징 구현
- [CloudFrontSigningGate.java](../../../../../src/main/java/backend/yourtrip/global/cloudfront/service/CloudFrontSigningGate.java) — 요청 단위 세마포어 게이트 구현
- [CloudFrontSigningGateTest.java](../../../../../src/test/java/backend/yourtrip/global/cloudfront/service/CloudFrontSigningGateTest.java) — 거부·불변식 경로 단위 테스트
- [scripts/k6/detail-arrival-rate.js](../../../../../scripts/k6/detail-arrival-rate.js) — 이번 검증에 쓴 열린 모델 k6 스크립트
