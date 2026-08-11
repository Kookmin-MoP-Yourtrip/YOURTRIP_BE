# Run D/D2 — 코스당 서명 1회 전환 EC2 실측

> [design-and-poc.md](design-and-poc.md)가 설계하고 PoC로 검증한 "코스당 서명 1회(custom policy 와일드카드)" 전환을, Run A/B/C와 동일한 App EC2(t3.small)·RDS·k6 EC2 환경에서 실측했다. 바뀐 변수는 "요청당 서명 횟수 10 → 1" 하나뿐이도록 통제했다(같은 시드 규격, 같은 열린 루프 스크립트, 같은 도착률 곡선).

## 측정 환경

| | 값 |
|---|---|
| App EC2 | t3.small (Run A/B/C와 동일) |
| RDS | db.t3.micro, PostgreSQL 16 |
| ElastiCache | cache.t3.micro |
| 부하 도구 | k6 EC2(t3.micro)에서 `scripts/k6/detail-arrival-rate.js` |
| 부하 모델 | 열린 루프(ramping-arrival-rate), 도착률 10→50→100→200→400 req/s, 290초 |
| 시드 | `scripts/sql/seed-benchmark.sql` — mycourse 3,000코스 × 코스당 이미지 10장, key `private/{courseId}/{id}.jpg` |
| JFR | `settings=profile`(10ms 샘플링), 연속 기록 — arm마다 앱을 재기동해 새 기록을 얻고, 부하 종료 직후 `jcmd JFR.dump`로 델타 스냅샷 |

두 arm 모두 재배포 없이 `CLOUDFRONT_SIGNING_PERMITS` 환경변수만 바꿔 같은 빌드로 측정했다(Run A/B에서 검증된 방식).

- **Run D** — `permits=54`(기본값, Run B와 통제)
- **Run D2** — `permits=100000`(게이트 사실상 비활성, Run A와 통제)

각 arm 전에 `DB_DDL_AUTO=create`로 재기동한 뒤 시드를 재실행했고, 시드 직후 검증 쿼리(`mismatched_private_keys`)가 두 arm 모두 **0**임을 확인했다 — key에 박힌 courseId가 실제 소유 코스와 어긋난 행이 없다는 뜻이다.

### 배포 전 실배포 검증(PoC-2)

코드 배포 직후, 실제 상세조회 응답의 이미지 URL로 세 가지를 확인했다(design-and-poc.md의 PoC와 별개로, 이번엔 앱이 만든 서명으로).

| # | 요청 | 결과 |
|---|---|---|
| 1 | 실제 응답 URL 그대로 | 403(오브젝트 없음)→테스트용 더미 오브젝트 업로드 후 **200** |
| 2 | 같은 서명, 응답에 없던 다른 이미지(같은 코스) | **200** |
| 3 | 같은 서명, 다른 코스의 이미지 | **403** |

앱이 만든 쿼리스트링이 코스 내 재사용·코스 간 차단 둘 다 정확히 동작함을 확인한 뒤 부하테스트를 시작했다.

### 진행 중 겪은 문제 — 같은 VPC 공인 IP 홀펀칭 실패

k6 EC2에서 App EC2의 **공인 IP**로 첫 부하테스트를 실행했더니 요청 전부가 `dial: i/o timeout`으로 실패했다(9,273건 100% 실패). App EC2 자체는 정상 응답 중이었고(`/actuator/health` 로컬에서 정상), k6 EC2에서 App EC2의 **사설 IP**로는 즉시 성공했다 — 같은 VPC 내 인스턴스 간 공인 IP 홀펀칭이 막혀 있었다. `BASE_URL`을 App EC2 사설 IP(`10.42.1.159`)로 바꿔 재실행해 해결했다. 이 실패는 DB에 쓰기 작업이 없는 순수 GET 부하라 데이터에 영향은 없었다(재확인 완료).

---

## 측정 결과

