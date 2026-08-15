# Run G — 도입 전 코드를 극한 부하(1200 req/s)로 재측정

> [run-e-infra-removed.md](run-e-infra-removed.md)의 Run F(도착률 상한 1200 req/s)는 **도입 후 코드에만** 돌렸다. 그래서 지금까지의 before/after 비교는 400 req/s 구간에서만 대칭이었고, "도입 전 코드를 1200까지 밀면 어떻게 됐을까"에는 답이 없었다. 이 문서가 그 비대칭을 없앤다. **새로운 성능 개선이 아니라, 이미 끝난 전환의 비교 신뢰도를 높이기 위한 순수 측정이다.**

## 측정 환경

Run D/D2/E/F와 동일(App EC2 t3.small, RDS db.t3.micro, ElastiCache cache.t3.micro, k6 EC2에서 `detail-arrival-rate.js`). **코드만 도입 전 상태(`72f0ed2` — custom policy 작업 시작 직전, AbortPolicy + `CloudFrontSigningGate`)로 교체**했다.

- **Run G** — `MAX_RATE=1200`, `CLOUDFRONT_SIGNING_PERMITS=100000`

`permits=100000`은 게이트를 사실상 비활성화해 **AbortPolicy 단독** 동작으로 만든다 — Run A(400 req/s)·Run D2와 같은 계보이고, "서명 풀 대기열이 포화되어 서명이 거절되고 일부 사진이 사라지는" 상태를 그대로 재현한다.

`preAllocatedVUs=300` / `maxVUs=1000`은 **Run F와 동일하게 유지**했다. Run F가 남긴 "k6 자신의 부하 생성 한계가 섞였다"는 한계는 여기서도 그대로 남지만, 값을 올리면 Run F와의 직접 비교가 불가능해지므로 **비교 가능성을 우선했다**(결과적으로 `dropped_iterations`가 35,725 vs 36,279로 거의 같아, 두 arm에 같은 정도로 작용했음이 확인됐다).

시드는 현재 `seed-benchmark.sql`(key `private/{courseId}/{id}.jpg`)을 두 arm 공통으로 썼다. 도입 전 코드는 이미지 key를 하나씩 canned policy로 서명하므로 key 형식과 무관하게 동작한다 — 시드를 구 형식으로 되돌리면 URL 길이가 달라져 `data_received` 비교에 잡음이 섞이기 때문에 통일하는 쪽을 택했다. mycourse 상세조회 경로는 Redis를 쓰지 않아(`@Cacheable`/`RedisTemplate` 참조 없음) 캐시 초기화는 생략했다.

### 도입 전 빌드가 실제로 떴다는 독립 증거 3가지

배포 착오는 이 측정 전체를 무의미하게 만들기 때문에, 부하를 걸기 전에 서로 다른 층위에서 셋을 확인했다.

| # | 확인 | 결과 |
|---|---|---|
| 1 | `/actuator/prometheus`에 `cloudfront_signing_*` 메트릭 | **8종 노출**(도입 후 빌드에는 존재하지 않는다) |
| 2 | `cloudfront_signing_gate_permits_available` | **100000** — 게이트 비활성 설정이 실제로 반영됨 |
| 3 | 실제 상세조회 응답의 이미지 URL 형식 | **`?Expires=...&Signature=...`**(canned policy = 이미지당 개별 서명). 도입 후의 `?Policy=...`(와일드카드)와 명확히 구분된다 |

3번이 가장 강한 증거다 — 메트릭은 설정 문제로 착시가 생길 수 있지만, 쿼리스트링 형식은 **서명이 실제로 이미지당 1회 일어나고 있다**는 것을 응답 자체가 증명한다.

---

## 측정 결과 — Run F(도입 후) vs Run G(도입 전), 같은 1200 req/s

