# CallerRunsPolicy 가설 검증 결과

> [ec2-rds.md](ec2-rds.md)가 남긴 의문 — "0단계(트랜잭션 분리) 적용 후 mycourse의 평균 HikariCP 커넥션 점유시간이 47.3ms→53.3ms(+12.7%)로 오히려 악화됐는데, 트랜잭션이 하는 일은 명백히 줄었다" — 에 대한 가설이 다른 워크트리(`upload-course-cache-invalidation-ff8c4e`)의 `docs/tasks/TASK-PRESIGN-CALLERRUNS-HYPOTHESIS.md`에 정리돼 있다(이 워크트리에는 해당 파일이 없어 상대링크로 연결할 수 없다). 이 문서는 그 가설을 EC2 환경에서 직접 계측으로 검증한 기록이다.

## 가설 요약

`MyCourseServiceImpl.getPlaceListByDay`가 쓰는 `cloudFrontSigningExecutor`([CloudFrontExecutorConfig.java](../../../../../src/main/java/backend/yourtrip/global/cloudfront/config/CloudFrontExecutorConfig.java))는 `corePoolSize = maxPoolSize = Runtime.availableProcessors()`, `queueCapacity = 100`, 거부 정책은 `CallerRunsPolicy`다. 0단계 적용 **전**에는 서명이 `@Transactional` 안에 있어 HikariCP 풀 크기(10)가 서명 유량을 자동으로 10개까지만 제한했다 — 이 실행자가 오버플로우할 조건 자체가 성립하지 않았다. 0단계 적용 **후**에는 서명이 트랜잭션 밖으로 빠지면서 이 자연 유량제한이 사라졌고, VU200 근처에서 요청당 서명 태스크 10개(day_schedule당 place 5 × image 2, [seed-benchmark.sql](../../../../../scripts/sql/seed-benchmark.sql) 기준) × VU200 = 최대 2,000개가 동시에 몰릴 수 있다는 게 가설의 핵심이다. `corePoolSize == maxPoolSize`라 큐(100)가 차면 즉시 `CallerRunsPolicy`가 발동해 **요청을 제출한 Tomcat 스레드 자신이 서명을 동기 실행**하게 되고, vCPU가 희소한 EC2 환경(t3 계열, vCPU 2개·물리 코어 1개)에서 이게 DB 트랜잭션 처리 스레드와 CPU를 놓고 경쟁해 트랜잭션의 벽시계 실행시간을 늘린다는 것이다.

이번 세션에서 원인을 조사하려다 App EC2(t3.micro, 1GB RAM)가 메모리 압박으로 두 차례 완전히 멎는 사고를 먼저 겪었고, 그 복구 과정에서 이 executor 구조를 코드로 직접 확인한 게 가설 문서 작성의 계기가 됐다.

## 검증 방법

가설 문서가 제시한 5가지 검증 방법 중 세마포어로 서명 동시성을 인위 제한하는 수정(방법 1)은 범위 밖으로 뒀다. 나머지를 직접 계측했다.

**코드 계측 추가** (측정 전용, 커밋 안 됨):
- [CloudFrontExecutorConfig.java](../../../../../src/main/java/backend/yourtrip/global/cloudfront/config/CloudFrontExecutorConfig.java) — `ExecutorServiceMetrics.monitor(...)`로 `executor_active_threads`/`executor_queued_tasks`/`executor_pool_size_threads` 등을 `/actuator/prometheus`에 노출. `CallerRunsPolicy`를 감싸는 래퍼로 발동 횟수를 `cloudfront_signing_caller_runs_total` 카운터로 계측(표준 `CallerRunsPolicy` 구현은 발동 횟수를 노출하지 않는다). 게이지 값이 첫 시도에서 전부 `NaN`으로 나왔는데, Micrometer의 executor 게이지가 대상 객체를 `WeakReference`로만 참조해 지역변수로 두면 GC 이후 값을 잃는 게 원인이었다 — 싱글턴 빈의 필드에 강한 참조로 옮겨 해결했다.
- [scripts/jfr/parse-execution-samples.mjs](../../../../../scripts/jfr/parse-execution-samples.mjs) — 기존에는 `jdk.ExecutionSample`의 `sampledThread` 필드(스레드 이름)를 전혀 읽지 않아, crypto/서명 프레임이 `http-nio-*`(Tomcat 요청 스레드, = CallerRunsPolicy 증거)에서 나왔는지 `cloudfront-signing-*`(전용 executor, = 정상)에서 나왔는지 구분할 수 없었다. 스레드명 접두사별로 카테고리 히트를 분리 집계하는 기능을 추가했다.