| 지표 | Run C (CallerRuns) | Run A (Abort 단독) | Run B (Abort+게이트) | **Run D (서명1회+게이트)** | **Run D2 (서명1회, 게이트 사실상 비활성)** |
|---|---|---|---|---|---|
| HikariCP 평균 커넥션 점유시간 | 16.28ms | 5.79ms | 6.95ms | **4.03ms** | **9.69ms*** |
| JFR `presign_or_signing`(http-nio) | 35.86% | 0.00% | 0.00% | **0.00%** | **0.00%** |
| JFR `cloudfront-signing` 전용 풀의 전체 샘플 점유율 | (미측정) | (미측정) | (미측정) | **19.16%** | **22.03%** |
| JFR http-nio 스레드의 JWT 관련(`io.jsonwebtoken`/`javax.crypto.Mac`) 비율 | (미측정) | (미측정) | (미측정) | **22.05%** | **24.59%** |
| 응답 완전성(200 중 이미지 10장 전부) | 88.4% | 50.6% | 78.0% | **100%**(구조적) | **100%**(구조적) |
| `cloudfront_signing_rejected_total`(executor) | 203,146 | 203,779 | 60,788 | **0** | **0** |
| `cloudfront_signing_gate_rejected_total` | 0(비활성) | 0(비활성) | 15,140 | **7,769** | **0** |
| `cloudfront_signing_gate_deadline_exceeded_total` | 3,943 | 0 | 774 | **0** | **0** |
| `cloudfront_signing_gate_permits_available`(종료 시) | - | - | 54(복귀) | **54**(복귀) | **100000**(복귀) |
| CPU(CloudWatch, 구간 피크) | 99.42% | 99.1% | 99.7% | **69.9%** | **83.4%** |
| 달성 처리량(`http_reqs`) | 111.10 req/s | 151.25 req/s | 151.46 req/s | **146.68 req/s** | **150.53 req/s** |
| `http_req_duration` avg / p95(전체) | 3.02s / 7.63s | 569.13ms / 2.63s | 505.48ms / 2.37s | **672.07ms / 3.53s** | **400.62ms / 2.70s** |
| `http_req_duration` avg(200 응답만) | - | - | 615.31ms | **258.29ms** | **400.62ms**(전체와 동일 — 실패 없음) |
| `http_req_failed` | 0.00% | 0.00% | 32.95% | **17.49%**(7,769건, 전부 게이트 503) | **0.00%** |
| `partial_responses`(브라운아웃) | 11.6%(3,943건) | 49.4%(추정) | 21.0%(6,485건) | **0건**(메트릭 자체 미발생) | **0건**(메트릭 자체 미발생) |
| `data_received` | (미측정) | (미측정) | (미측정) | 221 MB | 269 MB |

\* Run D2의 HikariCP 평균(9.69ms)은 최대값 140.20ms 스팟이 평균을 끌어올렸다 — 램프 초반 1회성 스파이크로 보이며(뒤 판정 참고), 절대값 자체는 여전히 0단계 이전 기준(47.3ms)의 1/5 수준이다.

---

## 판정

**판정 1 — 서명 CPU 비중이 실측으로 확정됐고, 사전 역산과 근접했다.** [design-and-poc.md](design-and-poc.md)가 Run A/B 수치를 역산해 얻은 추정치는 "서명이 전체 CPU의 약 16%"였다. 이번에 JFR 스레드 접두사 분포로 직접 측정한 값은 Run D **19.16%**, Run D2 **22.03%** — 사전 추정이 하한이라는 그 문서의 판단과 일치한다(실효 비용이 벤치마크치보다 높다는 abortpolicy-gate-verification.md 판정 3의 관찰과도 같은 방향).

**판정 2 — 브라운아웃이 설계대로 구조적으로 사라졌다.** Run A(49.4%) → Run B(21.0%) → **Run D/D2(0%, 메트릭 자체가 발생하지 않음)**. `partial_responses` 카운터는 `.add()`가 한 번도 호출되지 않아 k6 요약에 항목 자체가 나타나지 않았다 — "0으로 수렴"이 아니라 **"발생할 수 있는 경로가 코드에서 사라졌다"**는 뜻이다. 이건 게이트가 21%에서 멈췄던 목표를, 게이트가 아니라 서명 구조 자체가 달성한 것이다(fail-closed 설계가 실제로 작동함 — 게이트가 503으로 막은 7,769건은 전부 "이미지 0장" 대신 "완전한 거부"였다).

**판정 3 — CPU가 더 이상 병목이 아니게 됐다(그런데 처리량은 거의 그대로다 — 예상 밖 결과).** CPU 피크가 Run A/B의 99%대에서 **Run D 69.9% / Run D2 83.4%**로 크게 내려갔다. design-and-poc.md의 사전 추정(+19% 개선 상한)과 달리, 실제 처리량은 **Run D2 150.53 req/s ≈ Run A 151.25 req/s로 거의 동일**했다(오차 범위 -0.5%). 서명 CPU를 1/10로 줄였는데 처리량이 그대로인 이유는, **CPU가 이제 병목이 아니기 때문에 CPU를 더 줄여도 처리량이 안 오르는 것**이다 — 도착률 400 req/s를 계속 못 따라가는데(달성 150 vs 목표 400, `dropped_iterations` 대신 큐잉으로 흡수됨) 그 원인이 CPU가 아니라 다른 자원(Tomcat 스레드 대기열, RDS/네트워크 왕복, 또는 JWT·Hibernate 등 요청당 고정 비용)으로 옮겨갔다는 뜻이다. `http_req_duration`의 중앙값(8~9ms)과 p95(2.6~3.5초)가 크게 벌어지는 이중분포도 이를 뒷받침한다 — 대다수 요청은 빠르지만 일부가 큐에서 초 단위로 대기한다. **이 병목의 정체는 이번 범위에서 규명하지 않았다** — Run E/F(2단계) 측정과 knee 재탐색으로 이어간다.