| 지표 | **Run F (도입 후)** | **Run G (도입 전)** | 판정 |
|---|---|---|---|
| 달성 처리량(`http_reqs`) | 153.34 req/s | **155.22 req/s** | 사실상 동일 |
| `http_req_duration` avg / p95 | 1.56s / 3.94s | **1.66s / 3.82s** | 사실상 동일 |
| `http_req_duration` med / max | (미기록) | 934.3ms / 6.71s | — |
| `http_req_failed` | 0.00% | **0.00%** | 동일 |
| **`partial_responses`(브라운아웃)** | **0건**(구조적) | **30,506건 = 200 응답의 64.82%** | **완전히 갈림** |
| **`data_received`** | 275 MB | **117 MB** | **완전히 갈림** |
| `dropped_iterations` | 36,279 | 35,725 | 동일 수준(k6 한계가 대칭으로 작용) |
| CPU(CloudWatch 피크) | 97.8% | **99.44%** | 도입 전이 더 높음 |
| `tomcat_threads_busy_threads`(최대) | 200 | **200** | 둘 다 `maxThreads` 도달 |
| HikariCP 평균 점유시간 | 6.07ms | **8.80ms** | 도입 전이 45% 높음 |
| HikariCP `pending` 최대 | (미측정) | 55 | — |
| JFR `presign_or_signing`(전체 샘플) | 22.34%(http-nio) | **65.72%** | **3배 차이** |
| JFR 스레드 분포 | http-nio 87.1% / other 12.9% | **cloudfront-signing 66.02%** / http-nio 28.17% / other 5.81% | — |
| JFR http-nio의 `presign_or_signing` | 22.34% | **0.00%**(전용 풀에 격리) | 구조 차이 |
| `cloudfront_signing_rejected_total`(구간 증가분) | 메트릭 없음 | **282,578건** | — |
| `cloudfront_signing_gate_rejected_total` | 메트릭 없음 | 0(permits=100000이므로 설계대로) | — |
| `cloudfront_signing_gate_deadline_exceeded_total` | 메트릭 없음 | 2,462건 | — |

### 구간별 분해 — 같은 도착률 지점끼리

k6의 `partial_responses`는 전체 합계만 나오지만, 서버측 카운터는 Prometheus에 시계열로 남아 있어 구간별로 분해할 수 있다. TPS는 `http_server_requests_seconds_count`, 이미지 손실률은 `cloudfront_signing_rejected_total ÷ (처리 요청 수 × 10장)`이다.

| 구간(목표 도착률) | 실제 TPS (F / G) | **이미지손실 (F / G)** | CPU (F / G) | Tomcat busy (F / G) | pending (F / G) |
|---|---|---|---|---|---|
| 1 (10→50) | 30.9 / 28.1 | 0.0% / 0.0% | 54.3% / 63.8% | 3 / 3 | 0 / 0 |
| 2 (50→100) | 76.3 / 73.1 | 0.0% / 0.0% | 51.6% / 72.1% | 4 / 4 | 0 / 0 |
| **3 (100→200)** | **151.7 / 146.7** | **0.0% / 15.0%** | **62.3% / 100.0%** | **10 / 116** | 0 / 0 |
| 4 (200→1200) | 299.7 / 302.4 | 0.0% / **94.9%** | 100.0% / 99.5% | 200 / 200 | 160 / 61 |

**구간3이 이 측정에서 가장 선명한 한 줄이다.** 처리량이 사실상 같은데(151.7 vs 146.7 req/s) 도입 전은 이미 CPU 100%에 스레드 116개를 물고 이미지를 15% 흘리기 시작한 반면, 도입 후는 CPU 62.3%에 스레드 10개로 손실 없이 처리했다. **같은 일을 하는 데 도입 전은 한계였고 도입 후는 38%p의 여유가 있었다.**

구간4에서 도입 전의 이미지 손실률 94.9%는 "요청당 10장 중 평균 0.5장만 서명에 성공했다"는 뜻이다.

> **주의 — 이 표의 "이미지손실%"와 위 표의 `partial_responses` 64.82%는 층위가 다르다.** 전자는 *이미지 단위*(서명 거부 수 ÷ 서명 요구 수), 후자는 *응답 단위*("10장 중 한 장이라도 빠졌는가")다. 거부가 요청 내에서 몰려 발생하므로 둘은 단순 환산되지 않는다. 구간별 *응답 단위* 브라운아웃 %가 필요하면 k6를 `--out csv`로 재실행해 타임스탬프별로 매핑해야 한다.

또 하나 — **k6 요약의 `http_reqs` rate(155 req/s)는 램프 전체의 시간평균이지 이 시스템의 처리 상한이 아니다.** 구간별로 보면 실제 최대 처리량은 구간4의 약 300 req/s이고, 155는 낮은 도착률 구간들이 섞여 내려간 값이다.

---

## 판정