**인프라**: App EC2를 `t3.micro`(1GB RAM)에서 `t3.small`(2GB RAM, vCPU 2개·물리 코어 1개는 동일)로 교체했다 — CPU 경합 역학은 유지한 채 이전 두 차례 크래시의 원인이던 메모리 부족만 완화하려는 의도다("실제 배포 스펙과 동일 유지"라는 이 인프라의 기존 설계 원칙에서 의도적으로 벗어난 선택). `terraform/loadtest/templates/app-user-data.sh.tpl`에 커밋 안 된 변경이 이미 있던 상태라 `terraform apply`가 `instance_type` 변경과 무관하게 App EC2를 destroy+recreate했다.

**측정**: `settings=profile`(10ms 샘플링) JFR을 systemd override drop-in으로 계속 켜둔 채, `scripts/k6/detail-ramping.js`(VU 1→5→10→20→50→100→200, 총 450초+15초)로 mycourse만 부하를 걸었다. VU200 구간(마지막 90초) 직후 `jcmd JFR.dump`로 델타 덤프를 뜨고, Prometheus range query와 CloudWatch `CPUUtilization`을 같은 구간으로 조회했다.

## 측정 결과

VU200 구간(약 100초) 기준:

| # | 지표 | 확정 기준(사전 설정) | 실측값 | 판정 |
|---|---|---|---|---|
| 1 | `cloudfront_signing_caller_runs_total` 증가분 | 두 자릿수 이상 | 구간 내 **약 145,000회**, 전체 실행(460초) 누적 **413,366회** | 압도적 확정 |
| 2 | `executor_queued_tasks{name="cloudFrontSigningExecutor"}` | 큐 용량(100)에 근접 | 5초 스냅샷에서 항상 0 | 관측 안 됨(아래 참고) |
| 3 | `http-nio-*` 스레드 내 crypto/presign 히트 비율 (JFR) | 5%+ | crypto **80.69%**, presign_or_signing **69.28%** | 압도적 확정 |
| 4 | CloudWatch `CPUUtilization`(App EC2, t3.small 2 vCPU) | 85~90%+ | **98.28%** | 확정 |

**JFR 최상위(leaf) 프레임**: VU200 구간 1,822개 샘플 중 1~7위가 전부 `sun.security.util.math.intpoly.IntegerPolynomialP256.*`(ECDSA P-256 타원곡선 모듈러 연산 — CloudFront Signed URL 서명의 실제 CPU 비용) 계열이었다. 전체 샘플의 81.61%가 스택 어딘가에 crypto 프레임을 포함했다.

**스레드별 분포**: 전체 샘플의 94.35%가 `http-nio-*`(Tomcat 요청 스레드)에서 나왔고, `cloudfront-signing-*`(전용 executor, 이 인스턴스에서는 스레드 2개)는 5.54%뿐이었다. `cloudfront-signing-*` 스레드 자체는 crypto/presign 히트율이 99~100%로 정상(원래 하는 일이 서명이므로 당연하다) — 그런데 `http-nio-*` 스레드조차 80.69%가 crypto 프레임을 물고 있었다는 게 결정적이다. **요청을 처리해야 할 스레드 5개 중 4개꼴로, 실제로는 자기 요청 대신 서명 연산을 동기적으로 떠맡고 있었다.**

**신뢰성 부수 관찰**: 로깅을 제거하고 메모리를 2배로 늘린 이 설정에서는 부하테스트가 크래시 없이 완주했다(69,115 요청, 체크 성공률 100%, TPS 153.26/s, p95 1.24s) — t3.micro에서 겪은 두 차례의 완전 정지와 대조된다. 다만 CPU 경합 자체는 t3.small에서도 그대로 재현됐다 — 메모리를 늘린 게 이 병목을 없애지는 못했다.

## 결론

**가설이 확정됐다.** `cloudFrontSigningExecutor`는 t3.small(vCPU 2개)에서도 스레드 2개로만 잡히고, VU200 부하에서 큐+풀 용량(102)을 압도적으로 초과하는 서명 요청이 몰려 `CallerRunsPolicy`가 수십만 번 발동했다. 그 결과 Tomcat 요청 스레드의 상당수가 자기 본연의 작업(DB 트랜잭션 처리, 응답 조립) 대신 ECDSA 서명 연산을 직접 실행하며 vCPU를 거의 완전히 점유(98.28%)했다. 이게 DB 트랜잭션을 처리하는 다른 스레드들의 CPU 획득 기회를 뺏어 벽시계 실행시간을 늘렸다는 것이 [ec2-rds.md](ec2-rds.md)가 관찰한 "트랜잭션이 하는 일은 줄었는데 점유시간은 늘었다"는 역설의 가장 유력한 설명이다.

### `executor_queued_tasks`가 0으로 관측된 이유(지표 #2 미확정에 대해)

