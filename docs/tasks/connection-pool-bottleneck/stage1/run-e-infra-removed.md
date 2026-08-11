# Run E/F — 게이트·executor 제거 후 재측정, knee 재탐색

> [run-d-signature-once.md](run-d-signature-once.md)가 Run D/D2로 확인한 "executor 큐 거부가 두 arm 모두 0으로 수렴한다"는 사전 신호를 근거로, `CloudFrontSigningGate`·`cloudFrontSigningExecutor`를 코드에서 제거([B1](../../TASK-PRESIGN-BOTTLENECK-FIX.md) 커밋)한 뒤 같은 환경에서 재측정했다. 여기에 도착률 상한을 3배(400→1200 req/s)로 올린 knee 재탐색(Run F)을 더했다.

## 측정 환경

Run D/D2와 동일(App EC2 t3.small, RDS db.t3.micro, ElastiCache cache.t3.micro, k6 EC2에서 `detail-arrival-rate.js`). 코드만 B1·B2(게이트/executor 제거, 설정·에러코드 정리) 반영분으로 교체했다. 각 arm 전에 `DB_DDL_AUTO=create` 재기동 후 재시딩했고, `mismatched_private_keys = 0`을 매번 확인했다.

- **Run E** — B단계 빌드, `MAX_RATE=400`(기본값, Run D2와 직접 비교)
- **Run F** — 같은 빌드, `MAX_RATE=1200`(knee 재탐색)

---

## 측정 결과

| 지표 | Run D2 (인프라 유지, 게이트 비활성) | **Run E (인프라 제거, MAX_RATE=400)** | **Run F (인프라 제거, MAX_RATE=1200)** |
|---|---|---|---|
| HikariCP 평균 커넥션 점유시간 | 9.69ms | **4.56ms** | **6.07ms** |
| JFR `presign_or_signing`(http-nio) | 0.00%(전용 풀에 격리) | **23.06%** | **22.34%** |
| JFR `cloudfront-signing` 전용 풀 샘플 비율 | 22.03% | **해당 없음(풀 자체가 사라짐)** | **해당 없음** |
| 스레드 접두사 분포 | http-nio 67.3% / cloudfront-signing 22.0% / other 10.6% | **http-nio 89.2% / other 10.8%** | **http-nio 87.1% / other 12.9%** |
| `cloudfront_signing_*` 메트릭 | 4종 노출 | **`/actuator/prometheus`에서 완전히 사라짐**(확인됨) | 동일 |
| Tomcat `tomcat_threads_busy_threads`(구간 최대) | (미측정) | (미측정) | **200**(= `maxThreads` 상한) |
| CPU(CloudWatch, 구간 피크) | 83.4% | **85.3%** | **97.8%**(마지막 1분) |
| 달성 처리량(`http_reqs`) | 150.53 req/s | **150.36 req/s** | **153.34 req/s** |
| `http_req_duration` avg / p95 | 400.62ms / 2.70s | **400.45ms / 2.91s** | **1.56s / 3.94s** |
| `http_req_failed` | 0.00% | **0.00%** | **0.00%** |
| `dropped_iterations` | 1,239 | **1,214** | **36,279** |
| `data_received` | 269 MB | **269 MB** | **275 MB** |
| `partial_responses` | 0건(메트릭 미발생) | **0건**(메트릭 미발생) | **0건**(메트릭 미발생) |

---

## 판정

**판정 1 — 핵심 비교(D2 vs E)에서 인프라 제거는 처리량·지연 어느 쪽도 악화시키지 않았다.** 처리량은 150.53→150.36 req/s(오차 범위 -0.1%), 평균 지연은 400.62ms→400.45ms(사실상 동일), p95는 2.70s→2.91s(소폭 증가하나 노이즈 수준)다. HikariCP 점유시간은 오히려 9.69ms→4.56ms로 낮아졌다(Run D2의 9.69ms 자체가 램프 초반 140ms 스파이크 하나에 끌린 값이었다는 이전 문서의 관찰과 일치 — 스파이크가 없었던 이번 arm이 더 대표값에 가까울 수 있다). **"발동하지 않는 안전망을 걷어내도 손해가 없다"는 B단계의 전제가 실측으로 확인됐다.**

**판정 2 — 사전에 세운 판정 기준("http-nio의 presign_or_signing < 2%면 격리 불필요")은 실측으로 반증됐고, 그 반증 자체가 예상된 결과였다.** 실측값은 Run E 23.06%, Run F 22.34%로 기준의 10배 이상이다. 원인은 산수로 설명된다 — 전용 풀이 있을 때 그 풀이 전체 샘플의 19~22%를 차지했는데(Run D/D2), 풀을 없애면 그 CPU 작업이 사라지는 게 아니라 **정확히 같은 비중만큼 http-nio 스레드로 이전**될 뿐이다(http-nio 자체의 샘플 점유율도 67%→89%로 늘어 분모가 커진 효과가 섞였다). **격리 인프라를 없앤다고 서명이 쓰는 절대 CPU 총량이 줄어드는 게 아니므로, 애초에 "비율이 낮아야 한다"는 기준 자체가 틀린 전제였다.** 진짜 필요한 증거는 비율이 아니라 판정 1의 처리량·지연 비교였다 — 이 정정 자체를 결과로 남긴다(이 저장소가 이전에도 몇 차례 반복한 패턴: 판정 기준을 미리 정해도 실측이 그 결함을 드러낼 수 있다).