**판정 1 — 처리량과 지연은 두 arm이 사실상 같다. 즉 "천장은 원래도 같았다".** 155.22 vs 153.34 req/s(+1.2%), p95 3.82s vs 3.94s. 도입 전 후보 가설 두 개 중 ①"도입 전도 ~150에서 똑같이 막혔다"가 맞았다. **`tomcat_threads_busy_threads`가 두 arm 모두 정확히 200(=`maxThreads`)에 도달한 것이 같은 결론을 서버 쪽 지표로도 뒷받침한다** — 이 시스템의 처리량 상한은 도입 전에도 도입 후에도 Tomcat 스레드 풀이었고, 서명 비용은 그 상한을 만든 원인이 아니었다. run-e-infra-removed.md 판정 3의 결론이 도입 전 코드에도 그대로 적용된다는 뜻이다.

**판정 2 — 그러나 그 "같은 처리량"의 내용물이 전혀 다르다. 도입 전의 처리량은 상당 부분 빈 껍데기였다.** 도입 전은 200 응답 47,062건 중 **30,506건(64.82%)이 이미지가 빠진 응답**이다. 도입 후는 0건이다(메트릭 자체가 발생하지 않는다). `data_received`가 275MB → 117MB로 절반 이하인 것이 이를 바이트 단위로 뒷받침한다.

> `data_received` 차이를 이미지 누락만의 효과로 읽으면 과대평가다 — custom policy는 URL마다 `Policy=`(~230자)가 붙어 도입 후 쪽 응답이 원래 더 크다. URL 길이를 보정해 역산하면 도입 전 응답은 "전부 온전했을 때 기대되는 바이트"의 **약 61%**에 그친다. 방향과 자릿수는 확실하되 정확한 분해는 못 했다는 점을 명시해둔다.

**즉 두 arm은 "초당 150건 처리"라는 같은 숫자를 냈지만, 도입 전은 그 중 3분의 2가 사용자에게 "사진이 사라진 화면"으로 보이는 응답이었다.** `http_req_failed`가 양쪽 다 0.00%라는 사실이 이 지표의 함정을 잘 보여준다 — 표준 성공률 지표만으로는 두 arm이 구별되지 않는다. 이미지 완전성 체크를 넣지 않았다면 이 차이는 측정에 잡히지 않았을 것이다.

**판정 3 — 브라운아웃은 부하가 커질수록 나빠진다(49.4% → 64.8%). 도입 후는 부하와 무관하게 0%다.**

| 도착률 | 도입 전(AbortPolicy 단독) | 도입 후(코스당 서명 1회) |
|---|---|---|
| 400 req/s | Run A **49.4%** | Run E **0%** |
| 1200 req/s | **Run G 64.8%** | Run F **0%** |

도입 전은 부하를 3배로 올리자 브라운아웃이 15.4%p 더 악화됐다. **부하가 세질수록 격차가 벌어진다**는 것이 이번 측정으로 처음 확인됐다. 도입 후가 0%인 것은 "0으로 수렴했다"가 아니라 **그 실패 경로가 코드에서 사라졌다**는 뜻이다(서명이 성공하면 이미지 전부, 실패하면 요청 전체가 503인 fail-closed 구조).

**판정 4 — 극한 부하에서 도입 전 코드는 CPU의 3분의 2를 서명에 쓰고 있었다.** JFR 스레드 분포에서 `cloudfront-signing` 전용 풀이 전체 CPU 샘플의 **66.02%**를 차지했다(도입 후 Run F는 서명 관련 프레임이 22.34%). 사전 설계 문서([design-and-poc.md](design-and-poc.md))가 Run A/B에서 역산한 "서명이 전체 CPU의 약 16%"는 400 req/s 기준이었는데, 부하가 3배가 되자 그 비중 자체가 크게 커졌다.

**여기에 이 측정에서 가장 뼈아픈 사실이 겹친다 — 그렇게 CPU의 66%를 쓰고도 이미지의 상당수를 만들어내지 못했다.** 같은 구간에서 executor가 거부한 서명 태스크가 **282,578건**이다. 요청 47,062건 × 이미지 10장 = 약 47만 건의 서명 요구 중 60%가 큐에 들어가지도 못하고 `AbortPolicy`로 버려졌다는 뜻이고, 그게 곧 판정 2의 브라운아웃 64.82%다. **CPU를 가장 많이 쓰면서 결과물은 가장 부실한 상태** — 이것이 코스당 서명 1회 전환이 없앤 것의 실체다.

