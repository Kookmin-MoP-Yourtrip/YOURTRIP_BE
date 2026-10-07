# AI 코스 생성 장애 격리(bulkhead) 부하테스트 — 주실험 설계

> **상태: 설계 (측정 전)** · 이슈 #197
>
> 이 문서는 **측정 전에** 실험 질문·가설·예측·판정 기준·무효 조건을 고정하기 위해 쓴다. 측정 뒤에 기준을 고르면 결과에 맞춰 기준을 고른 것과 구분할 수 없기 때문이다([cd-pipeline 검증](../cd-pipeline/verification.md)의 C7 판정이 같은 원칙을 따랐다). 측정 결과는 이 문서를 고치지 않고 별도 문서로 남긴다.

## 0. 요약

- **질문**: AI 코스 생성 요청이 몰리면, 피해가 AI 기능 안에 갇히는가 아니면 AI와 무관한 API와 서버 자체(헬스체크)까지 번지는가.
- **비교**: 개선 전(#180 머지 직전, `3df97b4^1`)과 개선 후(`dev`, `47c0425`)의 JAR을 **같은 운영 인프라**(`terraform/prod`, t3.small 1대, ALB + ASG)에 번갈아 올린다.
- **부하**: AI 요청을 분당 5·20·40·70건의 포아송 도착으로 5분간 넣고, 그동안 AI와 무관한 API(`/popular` + 코스 상세)에 일정한 배경 트래픽을 흘린다. 단계마다 A·B를 연달아 재고, 결과가 경계에 걸칠 수 있는 40단계만 A → B → B → A로 두 번씩 잰다(10회차).
- **핵심 예측**: 개선 전은 CallerRunsPolicy가 예산(30초)을 무력화해 요청 처리 시간을 늘리므로, 워커 32개 고갈이 "예산만 보면 예상되는" 분당 64건이 아니라 **분당 20~40건에서 이미 온다.** 개선 후는 입장 제한이 AI 요청의 워커 점유를 4개 이하로 묶어 **어느 단계에서도 배경 API가 기준선을 유지한다.**

## 1. 실험 질문과 범위

### 1-1. 질문

> **AI 요청이 몰릴 때, 피해 반경(blast radius)이 AI 기능 안에 갇히는가?**

피해 반경은 **AI와 무관한 API의 응답 시간·에러율**과 **ALB 헬스체크 결과**로 잰다. AI 요청 자체의 품질(폴백률·거절률)은 이 실험의 주 지표가 아니다 — 그건 [llm-performance](../llm-performance/README.md) 로드맵이 로컬에서 이미 쟀다.

### 1-2. 왜 이 질문인가 — 동기 처리 구조

AI 코스 생성(`POST /api/my-courses/ai`)은 **동기 API**다. 요청을 받은 Tomcat 워커는 LLM 작업을 `aiAgentExecutor`로 보낸 뒤, 결과가 올 때까지 `future.get(deadline.remainingMs())`에서 멈춰 기다린다(`PlannerAgent.await`, `CuratorAgent.awaitAll`). 즉 **AI 요청 1건 = 워커 1개를 약 20~30초 점유**이고, 이건 executor가 한가해도 마찬가지다.

운영 프로필의 Tomcat 워커는 **32개**다([application-prod.yml](../../../src/main/resources/application-prod.yml), [tomcat-thread-sizing](../tomcat-thread-sizing/README.md)). 그리고 ALB 헬스체크(`/actuator/health/liveness`)도 **같은 8080, 같은 워커 풀**로 처리된다. AI 요청이 워커를 다 가져가면 AI와 무관한 요청과 헬스체크가 함께 줄을 선다.

### 1-3. 범위 밖

| 제외 | 이유 |
|---|---|
| 저녁 시간대 LLM 지연 보조 실험 | 주실험의 효과가 실제 환경 변동에서도 유지되는지 보는 후속 실험이다 |
| LLM stub·지연 주입 | 워커 고갈을 정하는 것은 LLM 속도가 아니라 도착률이다(개선 전 W는 예산이 막는다). LLM 응답 정지(hang) 같은 관측되지 않은 범위만 stub의 몫이다 |
| 클라이언트 재시도 폭풍 | 현재 FE는 재시도하지 않아 가상의 클라이언트 동작을 가정해야 한다 |
| 긴 여행(30일) 요청 | 개선 전후 비교가 아니라 개선 후의 잔존 약점(#178) 확인이다 |
| TourAPI | 실호출하지 않는다(5-4절) |

## 2. 비교 대상

### 2-1. 두 버전

| | A — 개선 전 | B — 개선 후 |
|---|---|---|
| 커밋 | `3df97b4^1` (#180 머지 직전) | `47c0425` (`dev`, #196 머지) |
| 사이의 변경 | #180 · #181 · #183 · #186 · #187 · #188(문서) · #191 · #195 · #196 | |

두 커밋 사이에 **엔티티·스키마 변경이 없다**(`git diff --stat 3df97b4^1 dev -- '**/entity/**'` 결과 없음). 그래서 같은 RDS에 두 JAR을 번갈아 올려도 된다. Spring Boot 버전도 둘 다 3.5.7이다.

### 2-2. 워커 점유에 영향을 주는 설정 차이

| 항목 | A | B | 바꾼 PR |
|---|---|---|---|
| LLM 동시 호출 슬롯 `llm.max-concurrent-calls` | 2 | 5 | #180, #187 |
| `aiAgentExecutor` core / max / 큐 | 4 / 8 / 50 | 20 / 20 / 50 | #180(#177) |
| 거부 정책 | CallerRunsPolicy | CallerRunsPolicy (**그대로**) | — |
| 요청 예산 `ai.course.budget-ms` | 30초 | 35초 | #191 |
| **AI 동시 입장 제한** | **없음** | **서버당 4건, 넘치면 즉시 429 + `Retry-After`** | #195 |
| 요청당 네이버 호출 | 약 45회, 제한기 없음 | 약 25회, 서버 제한기 초당 25 | #181, #186 |
| Curator 응답 | 장소 이름 전체 | 목록 번호(출력 −30%) | #183, #196 |
| Tomcat 워커 | 32 | 32 | — |
| `placeGroundingExecutor` | 8 / 16 / 200, CallerRuns | 같음 | — |

**어떤 변경이 이 실험의 결과를 만드는가.** 피해 반경을 직접 묶는 것은 **입장 제한(#195)** 이다. 슬롯·풀 정렬·출력 축소는 요청 처리 시간 W를 줄여 거절률을 낮추지만, 입장 제한 없이는 "도착률이 충분히 높으면 결국 워커가 고갈된다"는 구조를 바꾸지 못한다. CallerRunsPolicy는 B에도 그대로 있다 — B에서 발동하지 않는 것은 입장 제한 4 × 요청당 Curator 3개 = 12 < executor 스레드 20이기 때문이다(3일 코스 기준).

## 3. 장애 전파 가설

### 3-1. 메커니즘 (A 기준)

1. **슬롯 포화.** 슬롯 2에 호출 1회 약 7~9초라 LLM 처리량은 분당 약 13~17회다. 요청 하나가 LLM 작업 4개(Planner 1 + Curator 3)를 요구하므로 **분당 약 3~4건부터 포화**한다. 응답은 201이지만 폴백이 늘어난다(#180에서 동시 3명 폴백 42% 실측).
2. **executor 큐 적체.** `ThreadPoolExecutor`는 큐 50칸이 다 찰 때까지 스레드를 core 4개에서 늘리지 않는다. 처리량을 넘는 작업이 큐에 쌓이고, 큐에 든 작업에는 시간 상한이 없다 — 요청이 예산 만료로 포기해도 작업은 남아 나중에 실행된다(**좀비 작업**).
3. **CallerRuns 발동 → 예산 무력화.** 큐가 차고 스레드 8개가 모두 바쁘면 거부된 작업을 **제출한 Tomcat 워커가 직접** 실행한다. `generateAsync`는 `CompletableFuture.supplyAsync(() -> generate(call), executor)`라, 거부되면 `generate`가 `supplyAsync` 호출 **안에서, future를 돌려주기도 전에** 동기 실행된다. 데드라인을 거는 `.get(remainingMs)`는 그 다음 줄이라 아무것도 끊지 못한다. 게다가 Curator 제출이 `days.stream().map(submit).toList()`라 거부된 Curator들을 **한 워커가 직렬로** 실행한다. 작업 1개가 슬롯 대기 최대 20초(`llm.timeout-ms`) + 호출 약 9초이므로, 요청 하나의 W가 **60~90초**까지 늘 수 있다. ALB idle timeout 60초를 넘기면 사용자는 504를 받고 서버는 작업을 계속한다.
4. **워커 고갈.** 동시에 워커를 쥔 AI 요청 수 L = λW가 32에 닿으면 AI와 무관한 요청이 Tomcat 대기열에서 기다린다. 배경 API의 p99가 급증한다.
5. **헬스체크 실패.** 헬스체크도 같은 대기열 맨 뒤에 선다. `timeout 5초` 안에 워커를 못 받는 상태가 `interval 30초 × unhealthy_threshold 3`, 즉 **약 90초 지속**되면 ALB가 타깃을 unhealthy로 판정하고, `health_check_type = "ELB"`인 ASG가 인스턴스를 교체한다. desired=1이면 교체 동안 서비스 전체가 끊긴다.

### 3-2. 두 가설 — CallerRuns는 증폭기인가

| | H0: 예산이 지켜진다 | H1: CallerRuns가 예산을 깬다 |
|---|---|---|
| A의 W | 최대 약 30초 | CallerRuns 경로 60~90초 |
| 워커 고갈 경계 λ* = 32 × 60 / W | **분당 약 64건** | **분당 약 21~32건** |
| 고갈이 오는 단계 | 70만 | **40부터** (20도 경계) |

3-1의 코드 분석은 H1을 가리킨다. 분당 40건 단계가 둘을 가른다 — **H0이면 40에서 버티고, H1이면 40에서 무너진다.** 이것이 "근본 원인은 동기 구조 + 입장 제한 부재이고, CallerRuns는 고갈을 앞당기는 증폭기"라는 해석을 실측으로 판정하는 지점이다.

## 4. 측정 전 예측

### 4-1. 가정

- A의 LLM 호출 1회 7~9초, B는 #196 실측 Curator 6.2초 · 요청 처리 시간 W = 16.7초
- A의 요청당 LLM 작업 4개. 단, 큐 대기로 Planner가 예산을 다 쓰면 Curator 제출을 건너뛰어(`Curator 진입 전에 예산이 소진됐다`) 1개로 줄어드는 **자기 제한 효과**가 있다
- TourAPI 생략으로 후보 공급 단계가 약 0.7초 짧아진다(#186 실측). W의 3% 미만이라 무시한다
- 측정 시간대는 오전(LLM이 상대적으로 빠른 시간대)

### 4-2. B — 얼랑 B 예측

입장 상한 c = 4, 제공 부하 a = λW/60.

| λ (분당) | a | 거절률 | 수락 (분당) | AI가 쥔 워커 (평균) |
|---|---|---|---|---|
| 5 | 1.39 | 3.9% | 4.8 | 1.3 |
| 20 | 5.57 | 44.1% | 11.2 | 3.1 |
| 40 | 11.13 | 67.8% | 12.9 | 3.6 |
| 70 | 19.48 | 80.6% | 13.6 | 3.8 |

분당 5건 예측 3.9%는 #196 실측(5.1%)과 1.2%p 차이다. 거절된 요청은 수십 ms 만에 워커를 반납하므로 분당 70건이어도 워커 점유에 더하는 몫은 무시할 수 있다.

### 4-3. A — 동시 점유 L = λW

| λ (분당) | W = 30초 (H0) | W = 60초 | W = 90초 |
|---|---|---|---|
| 5 | 2.5 | 5 | 7.5 |
| 20 | 10 | 20 | 30 |
| 40 | 20 | **40** | **60** |
| 70 | **35** | **70** | **105** |

굵은 칸이 워커 32개를 넘는 조합이다.

### 4-4. 단계별 예측 (판정 대상)

| 단계 | A 예측 | B 예측 |
|---|---|---|
| **5** | 슬롯 포화로 폴백 증가. 큐는 자기 제한 효과로 50에 닿지 않아 **CallerRuns 없음**. 배경 API 영향 없음. AI 도착이 끝난 뒤에도 좀비 작업이 1~2분 남음 | 거절 약 4%, 배경 API 영향 없음 |
| **20** | 자기 제한(요청당 작업 1개)으로도 분당 20건 > 처리량 13~17회라 큐가 1~2분 안에 참 → **CallerRuns 발동**. 고갈은 경계(L 10~30) | 거절 약 44%, 배경 API 영향 없음 |
| **40** | H1이면 **워커 고갈, 배경 API p99 급증, 504 발생, 헬스체크 실패 시작** | 거절 약 68%, 배경 API 영향 없음 |
| **70** | 워커 고갈(H0이어도). AI 도착 5분 안에 **헬스체크 연속 실패가 90초를 넘겨 교체 조건 도달** | 거절 약 81%, 배경 API 영향 없음 |
| 회복 (공통) | 배경 API는 진행 중이던 CallerRuns 요청이 끝나는 대로(최대 약 90초) 회복. 내부 잔여 작업(큐 + 실행 중)은 그보다 늦게 소진 | 배경 API는 처음부터 영향이 없어 회복 시간 0. 실행 중이던 요청 4건 이하가 예산(35초) 안에 끝남 |

**예측이 틀릴 수 있는 지점.** A의 자기 제한 효과가 얼마나 강한지는 계산으로 정하지 못했다. 20단계에서 CallerRuns가 발동하지 않으면 그 효과가 예상보다 강하다는 뜻이고, 그 자체로 기록할 결과다.

## 5. 시나리오

### 5-1. 회차 구성

```
[웜업 2분] → [기준선 2분] → [AI 도착 5분] → [회복 관측 3분]   = 12분
```

| 구간 | 배경 트래픽 | AI 요청 | 목적 |
|---|---|---|---|
| 웜업 | 흐름 | 분당 2건 | 새 인스턴스의 JIT를 AI 경로까지 데운다. 배경 트래픽만으로는 AI 코드가 데워지지 않는다 |
| 기준선 | 흐름 | 없음 | 그 회차의 배경 API 정상 수준 |
| AI 도착 | 흐름 | 단계의 도착률 | 피해 반경 측정 |
| 회복 관측 | 흐름 | 없음 | 회복 시간 측정 |

AI 도착 구간을 5분으로 둔 이유:
- A는 큐가 차는 데 1~2분, CallerRuns 경로 요청 하나가 최대 약 90초라 처음 1~2분은 과도기다. 그 뒤의 정상 상태를 몇 분 봐야 한다
- 헬스체크 교체 조건(약 90초 지속)의 도달 여부와 시점을 볼 수 있다
- 카카오 일일 한도 안에 들어간다(7절)

회복 관측을 3분으로 둔 이유: 예측상 A도 AI 도착이 끝난 뒤 2~3분 안에 회복한다(4-4절). 3분 안에 회복하지 않으면 "회복 안 됨(3분 이상)"으로 기록한다 — 정확한 회복 시각보다 "3분 안에 돌아오는가"가 판정에 쓰이는 정보다.

분당 5건 단계는 AI 요청이 약 25건뿐이라 AI 쪽 통계가 거칠다. 이 실험의 주 지표는 배경 API(초당 20건이라 10초 구간마다 표본 200개)이므로 받아들인다.

### 5-2. AI 요청

- 기존 [`ai-course-arrival.js`](../../../scripts/k6/ai-course-arrival.js)의 **시드 고정 포아송 도착**을 쓴다. 같은 단계의 A·B 회차는 시드가 같아 **정확히 같은 도착 시각과 입력 순서**를 받는다
- 입력은 기존과 같은 지역 10 × 키워드 세트 3, 3일 코스다
- 거절(429)·실패는 재시도하지 않는다(현재 FE와 같다)
- 클라이언트 타임아웃 60초(FE OkHttp readTimeout = ALB idle timeout)

### 5-3. 배경 트래픽

| API | 속도 | 경로 | 고른 이유 |
|---|---|---|---|
| `GET /api/upload-courses/popular` | 초당 10건 | Redis 캐시만 사용 | **워커 부족만** 순수하게 드러난다 |
| `GET /api/upload-courses/{id}` | 초당 10건 | DB 경유(캐시 미스) | 피해가 워커 부족인지 DB 경합인지 가를 수 있다 |

- t3.small이 `/popular`만으로 초당 약 2,900건을 처리하므로([ec2-measurement](../tomcat-thread-sizing/ec2-measurement.md)) 배경 트래픽 자체는 부하가 되지 않는다
- 상세조회는 DB만 타는 API가 아니다. 조회수 증분을 Redis에 쓰고(`view_count:increment:*`) 이것이 주기적으로 DB에 동기화된다. A·B에 똑같이 적용되므로 비교에는 영향이 없다
- `constant-arrival-rate`(열린 모델)로 보낸다. 앞 요청의 응답을 기다리지 않아야 워커 고갈 때 대기 시간이 그대로 드러난다
- **배경 요청의 클라이언트 타임아웃은 10초**다. 열린 모델은 응답이 늦어질수록 동시에 떠 있는 요청이 늘어, 60초면 초당 20건에 k6 VU가 1,200개 넘게 든다. VU가 모자라면 k6가 요청을 건너뛰어(dropped iteration) 가장 나쁜 구간의 데이터가 빠진다. 판정 기준이 "p99 1초 초과 = 장애"라 10초에서 잘라도 판정은 바뀌지 않는다. 10초를 넘긴 요청은 상태 코드 0(에러)으로 센다
- VU는 처음부터 전부 할당한다(초당 건수 × 15). 부하 도중에 VU를 늘리면 그 초기화 사이에 보낼 요청을 건너뛴다 — 모의 서버 검증에서 150건이 빠지는 것을 확인했다(10-5절)
- 요청 생성은 기존 [`lib/scenarios.mjs`](../../../scripts/k6/lib/scenarios.mjs)의 `buildPopularRequest`·`buildRequest`를 재사용한다

### 5-4. TourAPI 생략

#186과 같은 방식으로 `tour.base-url`을 닫힌 로컬 포트로 돌린다. 연결이 즉시 거부돼 fail-open 경로를 탄다. A·B에 똑같이 적용한다. 회차 시작 전 AI 요청 1건으로 TourAPI 성공 0건을 확인한다.

### 5-5. 순서와 일정

- **10회차**: 40단계는 **A → B → B → A**, 나머지 단계는 **A → B** 한 번씩
  - ABBA(A → B → B → A)는 시간에 따라 선형으로 흐르는 LLM 속도 변화를 두 버전에 똑같이 배분해 평균에서 지운다. 대가는 회차가 두 배가 되는 것이다
  - 처음에는 #195·#196처럼 모든 단계를 ABBA로 설계했다(16회차, 약 5.6시간). 그러나 그때 재던 차이는 거절률 몇 %p 수준이라 시간대 변동(약 20%)에 뒤집힐 수 있었던 반면, 이번에 예측하는 차이는 "장애 vs 정상"으로 자릿수가 달라 시간대 변동으로는 뒤집히지 않는다. 그래서 반복은 **결과가 경계에 걸칠 수 있는 40단계(H0/H1 판정)에만** 둔다
  - A·B를 한 번씩 재는 단계는 둘을 연달아 재 측정 사이 시간 차를 최소로 한다. B가 늘 A 뒤라 시간이 갈수록 느려지는 LLM 조건을 B가 떠안는데, 이는 B에 불리한 쪽이라 "B가 버틴다"는 결론을 부풀리지 않는다
- 회차마다 instance refresh(5-6절)가 약 5~6분 걸려(CD 실측 6분 12초, 5분 15초) 회차 하나가 약 18분, 전체가 **약 3.3시간**이다
- **하루에 몰아서, 단계 순서는 40 → 70 → 20 → 5**로 잰다
  - 오전에 시작해도 뒤 단계는 오후에 걸리고, 오후에는 LLM이 약 20% 느려진다(#191). 그래도 A·B 비교는 언제나 같은 단계 안에서 연달아(40단계는 ABBA로) 하므로 시간대 변동의 영향이 작다. 영향을 받는 것은 단계끼리의 비교뿐이다
  - 그래서 H0/H1을 가르는 **40단계를 가장 먼저**, LLM이 빠른 오전에 잰다. 70은 H0이어도 고갈이 예측되는 단계라 시간대 영향이 판정을 뒤집지 않는다
  - 회차마다 인스턴스를 교체하고 캐시를 비우므로(5-6절) 단계 순서를 바꿔도 앞 회차가 다음 회차에 남기는 것이 없다
  - 단계끼리 비교할 때 시간대 차이를 해석할 수 있도록, B 회차의 Curator 호출 1회 시간(`ai.llm.call{agent=curator}`)을 **시간대 공변량**으로 함께 기록한다

### 5-6. 회차 사이 초기화 — 매 회차 인스턴스 교체

모든 회차를 **SSM `/yourtrip/prod/artifact_key` 변경 + instance refresh**로 새 인스턴스에서 시작한다. 같은 버전이 연속되는 회차(B → B)도 교체한다.

- **균일한 출발 상태.** A의 좀비 작업, JVM 상태, 커넥션 풀 상태가 다음 회차로 넘어갈 여지가 없다. "전환 때만 교체하고 나머지는 재시작"하면 B2만 다른 출발 상태를 갖게 되는데, ABBA는 시간 흐름만 상쇄할 뿐 이런 편향은 상쇄하지 못한다
- **기존 CD 경로 그대로.** 배포 기록(SSM)과 실제 실행 JAR이 어긋나지 않는다. 인스턴스 안에서 JAR을 바꿔 끼우는 수동 절차를 새로 만들지 않는다
- **CPU 크레딧은 변수가 아니다.** Launch Template이 `cpu_credits = "unlimited"`다([asg.tf](../../../terraform/prod/asg.tf))
- **Redis는 인스턴스 밖에 있어 교체로 초기화되지 않는다.** 그래서 회차 시작 전에 **캐시 키만** 지운다 — `popularCourses::*`, `courseDetail::*`, `courseListItem::*`([RedisConfig](../../../src/main/java/backend/yourtrip/global/config/RedisConfig.java)의 캐시 이름 + Spring 기본 접두사 `::`). `KEYS`는 Redis를 막으므로 `SCAN` + `UNLINK`로 지운다
  - `FLUSHALL`을 쓰지 않는 이유: 운영 Redis에는 캐시 말고도 **아직 DB에 동기화되지 않은 조회수 증분**(`view_count:increment:*`, `view_count_dirty`)과 이메일 인증 상태(`email_verification:*`)가 있다. 캐시만 지워도 "모든 회차가 캐시 미스에서 출발한다"는 실험 효과는 같다

## 6. 관측

### 6-1. 관측 장치가 관측 대상과 같은 자원을 쓰는 문제

운영 Grafana Alloy와 기존 `poll-metrics.sh`는 모두 `/actuator/prometheus`를 **앱과 같은 8080 워커 풀**로 긁는다. 워커가 고갈되는 바로 그 순간 수집이 끊겨, 이 실험의 핵심 증거(`tomcat_threads_busy` = 32, executor 상태)가 공백이 된다. ALB는 `/actuator/*`를 인터넷에서 차단하고, k6 EC2(loadtest VPC)와 앱(prod VPC)은 VPC가 달라 사설 IP로도 닿지 않는다.

### 6-2. 해법 — 관리 포트 분리, 헬스체크는 8080에 유지

| 설정 (환경변수, A·B 동일) | 효과 |
|---|---|
| `MANAGEMENT_SERVER_PORT=8081` | actuator가 **별도 내장 Tomcat**(별도 커넥터·대기열·워커 풀 `http-nio-8081-exec-*`)으로 옮겨진다. 앱 워커 32개가 고갈돼도 지표 수집은 영향받지 않는다 |
| `MANAGEMENT_ENDPOINT_HEALTH_GROUP_LIVENESS_ADDITIONALPATH=server:/actuator` | liveness 그룹을 **앱 포트(8080)의 `/actuator`** 에도 노출한다 |
| `MANAGEMENT_ENDPOINT_HEALTH_GROUP_LIVENESS_INCLUDE=livenessState` | 그 그룹이 liveness 상태만 담게 한다 |
| ALB 헬스체크 경로 → `/actuator` (`terraform/prod` `health_check_path`) | 헬스체크가 **운영과 같이 앱 워커 풀을 거친다.** 이걸 안 하면 헬스체크가 8081로 옮겨져 3-1의 5단계 가설이 실험에서 사라진다 |

**왜 `/livez`가 아니라 `/actuator`인가 — 설계 중 두 번 틀렸다.** 처음에는 Spring Boot가 제공하는 `/livez`(`probes.add-additional-paths`)를 쓰려 했다. 그런데 두 버전 모두 보안 설정이 `/actuator/**`만 인증 없이 열고 나머지는 `anyRequest().authenticated()`라, `/livez`는 401이 돼 인스턴스가 영원히 unhealthy가 된다. 개선 전 JAR은 코드를 바꿀 수 없으므로 보안 설정을 고치는 길은 없다. 그래서 liveness 그룹의 `additional-path`로 **이미 열려 있는 `/actuator/**` 아래**에 노출했다.
- 기존 경로 그대로(`/actuator/health/liveness`)는 쓸 수 없다 — `additional-path`는 경로 한 단계만 허용한다(`'value' must contain only one segment`로 기동 실패를 로컬에서 확인). `/actuator` 한 단계는 `/actuator/**` 허용 규칙에 포함된다
- `additional-path`만 주면 **liveness가 전체 health로 바뀐다** — 사용자 정의 그룹이 자동 생성 그룹을 대체하면서 포함 대상이 비어 DB·Redis·메일까지 담겼다. 그러면 DB 순간 장애에도 503이 나 ASG 교체 폭풍이 된다(운영이 `/actuator/health`가 아니라 liveness를 고른 이유와 같다). 그래서 `include=livenessState`를 함께 준다
- 로컬에서 세 설정을 함께 주고 확인했다: 8080 `/actuator` → 인증 없이 200, `{"livenessState":"UP"}`만 / 8080 `/actuator/prometheus` → 404 / 8081 `/actuator/prometheus` → 200

환경변수는 SSM `<ssm_parameter_path>/env/` 아래에 두면 부팅 때 `.env`로 떨어져 매 회차 새 인스턴스에도 적용된다.

**운영 Grafana Alloy는 이번 측정에서 쓰지 않는다.** Alloy는 `localhost:8080/actuator/prometheus`를 긁는데, 분리 후 그 경로가 8081로 옮겨 가 측정 기간 동안 앱 지표 수집이 실패한다. 실험 지표는 6-3절의 1초 수집이 담당하므로 받아들인다. Alloy의 호스트 지표 수집은 영향을 받지 않는다. `config.alloy`를 8081로 바꾸면 Launch Template이 바뀌는데, 측정 장치를 위해 그 변경을 들이지 않는다.

**이 장치가 시스템을 바꾸는 범위.** 앱 워커 풀과 헬스체크 경로(앱 워커 풀 경유)는 바꾸지 않는다. 추가되는 것은 관리용 Tomcat 하나와 1초 수집의 직렬화 CPU 비용(스냅샷 50~100KB)이며 A·B에 똑같이 든다. 같은 JVM이라 **CPU와 힙·GC는 여전히 공유**한다 — 이 시나리오의 워커는 LLM을 **기다리며** 묶여 CPU를 거의 쓰지 않으므로 CPU가 먼저 포화될 가능성은 낮지만, CPU 사용률과 GC 정지 시간을 함께 기록한다.

### 6-3. 수집 수단

| 수단 | 위치 | 간격 | 담는 것 |
|---|---|---|---|
| `poll-metrics.sh` → `localhost:8081` | 앱 인스턴스 안 | 1초 | Micrometer 전량(화이트리스트 없음 — 기존 원칙) |
| 스레드 덤프 `jcmd <pid> Thread.print` | 앱 인스턴스 안(SSM) | 10초 | 스레드별 스택. **CallerRuns의 1차 증거** |
| k6 | k6 EC2 | 요청 단위 | 배경·AI 요청의 상태 코드, 응답 시간, 출발 지연 |
| CloudWatch ALB 지표 | AWS | 1분 | `TargetResponseTime`, `HTTPCode_Target_5XX`, `HTTPCode_ELB_5XX`, `UnHealthyHostCount` |
| CloudWatch EC2 지표 | AWS | 1분 | `CPUUtilization` |

**스레드 덤프를 CallerRuns의 1차 증거로 두는 이유.** A에는 CallerRuns 발동을 세는 카운터가 없다. 과거 CloudFront 서명 executor의 CallerRuns 검증에서 `executor_queued_tasks` 게이지는 CallerRuns가 초당 약 1,450회 발동하는 동안에도 **항상 0으로 관측됐고**, 결정적 증거는 스레드별 스택이었다([callerruns-verification](../connection-pool-bottleneck/stage0/production/callerruns-verification.md)). 이번에는 `http-nio-8080-exec-*` 스택에 `CallerRunsPolicy.rejectedExecution` → `OpenAiLlmClient.generate`가 찍히는지로 판정한다.

### 6-4. 지표

| 분류 | 지표 | 출처 | 용도 |
|---|---|---|---|
| **피해 반경 (주 지표)** | 배경 API의 p50 · p95 · p99, 에러율 (10초 구간) | k6 | 판정 |
| 워커 점유 | `tomcat_threads_busy_threads`, `tomcat_threads_config_max_threads` | 8081 | 고갈 여부·시점 |
| executor | `executor_active_threads`, `executor_queued_tasks`, `executor_pool_size_threads` (`name=aiAgentExecutor`) | 8081 | 큐 적체 추이 (보조 — 6-3 참고) |
| CallerRuns | `http-nio-8080-exec-*` 중 LLM 호출 스택을 가진 스레드 수 | 스레드 덤프 | 발동 여부·규모 |
| 헬스체크 | `UnHealthyHostCount`, 헬스체크 실패 시각 | CloudWatch | 교체 조건 도달 여부·시점 |
| AI 요청 | 상태 코드 분포(201 / 429 / 504 / 기타), 응답 시간 | k6 | 맥락 |
| 입장 제한 (B만) | `ai_course_admission_total{result}`, `ai_course_admission_in_use` | 8081 | 4-2 예측 대조 |
| 자원 | CPU 사용률, `jvm_gc_pause_seconds` | CloudWatch, 8081 | 교란 요인 확인 |

### 6-5. 회복 시간의 정의

두 가지로 나눠 잰다.

- **서비스 회복 시간**: AI 도착 종료 시점부터, 배경 API의 10초 구간 p99가 **그 회차 기준선 p99의 1.5배 이하 또는 기준선 + 100ms 이하**인 상태가 **30초(10초 구간 3개) 연속** 유지되기 시작한 시점까지. +100ms 하한을 두는 이유는 8절과 같다 — 기준선이 수십 ms면 1.5배는 수 ms 차이라 잡음만으로도 회복 판정이 늦어진다
- **내부 잔여 작업 소진 시간**: AI 도착 종료 시점부터 `aiAgentExecutor`의 active + queued가 0이 되고, 스레드 덤프에서 LLM 호출 중인 스레드가 0이 된 시점까지. A의 좀비 작업 규모를 드러낸다

## 7. 외부 한도와 비용

TourAPI를 생략하면 **카카오가 새 병목**이다.

| API | 한도 | 요청당 호출 |
|---|---|---|
| 카카오 | 일 100,000건 | 약 30~45회 |
| 네이버 | 월 775,000건, 키당 초당 50건 | A 약 45회 / B 약 25회 |
| OpenAI | 조직 TPM | A는 슬롯 2, B는 입장 제한 4가 상한을 건다 |

| | 파이프라인에 들어가는 요청 | 카카오 (상한) |
|---|---|---|
| A: (40×2 + 70 + 20 + 5) × 5분 | 875 | 약 2.6만~3.9만 |
| B: 수락분 (12.9×2 + 13.6 + 11.2 + 4.8) × 5분 | 약 277 | 약 0.8만~1.2만 |
| 웜업: 분당 2건 × 2분 × 10회차 | 40 | 약 0.1만~0.2만 |
| **합계** | | **약 3.6만~5.4만** |

- 하루에 다 해도(5-5절) 한도 안이다
- A는 과부하 때 그라운딩까지 못 가는 요청이 있어 실제 소모는 이보다 적을 가능성이 크다. **첫 회차 직후 실측 호출 수로 남은 일정의 소모를 다시 계산**하고, 한도를 넘길 것 같으면 그날의 남은 단계를 다음 날로 미룬다
- 네이버: A는 제한기가 없어 분당 70건이면 초당 약 52건으로 키 한도를 넘어 429가 난다. **A의 실제 동작이므로 그대로 둔다.** 월 한도로는 합계 약 7만 건이라 여유가 있다
- OpenAI: A는 슬롯 2라 성공 호출이 분당 약 15회 이하로 묶이고, B는 입장 제한으로 묶인다. 부하를 올려도 사용량은 도착률에 비례해 늘지 않는다

## 8. 판정 기준

판정은 **같은 단계 안의 A 두 회차와 B 두 회차**를 비교한다. 아래 수치는 **측정 시작 후에는 고치지 않는다.**

### 8-1. 배경 API 상태의 두 단계 판정

**왜 "기준선의 몇 배" 하나로 정하지 않는가.** 실무의 장애 판정은 보통 SLO(사용자에게 약속한 목표)의 **절대값**과 그 위반 속도(error budget burn rate)로 하고, "기준선 대비 배수"는 이상 탐지·카나리아 비교 같은 상대 비교에서 팀마다 정해 쓴다 — 보편 표준값은 없다. 이 실험에는 둘 다 약점이 있다. 배수만 쓰면 기준선 p99가 수십 ms일 때 3배라도 100ms 남짓이라, 사용자는 못 느끼는 GC 한 번·네트워크 흔들림에도 걸린다(오탐). 절대값만 쓰면 사용자는 버티지만 확실히 나빠진 상태를 놓친다. 그래서 둘을 단계로 나눠 쓴다.

판정 창은 **AI 도착 구간의 마지막 3분**(과도기를 뺀 정상 상태)이고, 지표는 배경 API 전체(`/popular` + 상세)의 10초 구간 값이다.

| 판정 | 조건 | 근거 |
|---|---|---|
| **저하 (degraded)** | p99 > 기준선 p99 × 3 **그리고** p99 > 기준선 p99 + 100ms 인 10초 구간이 **3개(30초) 연속** | 배수로 상대 변화를 보되, +100ms 하한으로 작은 값의 잡음을 거른다. 30초 연속은 순간 튐을 거른다 |
| **장애 (outage)** | p99 > 1초인 10초 구간이 **3개 연속**, 또는 1분 창 **에러율 > 1%** | 단순 조회 API에서 p99 1초는 모바일 사용자가 지연을 체감하는 수준이고, 에러율 1%는 흔히 쓰는 가용성 경보선이다 |

이 서비스에는 **공식 SLO가 없다.** 위 값은 이 실험을 위해 정한 기준이며, 결과를 인용할 때도 이 점을 함께 밝힌다. 에러는 5xx, 클라이언트 타임아웃, 연결 실패를 모두 센다.

### 8-2. 판정 항목

| # | 주장 | 판정 방법 |
|---|---|---|
| J1 | B는 모든 단계에서 피해 반경이 AI 기능 안에 갇힌다 | 네 단계 모두, B 두 회차 모두에서 **저하도 장애도 없음** |
| J2 | A는 어떤 단계부터 피해가 번진다 | 저하가 처음 나타난 단계와 장애가 처음 나타난 단계를 **각각** 기록 |
| J3 | 번짐의 원인은 워커 고갈이다 | 저하·장애 구간에 `tomcat_threads_busy` = 32 |
| J4 | CallerRuns는 증폭기다 (H1) | A의 40단계에서 장애가 나고, 스레드 덤프에 CallerRuns 스택이 있다. 40에서 장애가 나지 않으면 H0 |
| J5 | A는 헬스체크까지 무너진다 | `UnHealthyHostCount` = 1 도달. B는 0 |
| J6 | B는 회복 시간이 짧다 | 단계별 서비스 회복 시간 A > B |

## 9. 측정 무효 조건과 원인 분리 표시

무효 조건은 "결과가 나쁘게 나왔다"가 아니라 **"측정 자체가 어긋나 그 회차 데이터를 믿을 수 없다"** 를 판단하는 규칙이다. 기준은 **실험 조건이 어긋났는가**이고, 시스템이 과부하로 보인 동작은 기준이 아니다 — 그걸로 회차를 버리면 A의 과부하 동작을 측정에서 지우게 된다.

### 9-1. 무효 — 버리고 같은 자리에서 다시 잰다

다시 재는 동안 ABBA 순서는 바꾸지 않는다.

- k6 출발 지연이 1초를 넘는 AI 요청이 있음(기존 `aggregate-arrival.py` 규칙)
- k6가 배경 요청을 제때 보내지 못함(`dropped_iterations` > 0) — k6 VU 부족
- 도구 문제로 보이는 응답이 있음 — AI 요청의 201·429·502·503·504 외 응답(401·403 토큰 만료, 연결 실패 등), 배경 요청의 200·5xx·타임아웃 외 응답(404면 상세 코스 ID 범위가 틀렸다)
- TourAPI 성공 호출이 1건 이상(생략이 적용되지 않음) — `ai_candidate_retrieval_total{source="tour_api",result=hit|empty}` 증가분
- 카카오 한도 소진이 1건 이상 — 앱 로그의 `Kakao search API error(QUOTA_EXCEEDED)`
- OpenAI가 429(TPM 초과)를 돌려준 호출이 1건 이상 — A의 슬롯 대기 포기는 실험 대상이므로 해당하지 않는다. 두 버전 모두 이를 가르는 지표가 없어, 집계기는 의심 로그를 찾아 **수동 확인**으로 표시한다
- 측정 중 인스턴스가 교체됨(ASG 프로세스 일시 중지가 적용되지 않음)

### 9-2. 원인 분리 필요 — 데이터는 남기고 J3만 보류

아래는 관측 장치의 전제가 흔들렸거나 대안 설명이 생긴 경우다. 회차를 버리지 않고, 그 회차의 **J3(원인이 워커 고갈인가) 판정만 보류**한 뒤 스레드 덤프로 원인을 가린다.

| 조건 | 왜 표시하나 | 원인을 가리는 방법 |
|---|---|---|
| 8081 지표 수집이 **10초 이상 연속** 실패 | 관리 포트 분리의 전제(고갈 중에도 수집된다)가 깨져 절정 구간의 `tomcat_threads_busy`가 빠진다 | 같은 구간의 스레드 덤프에서 `http-nio-8080-exec-*`의 개수와 상태로 워커 점유를 직접 센다 |
| CPU 사용률 **1분 평균 90% 초과** | 이 실험의 가설은 워커가 LLM을 **기다리며** 묶인다는 것이다. CPU가 포화되면 "느려진 원인이 CPU 부족일 수 있다"는 대안 설명이 생긴다 | 스레드 덤프에서 워커가 대기(`WAITING`·`TIMED_WAITING`)인지 실행 중(`RUNNABLE`)인지 본다. 대기가 대부분이면 워커 고갈로 판정한다 |

## 10. 인프라와 실행 절차

### 10-1. 구성

| 구성 요소 | 내용 |
|---|---|
| 앱 | `terraform/prod` — t3.small, ASG desired 1 / max 2, ALB(idle timeout 60초) |
| DB · 캐시 | 같은 모듈의 RDS(db.t3.micro), ElastiCache(cache.t3.micro) |
| 부하 생성 | `terraform/loadtest`의 k6 EC2(`m7i-flex.large`)만 `-target`으로 띄워(10-7) 운영 ALB의 공개 도메인으로 보낸다. k6 EC2는 서브넷·보안그룹·키페어에만 의존해 앱·RDS·Redis는 만들어지지 않는다. 모듈에 토글 변수를 두는 방법은 기존 리소스에 `count`를 붙여 state 주소가 바뀌고 `state mv`가 따라오므로 택하지 않았다 |
| 배포 | SSM `artifact_key` + instance refresh (CD와 같은 경로) |

### 10-2. 사전 작업

1. A의 JAR을 `3df97b4^1`에서 빌드해 아티팩트 버킷에 올린다(B는 CD가 이미 올렸는지 확인)
2. SSM `env/`에 6-2절의 관리 포트 환경변수 두 개와 `tour.base-url` 생략값을 넣는다
3. `terraform/prod`의 `health_check_path`를 `/actuator`로 바꿔 `plan` → `apply`한다(형상 변경이라 terraform을 거친다). **2번(SSM)보다 먼저 바꾸면 안 된다** — 새 경로를 여는 설정이 없는 인스턴스는 헬스체크에 실패해 교체가 반복된다
4. ASG의 `ReplaceUnhealthy`와 `AlarmNotification` 프로세스를 일시 중지한다(본측정 동안만. 실행 상태 조작이라 CLI로 한다)
   - `ReplaceUnhealthy`: 헬스체크 실패로 인스턴스가 교체되면 회차가 끊긴다(11절)
   - `AlarmNotification`: 운영 ASG에는 요청 수 기반 확장 정책(`aws_autoscaling_policy.request_count`)이 있다. 부하에 반응해 2대로 늘면 "서버 1대" 전제가 측정 도중에 깨진다 — `terraform plan`을 보다가 발견했다. 회차마다 하는 instance refresh는 이 둘과 무관하게 동작한다
5. 운영 DB에 시드를 넣는다 — 부하용 사용자 1명과 업로드 코스 3,000건([`seed-bulkhead.sql`](../../../scripts/sql/seed-bulkhead.sql)). 운영 RDS는 인프라를 올릴 때마다 새로 만들어져 비어 있고(스냅샷 복원 없음), 스키마는 첫 앱 기동 때 생긴다(DDL `update`) — 그래서 **앱이 한 번 뜬 뒤에** 넣는다. 시드 결과로 나온 사용자 id와 상세 코스 ID 범위를 회차 설정에 넣는다. 측정 뒤 인프라를 내리면 RDS와 함께 사라진다
6. 부하용 사용자를 정한다. 인증 필터가 토큰의 사용자를 DB에서 조회하므로 운영에 실제로 있는 사용자여야 한다. 토큰은 `LoadTestTokenIssuer`가 서버와 같은 방식(유효 1시간)으로 **회차마다** 발급한다(`LOADTEST_USER_ID`·`LOADTEST_USER_EMAIL`, 비밀키는 SSM에서 읽어 발급 프로세스에만 넘긴다)
7. 인스턴스 안에서 1초 수집과 10초 스레드 덤프를 돌릴 스크립트를 준비한다(측정 결과는 회차 끝에 S3로 옮긴다)
8. 배경 트래픽을 담은 k6 시나리오를 준비한다(`ai-course-arrival.js`의 `fastApi`를 5-3절 구성으로 확장)
9. 새로 만든 k6 스크립트를 **원격 브랜치에 push**하고, loadtest `terraform.tfvars`의 `app_git_ref`를 그 브랜치로 맞춘다. k6 EC2는 부팅 때 그 ref를 clone한다
10. 회차 전에 캐시 키를 지울 수단을 준비한다(5-6절. ElastiCache는 VPC 안에서만 닿으므로 앱 인스턴스에서 실행)

### 10-3. 측정 전 점검 (첫 회차 전에 한 번)

- [ ] 관리 Tomcat이 실제로 분리됐는가 — 스레드 덤프에 `http-nio-8081-exec-*`가 있고 그 개수가 몇인가(앱의 `server.tomcat.threads.max: 32`가 자식 서버에도 적용되는지 단정하지 않고 확인한다)
- [ ] ALB 헬스체크가 `/actuator`로 통과하는가, 응답이 `livenessState`만 담는가(전체 health면 DB 장애 때 교체 폭풍이 된다)
- [ ] A의 JAR에서 `executor_*{name="aiAgentExecutor"}`와 `tomcat_threads_*` 지표가 노출되는가
- [ ] TourAPI 성공 0건인가(AI 요청 1건으로 확인)
- [ ] `ReplaceUnhealthy`·`AlarmNotification`이 일시 중지됐는가

### 10-4. 회차 절차

1. SSM `artifact_key`를 그 회차 버전으로 바꾸고 instance refresh를 시작한다. `Successful`과 타깃 healthy를 확인한다
2. 인스턴스에서 지표 수집·스레드 덤프를 시작한다
3. 캐시 키(`popularCourses::*`, `courseDetail::*`, `courseListItem::*`)를 지운다
4. k6를 시작한다(웜업 → 기준선 → AI 도착 → 회복 관측, 12분)
5. 수집을 멈추고 결과를 S3로 옮긴다
6. 9-1절 무효 조건과 9-2절 원인 분리 표시를 확인한다. 첫 회차라면 7절의 카카오 실측 소모로 남은 일정을 다시 계산한다

### 10-5. 측정 도구

| 파일 | 실행 위치 | 역할 |
|---|---|---|
| [`scripts/k6/ai-bulkhead.js`](../../../scripts/k6/ai-bulkhead.js) | k6 EC2 | 회차 하나의 부하(웜업 → 기준선 → AI 도착 → 회복 관측)와 요청별 로그 |
| [`scripts/loadtest/bulkhead-collect.sh`](../../../scripts/loadtest/bulkhead-collect.sh) | 앱 인스턴스(root) | 8081 1초 수집, 10초 스레드 덤프(jcmd, 없으면 `-devel` 설치, 그것도 안 되면 SIGQUIT), 앱 로그 |
| [`scripts/loadtest/bulkhead-cache-evict.py`](../../../scripts/loadtest/bulkhead-cache-evict.py) | 앱 인스턴스(root) | 캐시 키만 `SCAN` + `UNLINK`로 삭제. 인스턴스에 redis-cli가 없어 표준 라이브러리로 RESP를 직접 말한다 |
| [`scripts/loadtest/aggregate-bulkhead.py`](../../../scripts/loadtest/aggregate-bulkhead.py) | 개발 PC | 6-5·8·9절의 판정. 무효면 종료 코드 4 |
| [`scripts/loadtest/bulkhead-round.sh`](../../../scripts/loadtest/bulkhead-round.sh) | 개발 PC | 10-4절 회차 절차 전체(토큰 → 배포 → 점검 → 캐시 삭제 → 수집 → k6 → 회수 → ALB 지표 → 판정) |
| [`scripts/loadtest/bulkhead-plan.sh`](../../../scripts/loadtest/bulkhead-plan.sh) | 개발 PC | 10회차를 5-5절 순서로 이어 돌린다. 무효 회차는 보관하고 같은 자리에서 한 번 더, 인프라 문제면 멈춘다 |
| [`scripts/loadtest/bulkhead-sql.sh`](../../../scripts/loadtest/bulkhead-sql.sh) | 앱 인스턴스(root) | `.env`의 DB 접속 정보로 SQL 파일을 실행한다. 비밀번호를 인스턴스 밖으로 꺼내지 않는다 |
| [`scripts/sql/seed-bulkhead.sql`](../../../scripts/sql/seed-bulkhead.sql) | 운영 RDS | 부하용 사용자 + 업로드 코스 3,000건. PK는 시퀀스에 맡기고 `created_at`은 `now()`로 넣는다. 로컬 개발 DB에서 롤백 트랜잭션으로 검증했다(ID 범위 연속 3,000) |
| [`scripts/loadtest/bulkhead.env.example`](../../../scripts/loadtest/bulkhead.env.example) | — | 회차 설정 예시. 실제 값은 gitignore 대상인 `results/ai-bulkhead/config.env`에 둔다 |

**운영에 올리기 전 로컬 검증.** 워커 32개를 세마포어로 흉내 낸 모의 서버(AI 요청이 워커를 8초 쥔다)에 같은 도착(시간표 압축, 분당 400건)을 넣어 k6 → 수집 → 집계 → 판정 경로를 확인했다.

| 모의 조건 | 판정 | 근거 |
|---|---|---|
| 입장 제한 없음 | **장애** | 워커 32/32 포화 23초, 배경 p99 10초(타임아웃), 1분 에러율 37%, AI 도착 종료 30초 뒤 회복 |
| 입장 제한 4 | **정상** | 198건 중 182건 즉시 429, 워커 최대 6/32, 배경 p99 기준선 그대로(12ms) |

검증 중 고친 결함 셋:
- 배경 VU를 부하 도중에 늘리다 요청 150건을 건너뛰었다 → 미리 할당(5-3절)
- `dropped_iterations`에는 시나리오의 `api` 태그가 붙지 않아 태그별 확인이 0으로 보였다 → 전체 합계로 판정
- 판정 창이 1분보다 짧으면 1분 에러율 검사를 통째로 건너뛰었다 → 창 전체로 본다(실측의 판정 창 3분에서는 드러나지 않았을 결함이지만, 기준이 조용히 빠지는 구조라 고쳤다)

스레드 덤프 파서는 CallerRuns 스택을 담은 샘플 덤프로, 캐시 삭제는 로컬 Redis의 빈 DB에 캐시 키와 보존 대상 키를 섞어 넣어 확인했다(캐시 키만 지워지고 조회수 증분·인증·락 키는 남았다. 구버전 Redis는 `UNLINK`를 몰라 `DEL`로 내려가는 경로를 더했다).

### 10-7. 준비 중 겪은 일 — 측정 전에 막은 결함

운영에 올린 뒤 측정 전 점검에서 드러나, 그대로 측정했으면 결과를 오염시켰을 것들이다.

| 발견 | 그대로 뒀다면 | 조치 |
|---|---|---|
| 운영의 앱 Tomcat 워커 이름이 `http-nio-0.0.0.0-8080-exec-N`이다(`server.address` 지정). 관리 포트는 `http-nio-8081-exec-N` | 덤프 파서가 앱 워커를 0개로 세어 **CallerRuns를 한 건도 못 잡는다** | 주소 부분이 있어도 없어도 맞추는 정규식으로 바꾸고 운영 덤프로 확인(앱 10 · 관리 10) |
| 운영 ASG에 요청 수 기반 확장 정책(`request_count`)이 있다 | 부하에 반응해 2대로 늘어 "서버 1대" 전제가 깨진다 | `AlarmNotification`도 일시 중지(10-2) |
| ASG의 `suspended_processes`를 terraform이 관리한다 | 측정 중 운영 `apply`를 하면 CLI로 멈춘 프로세스가 되살아난다 | **측정 중에는 운영 apply를 하지 않는다.** SSH 허용 IP를 바꾸는 대신 앱 인스턴스에는 **SSM 세션으로 SSH를 터널링**한다(보안그룹 변경 불필요) |
| `-target=aws_instance.k6`는 인스턴스가 **의존하는** 것만 만든다 | 인터넷 게이트웨이·라우팅과 보안그룹 규칙(인바운드 SSH, **아웃바운드 전체**)이 빠진다. terraform은 보안그룹을 만들 때 기본 아웃바운드 허용을 지우므로 k6 EC2가 밖으로 아무것도 못 해 부팅 스크립트(k6 설치·clone)가 실패한다 | 네트워크 → 보안그룹 규칙 → 인스턴스 순으로 대상을 나눠 적용하고, 실패한 인스턴스는 교체한다 |
| 이 AWS 계정은 프리 티어 플랜이라 허용된 인스턴스 타입만 띄운다 | k6 EC2용으로 고른 t3.large가 거부된다. 원래 값 t3.micro(1GB)는 VU 최대 약 650개를 감당하지 못한다 | 허용 목록 중 `m7i-flex.large`(vCPU 2, 8GB) |
| 명령줄 `curl`의 한글 본문이 UTF-8이 아니게 전달된다(Windows Git Bash) | 점검 요청이 400 | 본문을 UTF-8 파일로 보낸다. k6는 UTF-8이라 측정과 무관 |

측정 전 점검(10-3) 결과 — 두 버전 모두 같은 측정용 설정으로 기동했다.

| | A `46465a4` | B `47c0425` |
|---|---|---|
| 8080 `/actuator` | livenessState만 | livenessState만 |
| `aiAgentExecutor` 최대 / Tomcat 최대 | 8 / 32 | 20 / 32 |
| 입장 제한 지표 | 없음 | 있음 |
| TourAPI | — | 성공 0 · 실패 7(닫힌 포트로 즉시 실패) |
| AI 요청 1건 | — | 201, 21.5초 |
| 수집 스크립트 | — | jcmd 없음 → `-devel` 설치 후 jcmd, 15초에 덤프 2 · 스냅샷 15 · 수집 실패 0 |

### 10-6. 원복

- SSM `env/`에 추가한 네 값(관리 포트, liveness 경로·포함 대상, `TOUR_BASEURL`)을 지운다
- `health_check_path`를 `/actuator/health/liveness`로 되돌려 `apply`한다
- ASG `ReplaceUnhealthy`·`AlarmNotification`을 재개한다
- `artifact_key`를 측정 전 값으로 되돌린다
- loadtest 모듈을 `terraform destroy`로 내린다. `-target` destroy는 대상과 그것에 **의존하는** 리소스만 지우고, 대상이 의존하는 서브넷·보안그룹·키페어는 남긴다. 이 모듈의 state에는 측정 때 `-target`으로 만든 것만 있으므로 전체 destroy가 정확히 그것만 지운다 — 실행 전 `plan -destroy`로 목록을 확인한다

## 11. 시연 측정 — 실제 인스턴스 교체

본측정과 **다른 날** 버전별 1회 한다. ABBA 비교 대상이 아니라 "교체가 실제로 일어나는가"라는 사건을 보여주는 측정이다.

**본측정과 분리하는 이유.** desired=1에서 교체가 시작되면 타깃이 0개가 돼 남은 요청이 모두 ALB 503을 받는다. 그 구간의 지표는 앱 코드가 아니라 **인스턴스 기동 시간**이 정한다. 게다가 교체 시점이 무작위 도착에 따라 회차마다 달라 같은 버전의 두 회차가 서로 다른 실험이 되고, 교체 직후 회차는 다른 출발 상태를 갖는다.

| 항목 | 내용 |
|---|---|
| ASG | `ReplaceUnhealthy` 활성 (운영과 같음) |
| 부하 | 배경 트래픽 + AI 분당 70건을 교체가 시작되거나 **최대 10분**에 닿을 때까지. 10분이면 교체 조건(약 90초 지속) + deregistration 30초를 여러 번 담고도 남아, 10분 안에 교체가 없으면 "교체되지 않는다"로 판정한다 |
| 지표 | 첫 헬스체크 실패 시각, unhealthy 판정 시각, 교체 시작 시각, 새 인스턴스 healthy 시각, 외부 요청이 실패한 총 시간 |
| 예측 | A는 교체가 일어나고 서비스가 수 분 끊긴다. B는 교체가 일어나지 않는다 |

## 12. 확정한 설계 결정

설계 중 사용자와 확인해 정한 것들이다. 근거는 각 절에 있다.

| 결정 | 확정 | 절 |
|---|---|---|
| 비교 대상 | A `3df97b4^1`(#180 직전), B `47c0425` | 2 |
| 인스턴스 수 | 1대 | 10-1 |
| 부하 형태 | 지속 도착, 단계별 독립 회차, 분당 5·20·40·70건, AI 도착 5분 | 5-1 |
| 배경 트래픽 | `/popular` 초당 10 + 코스 상세 초당 10(대상은 운영 DB 시드) | 5-3, 10-2 |
| TourAPI | 생략(닫힌 포트) | 5-4 |
| 일정·규모 | 하루, 단계 순서 40 → 70 → 20 → 5, 10회차(40단계만 ABBA), 회차 12분, 약 3.3시간 | 5-5 |
| 회차 초기화 | 매 회차 instance refresh + 캐시 키만 삭제 | 5-6 |
| 지표 수집 | 관리 포트 8081 분리, 헬스체크는 8080 `/actuator`(liveness만), 운영 Alloy는 쓰지 않음 | 6-2 |
| 판정 임계값 | 저하 3배 그리고 +100ms · 30초 연속 / 장애 p99 1초 · 30초 연속 또는 에러율 1% / 회복 1.5배 또는 +100ms · 30초 연속 | 6-5, 8 |
| 수집 실패·CPU 포화 | 무효가 아니라 "원인 분리 필요"(J3만 보류) | 9-2 |
| ASG 교체·확장 | 본측정은 `ReplaceUnhealthy`·`AlarmNotification` 일시 중지, 버전별 시연 측정은 허용(최대 10분, 본측정과 다른 날) | 11 |
| k6 실행 위치 | loadtest 모듈의 k6 EC2만 `-target`으로 | 10-1 |
| 범위 | 회복 시간 포함. 재시도 폭풍·30일 요청·저녁 지연·LLM stub 제외 | 1-3 |

## 13. 한계

- **서버 1대 기준이다.** 운영 설정의 일부(네이버 초당 25 = 50 ÷ 2대, TPM 여유 계산)는 2대를 전제로 한다
- **오전 시간대의 LLM 속도에서만 잰다.** 저녁 지연에서도 효과가 유지되는지는 보조 실험의 몫이다
- **TourAPI가 빠져** 후보 공급이 운영보다 약 0.7초 짧다. A·B에 똑같이 적용되고 W의 3% 미만이다
- **관측을 위해 관리 포트를 분리했다.** 앱 워커 풀과 헬스체크 경로는 그대로지만, 운영 배포 구성과 완전히 같지는 않다
- **3일 코스만 잰다.** 긴 여행 요청이 B에서도 CallerRuns를 되살리는 경우(#178)는 다루지 않는다
- **반복은 버전·단계당 2회다.** 회차 간 분산은 그 두 값의 차이로만 본다

## 14. 관련 문서

- [llm-performance 로드맵](../llm-performance/README.md) — 비교 대상 PR들의 측정 기록
- [STEP-admission-limit](../llm-performance/steps/STEP-admission-limit.md), [STEP-arrival-rate](../llm-performance/steps/STEP-arrival-rate.md) — 입장 제한과 도착률 측정 도구(#195)
- [STEP-curator-output](../llm-performance/steps/STEP-curator-output.md) — B의 처리 시간 W 실측(#196)
- [STEP-6-budget](../llm-performance/steps/STEP-6-budget.md) — 예산과 `llm.timeout-ms`의 실제 의미, CallerRuns 경로의 예산 부재
- [tomcat-thread-sizing](../tomcat-thread-sizing/README.md) — 운영 워커 32의 근거
- [callerruns-verification](../connection-pool-bottleneck/stage0/production/callerruns-verification.md) — CallerRuns 관측의 선례와 `executor_queued_tasks`의 한계
- [terraform/prod/README.md](../../../terraform/prod/README.md), [docs/guide/cd.md](../../guide/cd.md) — 운영 인프라와 배포 경로