**판정 3 — knee를 재탐색했지만 "더 높은 처리량 상한"을 찾지 못했다. 대신 진짜 병목을 특정했다.** 도착률 상한을 400→1200 req/s(3배)로 올렸는데도 달성 처리량은 150.36→153.34 req/s로 **거의 그대로**였다. CPU는 400 req/s 구간(83~85%)과 1200 req/s 구간 대부분에서 비슷한 수준이었다가 **마지막 1분에만 97.8%까지 치솟았다.** 결정적 증거는 `tomcat_threads_busy_threads`다 — 구간 최대값이 정확히 **200**, 즉 Tomcat `maxThreads` 설정값과 일치한다. **200개 스레드가 전부 요청 처리(대부분 응답을 조립하며 대기)에 묶여 있어, 그 이상은 스레드를 못 얻고 큐에서 기다리는 구조다.** `http_req_duration`이 avg 400ms→1.56s로 치솟은 것도 이 큐 대기시간이 고스란히 응답 지연에 반영된 결과다. **즉 이 시스템의 처리량 상한은 CPU가 아니라 Tomcat 스레드 풀 크기(200)이며, 그 이하 부하에서는 CPU에 30~40%p의 여유가 있어도 처리량이 못 오른다.**

**판정 4 — Run D/D2가 관찰한 "CPU 이후의 미규명 병목"(run-d-signature-once.md 판정 3)의 정체가 이걸로 설명된다.** 그 문서는 CPU 피크가 70~85%로 내려갔는데도 처리량이 Run A와 거의 같다는 역설을 남기고 정체를 규명하지 못했다. 이번 Run F가 그 답을 준다 — **CPU가 병목이 아니게 된 대신 Tomcat 스레드 풀이 그 자리를 대신 차지하고 있었다.** 요청 하나의 평균 처리시간이 400ms대(대부분 응답 조립·DB 조회·직렬화 등 스레드를 계속 점유하는 작업)라서, `maxThreads=200`으로는 `200 ÷ 0.4s ≈ 500 req/s`가 이론적 상한처럼 보이지만 실측은 그보다 낮은 150 req/s대에서 이미 막힌다 — 큐잉이 시작되면 지연이 늘고, 늘어난 지연이 스레드 점유시간을 늘려 처리량을 더 깎는 되먹임 구조이기 때문으로 보인다(Little's Law: 동시성 200 ÷ 평균 지연 1.56s ≈ 128 req/s로, 이 arm의 실측 처리량 153 req/s와 같은 자릿수다).

---

## 한계

- 각 arm은 반복 없이 1회 측정이다.
- **Run F는 k6의 `maxVUs`(1000) 한계와 뒤섞여 있다.** `dropped_iterations`가 36,279건(다른 arm의 15~30배)으로 k6가 목표 도착률(1200 req/s)을 스스로 유지하지 못했다는 뜻이다 — 서버가 응답을 늦게 줄수록 그 응답을 기다리는 VU가 늘어 남은 VU 풀이 고갈되고, k6는 새 반복을 시작하지 못한 채 건너뛴다. 즉 판정 3의 "처리량이 안 오른다"는 관찰은 서버 병목(Tomcat maxThreads)과 k6 자체의 부하 생성 한계가 섞인 결과일 수 있다 — 다만 `tomcat_threads_busy_threads=200`(서버 쪽 지표)이 독립적인 증거로 남아있어 서버 병목이 실재한다는 결론 자체는 유효하다. `preAllocatedVUs`/`maxVUs`를 3000 이상으로 올려 이 confound를 제거하는 재측정은 후속 과제로 남긴다.
- `maxThreads=200`이 병목이라는 결론은 이번 실측(스레드 busy 지표 + Little's Law 자릿수 일치)으로 뒷받침되지만, **`maxThreads`를 실제로 올려 처리량이 실제로 오르는지는 검증하지 않았다** — 4단계(커넥션 풀/DB 계층 튜닝, TASK-PRESIGN-BOTTLENECK-FIX.md)의 "지금 문제를 풀 크기로 덮지 않는다"는 원칙과 같은 이유로 이번 범위에서는 보류한다. 스레드를 늘리면 코어 수(2 vCPU) 대비 컨텍스트 스위칭 오버헤드가 오히려 늘 수 있다는 경고(HikariCP 위키의 풀 사이징 원칙과 같은 논리가 Tomcat 스레드에도 적용된다)도 함께 검토해야 한다.
- t3.small 인프라 선택은 "실제 배포 스펙과 동일 유지" 원칙에서 의도적으로 벗어난 것이다.

## 참고 문서

- [run-d-signature-once.md](run-d-signature-once.md) — 서명 1회 전환 실측(Run D/D2), 이 문서가 정정하는 사전 판정 기준의 원본
- [design-and-poc.md](design-and-poc.md) — 1단계 설계와 PoC
- [../TASK-PRESIGN-BOTTLENECK-FIX.md](../TASK-PRESIGN-BOTTLENECK-FIX.md) — 4단계(커넥션 풀/DB 계층 튜닝) — Tomcat maxThreads 조정이 다음 논의 대상이라면 이 절과 연결된다