**판정 4 — Tomcat 스레드의 crypto 부담은 CloudFront가 아니라 JWT다(실측으로 확정).** 사전에 세운 범위 밖 가설("JWT 이중 파싱이 서명보다 큰 CPU 소비처일 수 있다")을 이번 JFR로 직접 검증했다. `presign_or_signing`(CloudFront SDK 프레임)은 http-nio 스레드에서 **0.00%**로 완전히 격리돼 있는 반면, `io.jsonwebtoken`/`javax.crypto.Mac` 프레임은 http-nio 스레드 샘플의 **Run D 22.05%, Run D2 24.59%**에서 발견됐다 — `cloudfront-signing` 전용 풀 자체의 전체 점유율(19~22%)과 같은 자릿수다. **즉 이제 남은 두 개의 암호 연산 부담(CloudFront 서명, JWT 검증)이 거의 비슷한 크기다.** JWT 경로([JwtAuthenticationFilter.java](../../../../src/main/java/backend/yourtrip/global/jwt/JwtAuthenticationFilter.java), [JwtTokenProvider.java](../../../../src/main/java/backend/yourtrip/global/jwt/JwtTokenProvider.java))의 이중 파싱·매 호출 파서 생성·요청마다 DB 조회는 이번 범위에서 고치지 않았지만, 이 실측이 그 개선의 우선순위를 뒷받침한다.

**판정 5 — 게이트는 D 단계에서 여전히 완벽하게 작동하지만, Run D/D2 어느 쪽에서도 executor 레벨 거부(`cloudfront_signing_rejected_total`)가 발생하지 않았다.** 이는 B단계(인프라 제거)의 사전 신호다 — "요청당 서명 1회"가 되면서 fan-out이 사라져 큐가 찰 이유 자체가 없어졌다는 뜻이다. Run D의 게이트 거부(7,769건)는 세마포어 permit 부족(요청 단위 배압)이지 executor 큐 포화가 아니다 — 정확히 설계대로다.

---

## 한계

- 각 arm은 반복 없이 1회 측정이다(Run A/B/C와 동일한 한계).
- t3.small 인프라 선택은 "실제 배포 스펙과 동일 유지" 원칙에서 의도적으로 벗어난 것이다([callerruns-verification.md의 한계](../stage0/production/callerruns-verification.md#한계)와 동일하게 적용).
- **판정 3의 "CPU 이후 병목"은 이 문서에서 규명하지 못했다.** JFR의 leaf frame 상위권(`HashMap.getNode`, 문자열 연산, JAR 클래스로딩 등)이 단서일 수 있으나 결정적이지 않다 — Run E/F(2단계)에서 게이트/executor를 제거한 뒤 재관찰하고, 필요하면 별도 JFR 분석(락 경합·GC·소켓 I/O 대기)으로 이어간다.
- `data_received`(Run D 221MB vs Run D2 269MB)는 custom policy의 `Policy=` 파라미터가 URL마다 붙어 응답 크기가 커진 영향과 처리 성공 건수 차이(D는 7,769건이 짧은 503으로 빠짐)가 섞여 있어 순수 "쿼리스트링 오버헤드"만 분리하지 못했다.
- PoC-2의 1번 판정(실제 URL 200)은 seed 데이터가 DB 행만 만들고 S3 오브젝트를 실제로 올리지 않는다는 사실을 이번에 재확인시켰다 — k6 스크립트 자체는 이미지 URL을 fetch하지 않으므로 부하테스트 결과에는 영향이 없지만, "완전성"이 이 문서에서는 JSON 응답의 이미지 개수 기준이지 실제 CloudFront 200 여부 기준이 아니라는 점을 명시해둔다.

## 참고 문서

- [design-and-poc.md](design-and-poc.md) — 이 측정이 검증하는 설계와 사전 추정
- [../stage0/production/abortpolicy-gate-verification.md](../stage0/production/abortpolicy-gate-verification.md) — Run A/B/C 원본 실측
- [run-e-infra-removed.md](run-e-infra-removed.md) — 다음 단계: 게이트·executor 제거 후 재측정