판정 기준 4개 중 유일하게 부합하지 않은 지표다. 다른 세 지표(카운터·JFR·CPU)가 워낙 압도적이라 결론에 영향은 없지만, 메커니즘 이해를 위해 짚어둔다: `CallerRunsPolicy`가 이 정도 빈도(초당 약 1,450회)로 발동한다는 것 자체가 큐+풀(102)이 거의 항상 가득 차 있었다는 뜻이다. 다만 표준 `ThreadPoolExecutor`의 거부 정책은 "큐가 가득 차면 새 제출은 큐에 들어가지 않고 즉시 거부되어 제출 스레드가 직접 실행"하는 구조라, 큐 자체의 점유량(`queued`)은 실제로는 "풀이 감당 못 해서 거부되는 태스크 수"와는 별개 지표다 — 큐는 항상 가득 찬(100) 상태를 유지하며 아주 빠르게 회전(2개 스레드가 각 태스크를 밀리초 이내에 처리하고 큐에서 다음 항목을 채워 넣음)했을 가능성이 있고, Prometheus의 5초 스크레이프 간격이 이 회전을 놓쳤을 수 있다. 정확한 원인 규명(게이지 자체의 버그 가능성 포함)은 이번 범위를 벗어난다.

## `TASK-PRESIGN-BOTTLENECK-FIX.md` 3단계 서술 정정

[TASK-PRESIGN-BOTTLENECK-FIX.md의 3단계](../../TASK-PRESIGN-BOTTLENECK-FIX.md)는 "`cloudFrontSigningExecutor`가 Bulkhead 패턴의 절반은 이미 구현돼 있었고, 0단계가 적용되면 이 실행자가 비로소 제 역할을 하게 된다"고 온건하게 서술했다. 이번 실측은 이 서술을 정정한다 — 실행자가 "제 역할을 하게" 된 게 아니라 **압도적으로 오버플로우해 CallerRunsPolicy로 새는 상태**였다.

**"절반 구현"이라는 표현을 더 정확히 하면**: 별도 스레드풀로 서명 작업을 분리해둔 것 자체는 Bulkhead의 겉모습(구조)을 갖췄지만, Bulkhead의 실질적 목적("한쪽의 과부하가 다른 쪽으로 전염되지 않게 막는 것")은 지금 구조에서 보장되지 않는다. `CallerRunsPolicy`는 큐가 가득 찼을 때 "거부된 작업을 제출 스레드가 직접 실행"하는 정책인데, 여기서 제출 스레드는 Tomcat 요청 스레드다 — 즉 격리벽이 가장 필요한 과부하 순간에 격리벽 스스로가 열려 서명 작업이 요청 스레드로 역류한다. `TASK-PRESIGN-BOTTLENECK-FIX.md` 3단계가 원래 제안한 것은 이 순정 `ThreadPoolExecutor` 흉내가 아니라 Resilience4j `@Bulkhead(type = Bulkhead.Type.THREADPOOL)`로의 전환이었다 — Resilience4j의 ThreadPoolBulkhead는 큐가 차면 호출자 스레드로 넘기지 않고 `BulkheadFullException`으로 거부해, 과부하 상황에서도 격리가 유지된다. 즉 "3단계(Bulkhead 정식화)"는 없던 걸 새로 만드는 게 아니라, **지금의 무늬만 격리인 구조를 실제로 격리가 보장되는 구현으로 교체하는 것**을 뜻한다 — 이 실측이 그 교체의 필요성을 뒷받침한다.

### 실무 근거 — CallerRunsPolicy가 격리 목적 풀에 부적절하다는 지적이 실제로 있는가

위 정정이 이 저장소만의 해석이 아니라는 근거를 별도로 조사했다.