**판정 5 — CallerRunsPolicy 회귀는 없었다(격리는 끝까지 정상 동작).** JFR에서 http-nio 스레드의 `presign_or_signing` 비율이 **0.00%**다. 요청 스레드가 서명을 직접 실행한 흔적이 전혀 없으므로, 이 arm의 병목은 `AbortPolicy` 전환 이전의 실패 모드(`CallerRunsPolicy`가 Tomcat 스레드를 잡아먹던 문제, [callerruns-verification.md](../stage0/production/callerruns-verification.md))와는 다른 것이다. 대신 http-nio 샘플의 29.59%는 `crypto`인데 이는 JWT 검증이다(run-d-signature-once.md 판정 4와 같은 관찰).

---

## 방법론 정정 — t3 `unlimited` 모드에서 `CPUCreditBalance=0`은 위험 신호가 아니다

[ec2-rds-loadtest.md §5-3](../../../guide/ec2-rds-loadtest.md)은 "`CPUCreditBalance`가 0에 가까워지면 측정 신뢰도를 재평가해야 한다"고 적고 있다. 이번 측정에서 App EC2의 `CPUCreditBalance`가 **0.0**으로 나와 그 기준대로면 결과를 폐기해야 했지만, 확인해보니 **이 인스턴스들은 `unlimited` 모드**였다(`aws ec2 describe-instance-credit-specifications`).

`unlimited` 모드에서는 잔량이 0이어도 스로틀링되지 않고 **서플러스 크레딧을 빌려 풀스피드로 계속 돈다**(초과분은 과금). 실제로 같은 구간에서 `CPUSurplusCreditBalance`가 0.15 → 5.47로 증가했고, CPU 사용률도 99.44%까지 올라갔다 — 스로틀링(t3.small 베이스라인 20%)이 걸렸다면 나올 수 없는 수치다.

**따라서 `unlimited` 모드에서 봐야 할 지표는 `CPUCreditBalance`가 아니라 `CPUSurplusCreditBalance`이며, 판정 기준도 "0인가"가 아니라 "24시간치 적립 한도를 넘겨 결국 스로틀링에 걸렸는가"여야 한다**(이번엔 5.47로 한참 아래다). 가이드의 기준은 `standard` 모드를 암묵적으로 전제한 것이었다. 이 정정은 Run D/E/F를 포함한 이전 측정에도 소급 적용된다.

---

## 한계

- 1회 측정이다(Run A~F와 동일한 한계).
- **Run F와 마찬가지로 k6의 `maxVUs`(1000) 한계가 섞여 있다.** `dropped_iterations`가 35,725건으로, k6가 목표 도착률 1200 req/s를 스스로 유지하지 못했다. 다만 Run F(36,279건)와 거의 같은 크기라 **두 arm에 대칭으로 작용했으므로 비교 자체는 유효하다** — 절대적인 "이 시스템의 최대 처리량"을 말하는 근거로는 쓸 수 없다는 뜻이다.
- `data_received` 비교는 이미지 누락과 canned/custom policy의 URL 길이 차이가 섞여 있어 완전히 분해하지 못했다(판정 2의 인용 참고).
- `partial_responses`는 "이미지가 10장 미만"만 세고 **몇 장이 빠졌는지는 세지 않는다.** 따라서 64.82%는 "응답의 64.82%가 하나 이상 빠졌다"는 뜻이지 "이미지의 64.82%가 사라졌다"는 뜻이 아니다(후자는 바이트 역산으로 약 39%로 추정된다).
- t3.small 인프라 선택은 "실제 배포 스펙과 동일 유지" 원칙에서 의도적으로 벗어난 것이다.

## 참고 문서

- [run-e-infra-removed.md](run-e-infra-removed.md) — Run E/F. 이 문서가 메우는 비대칭의 출처
- [run-h-i-closed-loop.md](run-h-i-closed-loop.md) — 같은 세션의 닫힌 루프 측정(포화 시작 VU 비교)
- [run-d-signature-once.md](run-d-signature-once.md) — Run D/D2
- [../stage0/production/abortpolicy-gate-verification.md](../stage0/production/abortpolicy-gate-verification.md) — Run A/B/C 원본 실측(400 req/s 기준 브라운아웃 49.4%)