- **Resilience4j `ThreadPoolBulkhead`는 실제로 CallerRunsPolicy 방식을 쓰지 않는다** — 큐+풀이 가득 차면 즉시 `BulkheadFullException`으로 거부한다([resilience4j.readme.io/docs/bulkhead](https://resilience4j.readme.io/docs/bulkhead)). "진정한 스레드 격리는 전용 스레드풀에서만 실행할 때 성립하며, 오버플로우를 호출 스레드로 넘기는 순간 그 격리는 깨진다"는 게 이 설계의 근거로 통용된다.
- **`ThreadPoolExecutor.CallerRunsPolicy`의 Java 공식 Javadoc**(Java SE 21)은 이 정책을 "새 작업이 제출되는 속도를 늦추는 간단한 피드백 제어 메커니즘"이라고 설명한다 — 이 설명은 **제출 스레드가 오직 이 executor에 작업을 넘기는 일만 하는 producer**라는 상황을 전제한다. 지금 사례처럼 제출 스레드가 `maxThreads=200`짜리 공유·유한 자원(Tomcat)이고 다른 무관한 요청도 처리해야 한다면 이 전제가 깨진다.
- **SEI CERT(카네기멜론 소프트웨어공학연구소) Java 시큐어 코딩 표준 `TPS01-J`**: "경계가 있는 스레드풀 안에서 상호의존적인 작업을 실행하지 마라"([cmu-sei.github.io](https://cmu-sei.github.io/secure-coding-standards/sei-cert-oracle-coding-standard-for-java/rules/thread-pools-tps/tps01-j)) — Tomcat 풀이 signing 풀에 의존하고, signing 풀의 오버플로우가 다시 Tomcat 풀로 역류하는 지금 구조가 정확히 이 패턴에 해당한다.
- **CPU-bound 작업과 I/O-bound 작업을 같은 풀에 섞지 말라는 원칙**은 여러 스레드풀 튜닝 자료에 공통으로 등장한다 — CPU-bound 작업은 스레드 수를 코어 수에 근접하게 유지해야 컨텍스트 스위칭 오버헤드를 피할 수 있고, I/O-bound 작업은 코어 수보다 훨씬 많은 스레드가 유리하다는 반대되는 요구사항을 갖기 때문이다. 이번 사례는 CPU-bound 풀(ECDSA 서명)의 오버플로우가 I/O-bound 특성의 공유 풀(Tomcat)로 역류한 것이라 이 원칙을 정면으로 위반한다.

**"느려지더라도 처리하는 게 아예 거부하는 것보다 낫지 않은가"라는 반론에 대한 답**: 이 직관은 제출 스레드가 유휴 대기 중이던 I/O-bound 상황(예: 네트워크 응답을 기다리며 어차피 놀고 있던 스레드)에는 타당하다 — 이때 CallerRunsPolicy는 손실이 적은 자연스러운 배압(backpressure)이다. 하지만 이번 사례처럼 제출 스레드가 CPU가 희소한 환경에서 다른 중요한 작업(DB 트랜잭션 처리)과 코어를 직접 두고 경합하게 되는 CPU-bound 상황에서는, "느려도 처리"가 실제로는 "무관한 작업까지 함께 느려짐"으로 번져 전체 시스템 처리량을 더 크게 깎아먹는다 — 이번 실측(CPU 98%, Tomcat 스레드 80%가 서명 연산 점유, 응답 지연 증가)이 그 근거다. 실무에서 흔히 쓰는 대안(AbortPolicy+재시도, 429 응답, DiscardOldestPolicy, 속도 제한, 별도 큐잉)은 전부 "격리된 자원의 과부하를 무관한 공유 자원으로 전가하지 않는다"는 방향으로 수렴한다.

## 한계

- 각 지표는 반복 없이 1회 측정이다.
- t3.small은 "실제 배포 스펙과 동일 유지" 원칙에서 의도적으로 벗어난 선택이다 — CPU 경합 역학(vCPU 2개·물리 코어 1개)은 t3.micro와 동일하지만, 메모리 여유는 실제 배포 환경과 다르다.
- 계측 코드(`CloudFrontExecutorConfig.java`, `parse-execution-samples.mjs`)는 커밋하지 않고 워킹트리 변경으로 남아 있다.
- `executor_queued_tasks` 게이지가 예상과 다르게 나온 정확한 원인은 규명하지 못했다(위 참고).
- 세마포어로 서명 동시성을 인위 제한해 실제로 지표가 개선되는지 확인하는 검증 방법 1(가장 결정적인 인과관계 증거)은 이번 범위에 포함하지 않았다.
- `terraform apply` 도중 보안그룹 규칙 3개에서 state drift(실제 AWS 쪽에는 이미 규칙이 존재해 "중복" 오류)가 발생했다 — 접속 자체는 정상 동작해 이번 측정에는 영향이 없었지만, terraform state 정리가 별도로 필요하다.

## 참고 문서

- [ec2-rds.md](ec2-rds.md) — 이 검증의 출발점이 된 +12.7% 커넥션 점유시간 증가 관찰
- [../local/index.md](../local/index.md) — 로컬 환경에서 유사 CPU 경합 패턴을 처음 관찰한 JFR 실측(Phase C)
- [TASK-PRESIGN-BOTTLENECK-FIX.md](../../TASK-PRESIGN-BOTTLENECK-FIX.md) — 이 실측이 정정한 3단계(Bulkhead) 서술을 포함한 전체 계획 문서
- `TASK-PRESIGN-CALLERRUNS-HYPOTHESIS.md`(다른 워크트리 `upload-course-cache-invalidation-ff8c4e`) — 이번에 검증한 가설의 원본 문서
