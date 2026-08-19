# AI 코스 생성 멀티 에이전트 파이프라인 로드맵

> [멀티 에이전트 파이프라인 설계](멀티-에이전트-파이프라인.md)에서 AI 코스 생성(`POST /api/my-courses/ai`)을 단일 LLM 호출에서 멀티 에이전트 파이프라인으로 재설계하기로 했다. 설계 문서가 "왜"라면 **이 문서는 "무엇을 어떤 순서로, 무엇을 확인해야 다음으로 넘어가는지"** 다. 설계 문서의 도입 순서 표를 승계하되 **0단계(사전 준비)를 앞에 추가**하고 각 단계를 체크 가능한 항목으로 분해한다.
>
> **V1 범위**: Planner · CandidateRetrieval(네이버 시더 + TourAPI) · Curator · Grounding · RouteOptimizer(+PlaceUrlEnricher). `CriticAgent`·`CandidateRefiner`·`PlaceSignalStage`(3·4층)는 제외했고, LLM 벤더는 **OpenAI로 확정**됐다.
>
> **설계는 착수 후 네 번 개정됐다**(후보 공급 층 추가 → PlaceSignal 제외 → 카카오를 후보 소스에서 제외 → ATTRACTION 소스로 TourAPI 채택). 각 개정의 근거와 파급은 **설계 문서의 "설계 변경 이력" 표**가 소유한다. 이 로드맵은 그 결과만 반영한다.
>
> **진행 상황: 3단계 완료.** 2단계에서 복합 환각률 25.6% → **7.5%**(모델 교체 효과)를 확인했고 Curator 모델은 `gpt-5.6-luna`로 확정됐다. 0단계 검증은 전부 통과, OpenAI·네이버 키도 발급됐다. **다음은 4단계.**

## 목표

1. **JSON 파싱 실패로 요청이 통째로 실패하는 것을 없앤다.** Gemini 단일 호출의 실측 파싱 실패율은 **16.7%**(5/30) — 사용자가 AI 코스 생성을 여섯 번 시도하면 한 번은 503을 받는다는 뜻이다. **2-6에서 0.0%로 달성됐다.** 다만 **무엇이 이걸 해결했는지는 착수 시점의 추정과 다르다** — 실패 5건이 전부 응답 **절단**이었고 절단은 스키마로 막히지 않으므로, 해결한 것은 구조화 출력이 아니라 **모델 교체**다. 구조화 출력이 실제로 준 것은 출력 바이트 −48%와 스키마 밖 필드 차단이다(2-6).

2. **환각 장소가 사용자 코스에 실리는 비율을 낮춘다.** 현재 실측 환각률은 **25.6%**([AI-HALLUCINATION-GEMINI.md](hallucination/AI-HALLUCINATION-GEMINI.md)) — 코스 하나를 받으면 평균 4곳 중 1곳이 존재하지 않는 장소다. 후보를 3배로 늘리고 카카오 매칭에 **이름 일치를 필수 조건**으로 걸어, 검증을 통과하지 못한 장소는 파이프라인에 아예 존재하지 않게 만든다. **처방은 1-2에서 바뀌었다** — 원래 계획한 점수 하한선은 실측에서 역효과였다.

3. **동선·시간 배치를 LLM 추측에서 실좌표 계산으로 옮긴다.** 좌표를 확보한 뒤 완전탐색으로 최적 순열을 고르므로, "시간 겹침 없음"·"day당 식사 1회"·"동선 역주행 없음"이 프롬프트 규칙이 아니라 **알고리즘 불변식**이 된다.

4. **AI 코스 생성이 다른 API를 죽이지 않게 한다.** 현재 `createAICourse`는 `@Transactional` 하나로 LLM 호출과 카카오 블로킹 호출 N회를 전부 감싸, 최악의 경우 HikariCP 커넥션 1개를 **360초** 점유한다. 이 저장소는 동시성 200에서 이미 커넥션 풀 병목을 실측한 이력이 있다([TASK-PRESIGN-BOTTLENECK.md](../connection-pool-bottleneck/PRESIGN-BOTTLENECK.md)).

5. **LLM 벤더를 코드에서 분리한다.** 에이전트 코드가 벤더 SDK 타입을 한 개도 import하지 않게 해, 벤더 교체가 어댑터 하나의 교체가 되도록 한다. 부수 효과로 에이전트 단위 테스트가 가능해진다(`com.google.genai.Client`가 `public final class`라 현재는 Mockito로 묶을 수 없다).

## 배경 — 현재 구조의 문제

**① 환각을 걸러내는 게 아니라 세탁하고 있다.**
[KakaoLocalClient.java](../../../src/main/java/backend/yourtrip/global/kakao/KakaoLocalClient.java)의 `score()`는 이름 일치 +5 / 주소 일치 +3 / 카테고리 +2로 최대 10점을 매기지만 **하한선이 없다.** `max()`로 최고점을 뽑으므로 0점 후보도 그대로 반환된다. LLM이 지어낸 상호명으로 검색하면 카카오가 그 지역의 무관한 POI를 돌려주고, 그게 사용자 코스에 저장된다. BASELINE 측정이 이 경로를 `LAUNDERED`(진짜 환각)로 분류했다. **1-2에서 해소했다** — 다만 처방은 하한선이 아니라 이름 일치 게이트였다(하한선은 실측에서 역효과).

**② LLM이 지리를 모르는 채로 동선을 짠다.**
[GeminiService.java](../../../src/main/java/backend/yourtrip/global/gemini/service/GeminiService.java)의 95줄 프롬프트 하나가 컨셉 설계 + 장소 선정 + 시간 배치 + 동선 최적화 + 제목 작명을 동시에 요구한다. 좌표 없이 텍스트로만 최적화하니 지그재그 동선이 나오고, 다섯 가지 일을 한 번에 시켜 각각이 다 얕다.

**③ 트랜잭션이 외부 I/O 전체를 감싼다.**
[MyCourseServiceImpl.java](../../../src/main/java/backend/yourtrip/domain/mycourse/service/MyCourseServiceImpl.java)의 `createAICourse`는 `@Transactional` 안에서 타임아웃 없는 LLM 호출과 `block(20초)` 카카오 호출을 최대 18회 수행한다. `open-in-view: false`라 트랜잭션 전체에 커넥션이 묶인다. 이건 품질 문제가 아니라 **가용성 문제**이고, 파이프라인 재설계와 무관하게 우선 고쳐야 한다.

## 확정된 방침

설계 문서가 "미확정"·"착수 전 확인 필요"로 남긴 항목 중 결정된 것들이다.

| 항목 | 설계 문서 상태 | 확정 |
|---|---|---|
| LLM 벤더 | "Gemini는 고정이 아니다. OpenAI로 전환할 가능성이 높다"(LLM 포트 설계) | **OpenAI 확정.** Gemini 어댑터는 만들지 않는다 |
| 어댑터 구현 | "Gemini 쪽이 막히면 OpenAI 어댑터만 Spring AI"(LLM 포트 설계) | **Spring AI `1.1.8` 채택.** `LlmClient` 포트는 그대로 유지. 2.0.x는 Spring Boot 4를 요구해 쓸 수 없다(0단계 판정) |
| 모델 배치 | Gemini 기준 `thinking-budget` | **Planner·Curator = `gpt-5.6-luna`, PlaceProfile = `gpt-5-nano`**(9단계 조건부라 V1에서는 호출되지 않는다) (약 $0.0030/요청). **2-6 실측으로 Curator=luna 확정** — nano는 환각률이 7배라 후보에서 탈락했다. `thinking-budget`의 OpenAI 대응물은 `reasoning-effort`로 확인돼 agent별 설정에 들어갔다 |
| 네이버 API 키 | "착수 전 확인 필요"(남는 한계) | **발급 완료**(0-2). API HUB 이관으로 발급처·엔드포인트·인증 헤더가 바뀌었고 요금은 무료 그대로 |
| **TourAPI 키** | 후보 공급 "ATTRACTION 계열 슬롯" | **개발계정만 쓴다 — 자동승인이라 블로커가 아니다.** 공공데이터포털 상세페이지의 심의유형이 "개발단계: 자동승인"이라 신청 즉시 발급되고, 포털↔한국관광공사 동기화(10~30분) 뒤 호출된다. **운영계정 전환은 계획하지 않는다**(아래) |
| before/after 비교 | 환각률 25.6%(Gemini 단일 호출) | **OpenAI 단일 호출 baseline을 2단계에서 재측정**해 3점 비교 |
| V1 범위 | Critic·Refiner 제외(지연 예산) | **Critic·Refiner에 더해 `PlaceSignalStage`(3·4층)도 제외**했다. 도입 순서의 11단계 전부가 이 로드맵의 범위이며, 9단계는 조건부다 |

**세 결정의 근거는 설계 문서가 소유한다** — 포트는 유지하되 어댑터는 OpenAI 하나만 만드는 것, Spring AI를 `OpenAiLlmClient` 내부 전송 계층에만 가두는 것, agent별로 모델과 `reasoning-effort`를 나누는 것. 상세는 [LLM 연동](design/LLM-연동.md)에 있다. 한 가지만 여기 적어둔다: **agent별 온도 차등은 계획했으나 실행할 수 없다** — 두 모델 모두 커스텀 `temperature`를 거부해 기본값 1로 고정된다([STEP-2](steps/STEP-2-llm-port.md) 판정 6).

## 문서 작성 원칙

- **이 문서에는 체크리스트만 남긴다.** 설계 논의, 발견한 버그, 성능 측정 결과 같은 상세 내용은 단계별 실행 계획서 `steps/STEP-N-*.md`에 적는다.
- **실행 계획서는 해당 단계에 착수하는 시점에 작성한다.** 미리 전부 쓰지 않는다 — 앞 단계의 결과가 뒤 단계의 설계를 바꾸기 때문이다(특히 0단계 Spring AI 검증 결과가 2단계를 좌우한다). 각 `### N.` 섹션에 걸린 `steps/` 링크는 **아직 파일이 없는 상태가 정상**이다.
- **구현하며 계획이 바뀌면 이 문서의 항목을 직접 고쳐 최신 상태로 유지한다.** 이 문서는 "무엇을 어떤 순서로"만 담으므로 소급 기록으로 길어지면 그 역할을 잃는다 — **바뀐 이유와 경위는 `steps/STEP-N-*.md`가 소유한다.** 이미 확정된 지표의 정의처럼 값 자체를 고치면 비교가 깨지는 것만 `> **[정정]**` 인용블록으로 남긴다
- **항목 번호는 식별자이지 실행 순서가 아니다.** 순서는 나열된 위치가 정하고, 번호는 다른 문서와 코드 주석이 참조하므로 한 번 붙이면 바꾸지 않는다. 그래서 `~~5-4~~`처럼 비는 번호도, `4-10`처럼 맨 앞에 오는 번호도 정상이다
- 완료된 항목은 `- [x]`로 반영한다.

## 적용 원칙 (진행 방식)

- 체크리스트는 **한 항목씩** 적용한다. 여러 항목을 묶어 구현하지 않는다. 항목이 커 보이면 더 잘게 쪼갠다.
- **요구사항·설계·구현 방식 중 모르거나 애매한 부분이 있으면 임의로 판단하지 않는다.** 작업을 멈추고 먼저 질문한 뒤 답변을 받고 진행한다.
- **매 `### N.` 섹션이 끝날 때마다 애플리케이션을 실행해 정상 기동/동작을 확인한다.** 컴파일 성공으로 끝내지 않는다.
- **API 동작에 영향을 주는 단계(1, 8, 9)는 실제 요청으로 E2E 검증한다.** 설정·순수 함수만 추가하는 단계는 대상에서 제외한다.
- **외부 API를 실제로 호출하는 작업은 비용과 쿼터를 소모한다.** 특히 2·11단계의 baseline 측정은 30요청 × LLM 호출이므로, 실행 전에 반드시 확인을 받는다.
- 이 원칙은 [CLAUDE.md](../../../CLAUDE.md)의 "작업 방식 (포트폴리오 저장소 특성)"과 연결된다.

## 적용 체크리스트

단계 순서는 설계 문서의 도입 순서를 따른다. **동작 변화가 없는 커밋을 앞에 쌓고, 스위치는 8단계 하나로 몰아** 문제 시 그 커밋만 revert할 수 있게 하는 것이 이 순서의 요지다.

### 0. 사전 준비

동작 변화 없음. **여기서 나온 판정이 2·4단계의 설계를 바꿨다.**

> 검증 방법·판정 결과·근거는 [STEP-0-prerequisites.md](steps/STEP-0-prerequisites.md) 참고.

- [x] 0-1. OpenAI API 키 발급 + 크레딧 충전
- [x] 0-2. 네이버 검색 API 키 발급 — **NCP 콘솔의 NAVER API HUB**에서. 검색 API가 developers.naver.com에서 이관돼 발급처·엔드포인트·인증 헤더가 바뀌었다(요금은 무료 그대로). **한도 초과 시 429** — 4단계 fail-open 분기의 기준
- [x] 0-3. **Spring AI 구조화 출력 검증 — 전제 성립.** 스키마가 전부 `response_format.json_schema`에 `strict: true`로 나간다(공식 SDK 폴백 불필요). 실 API에서도 두 모델 다 지원. **단 최상위 배열 스키마는 400으로 거부된다** — 6단계 Curator 스키마의 제약
- [x] 0-4. 모델 배치 확정 — **Planner·Curator = `gpt-5.6-luna`, PlaceProfile = `gpt-5-nano`**(9단계 조건부). 약 $0.0030/요청
- [x] 0-5. 쿼터 확인 — 네이버 **일 25,000건**(지역검색 ~18~30회/요청 → 약 830~1,400요청), 카카오 100,000/일(~20~33회), TourAPI **일 1,000건**(≤9회, 격자 캐시). **네이버가 먼저 한계에 닿지만 fail-open이라 서비스는 죽지 않는다**
- [x] 0-6. **테스트 인프라 신설** — `src/test/resources` + `application-test.yml`, `wiremock-standalone`(셰이딩판 — 클래스패스에 Jackson·Guava가 이미 경합 중이라)
- [x] 0-7. `build.gradle`에 Java 21 toolchain 고정

### 1. 기존 결함 수정

동작 변화 **있음(버그 수정)**. 파이프라인과 독립적으로 옳은 수정이라 작업이 중단돼도 가치가 남는다.

> 상세 실행 기록은 [STEP-1-existing-defects.md](steps/STEP-1-existing-defects.md) 참고.

- [x] 1-1. `Place`의 `@Builder` 파라미터를 `double` → `Double`로 (좌표 `0.0/0.0` 저장 차단) + `PlaceMapper.toCopyEntity`의 언박싱 NPE. **응답 DTO 3종과 `PlaceCacheItem`도 함께 승격해야 했고, 좌표가 nullable이 되므로 API 계약 변경이라 FE 공유가 필요하다**
- [x] 1-2. `KakaoLocalClient`에 **이름 정규화 + 이름 일치 필수 게이트**. 계획했던 총점 하한선은 **역효과라 폐기했다** — 검색어가 "지역명 + 장소명"이라 주소·카테고리 가점만으로 5점이 나와, 하한선이 정답 밴드를 버리고 불량 밴드를 남긴다. `score()` 자체는 건드리지 않았다(하네스 비교 가능성). 근거는 [BASELINE-ARTIFACT-ANALYSIS.md](hallucination/BASELINE-ARTIFACT-ANALYSIS.md) 판정 1·2
- [x] 1-3. `KakaoConfig`의 `WebClient`에 connect/response 타임아웃 + 커넥션 풀 명시, `.block(20초)` 제거. **catch를 `WebClientException`으로 확장하는 것이 한 세트다**(타임아웃은 `IllegalStateException`이라 기존 catch를 빠져나갔다). 호출당 최악 지연 20초 → 5초
- [x] 1-4. `buildKeywordsJson(null)` NPE 수정 + `AICourseCreateRequest.keywords`에 `@NotEmpty`
- [x] 1-5. `createAICourse`의 `@Transactional` 경계 분리 — 외부 I/O를 밖으로, 저장만 짧은 트랜잭션으로. **`AiCoursePersister`를 별도 빈으로 둔다**(자기호출은 프록시를 우회한다). 걸림돌은 어노테이션이 아니라 더티체킹 의존이라 `ResolvedPlace`/`ResolvedDay` 중간 표현으로 "다 모은 뒤 저장" 순서로 뒤집었다
- [x] 1-6. 회귀 테스트 (56 → 73개). 커넥션 점유 before/after는 **8단계 E2E로 미룬다** — 요청마다 LLM을 호출해 부하 테스트가 부적합하다
- [x] 1-7. **E2E 검증 완료(로컬)** — 순천 3일 201/14.4초, 장소 12개 중 `0.0/0.0` **0건**, 매칭 실패 2건은 좌표 `null`로 저장. `keywords` 생략·빈 배열 모두 400
### 2. `LlmClient` 벤더 중립 추상화 + 설정 외부화 + baseline 재측정

동작 변화 없음(기존 `GeminiService` 경로는 그대로 둔다).

> 상세 실행 기록은 [STEP-2-llm-port.md](steps/STEP-2-llm-port.md) 참고 — 측정이 전제 두 개를 뒤집었다.

- [x] 2-1. `LlmClient` 포트 + `LlmCall` record. `responseJsonSchema`는 벤더 타입이 아니라 **JSON 문자열**로 받는다(벤더 중립의 핵심). 2-6이 "구조화 출력을 끈" 측정점을 필요로 해서 **nullable로 뒀다**
- [x] 2-2. `OpenAiLlmClient` 어댑터 — 전송 계층은 Spring AI. **auto-config를 쓰지 않고 어댑터가 `OpenAiChatModel`을 직접 조립한다** — 켜면 API 키가 기동 필수가 되어 이 단계가 동작 변화를 만들고, `baseUrl`을 못 바꿔 WireMock 검증이 불가능해진다
- [x] 2-3. `AiLlmProperties` 등 `@ConfigurationProperties` 도입 — agent별 model·`reasoning-effort`·`max-output-tokens`, `timeout-ms`, `max-concurrent-calls`, retry. **이 저장소 최초의 `@ConfigurationProperties`다**(기존은 전부 `@Value`)
- [x] 2-4. `LlmResponseParser` + 재시도 2계층 — 전송(`LlmRetryExecutor`, 429/5xx 지수 백오프)과 의미(200인데 깨진 JSON → 어댑터가 1회 재호출). **재시도 계층이 셋으로 흩어져 있던 것을 발견해 제거했다** — 설정 3회가 실제 HTTP 6회로 관측됐고, 원인은 Spring AI의 `RetryTemplate` · 선언조차 안 된 전이 의존성 Apache HttpClient 5 · 우리 executor였다. **Spring AI가 429를 재시도 불가로 분류하는 것도 뒤집었다**([STEP-2](steps/STEP-2-llm-port.md) 판정 1·2)
- [x] 2-5. 포트 기반 단위 테스트 — 에이전트가 벤더 SDK 타입을 import하지 않음을 소스 스캔으로 단언. 어댑터는 실제로 쓴다는 것도 함께 단언해 검사기가 헛돌지 않음을 보인다
- [x] 2-6. **OpenAI 단일 호출 baseline 재측정** — `BASELINE_MODEL`(luna/nano) × `BASELINE_SCHEMA_MODE`(prompt/json_schema) **4조합 120요청 전량 완료**. 결과와 그것이 뒤집은 전제 둘은 성공 기준 참고. **Curator 모델이 `gpt-5.6-luna`로 확정된 근거다**

### 3. `RouteOptimizer` + `SlotType` + `GeoUtils`

동작 변화 없음. 순수 함수라 단위 테스트가 완전히 결정론적이다.

> 상세 실행 기록은 [STEP-3-route-optimizer.md](steps/STEP-3-route-optimizer.md) 참고 — 설계가 비워둔 계수 셋을 정하는 과정과, 식사 윈도우 배정의 첫 구현이 벌점의 목적과 정반대였던 사건.

- [x] 3-1. `SlotType` enum — 체류시간·인기도 가중치·허용 카테고리 코드를 enum이 소유. LLM이 내보낼 필드가 하나 줄고 튜닝이 코드 리뷰 대상이 된다
- [x] 3-2. `GeoUtils` — haversine(반경 6371.0088km). **내부 항을 `[0,1]`로 클램프해야 했다** — 대척점에서 `NaN`이 나오면 예외 없이 최적 순열 선택만 망가진다
- [x] 3-3. `RouteOptimizer` 완전탐색 — 비용은 순수 TSP가 아니라 **거리 + 식사 시간창 위반 + 하루 초과**. 계수보다 **단위를 먼저 맞춰야 했다**(`DISTANCE_WEIGHT`를 "km당 분"으로 정의해 세 항을 분으로 환산 — [STEP-3](steps/STEP-3-route-optimizer.md) 판정 1)
- [x] 3-4. 시간 모델 — `t[i] = t[i-1] + 체류 + 이동`, travelMode별 유효속도·고정 오버헤드. **`startTime` 5분 올림은 출력 직전 한 번만** 한다(계산 중에 하면 표시용 반올림이 장소를 지운다). 내부 시각은 `LocalTime`이 아니라 `int`(자정 랩어라운드가 초과 판정을 뒤집는다)
- [x] 3-5. 하루 초과 처리 — 체류 0.8배 축소 → 후순위 드롭 → day당 최소 3개에서 중단. **하루 종료 기본값을 23:59로 넓혀 기본값에서는 발동하지 않지만**, `dayEndTime`이 요청 필드라 Planner가 이른 종료를 넘기면 살아난다. 드롭 서열에 **`popularityWeight`를 쓰면 안 된다**(신호의 신뢰도이지 중요도가 아니라, 관광명소가 카페보다 먼저 버려진다)
- [x] 3-6. 단위 테스트 129개 + `@Tag("benchmark")` 벤치마크. **임계값 7이 데이터로 뒷받침된다** — `n=7`은 3일 1.77ms인데 `n=8`은 15ms로 지연 예산 `<10ms`를 이미 넘는다. **NN + 2-opt 폴백은 구현하지 않았다**(Planner가 슬롯을 3~6개로 clamp해 도달 경로가 없다)
### 4. `NaverLocalClient` + `TourApiClient` + 후보 공급 순수 함수

동작 변화 없음. 5단계보다 앞에 두는 이유는 클라이언트와 순수 함수가 외부 의존이 적고 단위 테스트가 가능해, 먼저 검증해두면 5단계가 조립에만 집중할 수 있기 때문이다.

> 설계 근거는 [지식 신호 층과 후보 공급](design/지식-신호와-후보-공급.md). 상세 실행 계획은 [STEP-4-candidate-sources.md](steps/STEP-4-candidate-sources.md) (착수 시 작성).

- [x] 4-10. **지역 티어별 환각률 소급 집계** — **Gemini 아티팩트**(389장소)를 유명/무인지로 다시 집계했다. OpenAI 산출물은 소실돼 대상을 바꿨고, 가설 검증이 목적이라 before 데이터로 결론은 같다. **가설 성립** — 복합 환각률 FAMOUS 13.9% vs MINOR 35.4%(2.5배, p ≈ 0.0002). 결과는 [AI-HALLUCINATION-GEMINI.md](hallucination/AI-HALLUCINATION-GEMINI.md)의 "지역 티어별 소급 집계"
- [x] 4-2. **네이버 실호출 확정** — **경로는 `/search/v1/local`**(레거시 `/v1/search/local.json`은 404). **설계를 가르는 둘은 통과** — `mapx`/`mapy`는 WGS84 × 10⁷(정밀도 7자리), 서술어 매칭이 작동해 **스타일 modifier를 채택**한다(9단계 조건 미발동). 어긋난 셋은 4-1·4-4·9-2에 반영했다
- [x] 4-1. **`NaverLocalClient`** (지역검색 시더, 전 슬롯) — `"{area} {searchHint}"` + `sort=comment` + `display=5`. **V1의 네이버 의존은 이 클라이언트 하나다.** 상호명(`<b>` 태그 스트립)·`roadAddress`·`category`·`mapx`/`mapy`를 **모두** 취한다 — 좌표를 SEEDED의 실좌표로 쓰므로 "상호명만 쓰고 카카오로 공식화"는 철회됐다. 반환은 예외가 아니라 `Found`/`Empty`/`Failed` — **`Empty`와 `Failed`를 가르는 것이 핵심**이고, `start`·`sort`는 노출하지 않는다
- [x] 4-3. **키워드→스타일 modifier 사전** (순수 함수) — 사용자 키워드를 traits 닫힌 태그 집합의 가점 태그 상위 1~2개로. **여기에 LLM을 쓰지 않는다.** 4층이 V1에서 빠져도 이 사전은 살아 있다. **어휘는 4-9와 합쳐 `StyleTag` 31개 하나로 뒀고**(설계의 두 표가 어긋나 있었다), `KeywordType` 20개 중 13개를 채웠다 — duration·`NORMAL`·`FOOD`·`SHOPPING`은 비우는 것이 결정이다 **검색어 표기는 26개 전량 실호출로 확정했다**(3라운드·122회) — 21개 유지, `도보`→`역근처`·`한적한`→`숨은`·`고급`→`프리미엄` 교체, `통창`·`시끌벅적`·`아늑한`은 비웠다
- [x] 4-4. **네이버 `category` → `SlotType` 매핑 사전** (순수 함수) — SEEDED에 카테고리 하드 제약을 걸기 위한 것. TourAPI 후보는 `contentTypeId`로 같은 제약. 매핑에 없는 분류는 통과시키되 표시(감점, 하드 드롭 아님). **최상위가 아니라 가장 구체적인 분류가 이긴다** — `음식점>카페,디저트`가 실재해서 최상위 규칙으로는 카페가 MEAL이 된다(4-2 실측). 판정은 동등 비교가 아니라 네이버가 실제로 가를 수 있는 단위(ATTRACTION·VIEWPOINT·WALK는 한 묶음)로 한다
- [ ] 4-7. **`TourApiClient`** (관광지 커버리지) — `locationBasedList2(좌표, radius=20000, contentTypeId=12|14|28, 거리순)`. 반경은 튜닝값이 아니라 최대 고정 울타리이고 실질 필터는 거리순 + cap이다. 캐시 키 `(~1km 격자, contentTypeId)` TTL 7일. **착수 전 실호출 확정 항목은 설계 문서의 "착수 전 확인 필요" 그대로** — 그중 **분류체계(`cat1~3` vs 신 체계)가 최우선**이다(4-9가 통째로 이걸 전제한다). 키는 개발계정 자동승인이라 대기가 없다
- [ ] 4-5. **후보 dedupe·매칭 키** (순수 함수) — MEAL/CAFE/SHOPPING은 provider가 하나라 쿼리 간 중복만. **관광 슬롯은 시더↔TourAPI 매칭이 필요하고 좌표와 이름을 둘 다 봐야 한다** — 좌표만 쓰면 대릉원 안의 천마총이 합쳐지고 이름만 쓰면 전국의 "향교"가 합쳐진다. **초기 임계값은 4-7 실호출 표본으로 정하고, 5-9 후보 공급 실측에서 조정한다**
- [ ] 4-8. **area 지오코딩** (카카오) — Planner의 `anchor`를 `"{location} {anchor}"`로 검색해 권역 중심 좌표를 얻는다. **캐스케이드** `anchor` → `area` → `location`: 무결과면 다음 단계로, **호출 실패면 중단**(같은 API를 두 번 더 두드릴 이유가 없다). 전부 실패하면 그 day의 TourAPI만 건너뛴다. 캐시 TTL 30일. 메트릭 `ai.geocode{result=hit|fallback_area|fallback_location|failed}`. **여기가 파이프라인의 첫 카카오 호출이라 `KakaoLocalClient`를 먼저 손봐야 한다** — 호출 실패를 예외가 아니라 **결과 값으로** 돌려주고 무결과와 구분하도록(설계 문서의 부분 실패 전략). 기존 단일 호출 경로는 호출부가 예외를 던져 동작 변화가 없다
- [ ] 4-9. **`cat3` → 스타일 태그 결정론 매핑** (순수 함수) — TourAPI 소분류를 4-3과 **같은 traits 어휘**로. **필터가 아니라 표시**다 — 후보에 `styleTags`를 달아 Curator 입력과 목록 정렬에 쓴다. 코드표는 4-7 실호출로 확정
- [ ] 4-6. 단위 테스트 (순수 함수 전량) + `NaverLocalClient` 스텁 테스트

### 5. `CandidateRetrievalStage` + `GroundingStage` + `PlaceUrlEnricher`

동작 변화 없음(파이프라인이 아직 컨트롤러에 연결되지 않는다). GroundingStage의 역할은 "카카오 검색"이 아니라 **"실존 확인 + 좌표 확보"** 다 — SEEDED는 네이버, LISTED는 TourAPI 응답을 승계하고 **SUGGESTED만 카카오를 호출한다.**

> 설계 근거는 [지식 신호 층과 후보 공급](design/지식-신호와-후보-공급.md). 상세 실행 계획은 [STEP-5-grounding.md](steps/STEP-5-grounding.md) (착수 시 작성).

- [ ] 5-1. 스레드풀 2개 신설 — `aiAgentExecutor`(LLM)와 `placeGroundingExecutor`(장소 API 공유). **벌크헤드로 나누는 이유**는 장소 API가 느려질 때 그 대기가 LLM 슬롯을 잠식하면 안 되기 때문이다. **여기서 `llm.max-concurrent-calls: 2`를 재실측한다**(2-6은 동시 호출 1이라 조건이 느슨했다)
- [ ] 5-8. **`CandidateRetrievalStage`** — day × 슬롯타입 병렬로 후보 목록을 만든다. 소스는 `CandidateSource` 둘 — `NaverLocalSeedSource`(전 슬롯, `SEEDED`) + `TourApiSource`(관광 슬롯, `LISTED`). 병합 후 사전식 정렬 + cap 20~25, `listIndex` 부여. **카카오는 후보 소스가 아니다.** 스타일 modifier 확장·병합 규칙·캐시 키는 설계 문서 그대로. **fail-open** — 전부 실패하면 빈 목록으로 Curator 실행(초안 구조로 degrade). 메트릭 `ai.candidate.retrieval`·`ai.candidate.adopted`
- [ ] 5-9. **후보 공급 실측** — 하네스 지역 세트로 시더의 슬롯당 확보 건수·빈 결과 비율, 관광 슬롯의 시더↔TourAPI 겹침·오매칭 표본(4-5 임계값 근거). 빈 결과가 잦은 지역이 있으면 그때 "0건일 때만 카카오" 폴백을 붙인다
- [ ] 5-2. `GroundingStage` — **`SUGGESTED`만 카카오 병렬 검증**, 이름 일치 게이트를 통과 못 하면 탈락(1-2에서 점수 하한선을 대체했다). **호출 실패·무결과·이름 불일치 모두 그 후보만 탈락**시키고 사유별로 메트릭을 남긴다 — 예외를 올리면 15건 중 하나가 429일 때 코스 전체가 죽는다. **검증 성공 시 `place_url`을 함께 승계**해 5-10이 같은 장소를 다시 부르지 않게 한다. `SEEDED`·`LISTED`는 **코드가** 응답을 승계해 호출 없이 통과. 4-5의 키로 전 day dedupe
- [ ] 5-3. 슬롯별 카테고리 하드 제약 — `category_group_code`를 가점 +2에서 하드 제약으로 승격(MEAL←FD6, CAFE←CE7, ATTRACTION←AT4/CT1). SEEDED에는 4-4 매핑으로 같은 제약. 비용이 사실상 0인데 "점심에 호프집"이 구조적으로 사라진다
- [ ] ~~5-4. `PlaceSignalStage`~~ → **9단계로 이동** (V1 제외, 조건부). 번호를 당기면 참조가 흔들리므로 이 자리는 비워둔다
- [ ] 5-10. **`PlaceUrlEnricher`** — 배치 확정 뒤 **URL이 빈 장소(`SEEDED`·`LISTED`)에만** 카카오 1회. **수락 조건 둘**: 이름 일치 게이트 통과 **그리고** 좌표 거리 ≤ 300m. 하나라도 미달이면 `null` — **엉뚱한 URL은 URL 없음보다 나쁘다.** fail-open, 전용 ErrorCode 없음. 메트릭 `ai.place.url{result=hit|name_mismatch|too_far|failed}`
- [ ] 5-5. 파이프라인 하드 데드라인 — `CompletableFuture.allOf(...).get(remainingMs)`. `CallerRunsPolicy`를 유지하되(거부보다 느린 성공이 낫다) 요청 스레드가 I/O를 직접 수행해 순차 실행으로 퇴화하는 것을 데드라인으로 막는다
- [ ] 5-6. `ai.grounding.match{result=hit|name_mismatch|no_result|failed, source=seeded|listed|suggested}` — **환각률의 운영 프록시이자 이 작업의 핵심 지표.** `no_result`(순수 환각)와 `failed`(인프라)를 반드시 갈라야 한다 — 뭉치면 카카오 장애 때 환각률이 부풀어 3점 비교가 오염된다. `source` 태그는 "무인지 지역일수록 파라메트릭이 약하다"는 가설을 운영 데이터로 검증한다. 이 저장소 최초의 커스텀 Micrometer 메트릭이라 `MeterRegistry` 주입 패턴을 여기서 세운다
- [ ] 5-11. `ai.llm.call{agent, provider, outcome}` — 에이전트별 지연·실패율. 2단계에서 만든 `OpenAiLlmClient`에 붙이는 것이라 새 코드가 아니다
- [ ] 5-7. 스텁 기반 통합 테스트 (0-6의 WireMock 인프라 사용)
### 6. `PlannerAgent` / `CuratorAgent`

동작 변화 없음.

> 상세 실행 계획은 [STEP-6-agents.md](steps/STEP-6-agents.md) 참고. (미작성)

- [ ] 6-5. **`duration` 키워드 처리 방침 결정** — 무시할지, `days`와의 모순 검증에 쓸지. 지금은 "보내지만 아무도 해석하지 않는" 상태이며 label 표기도 어긋나 있다(설계 문서의 프롬프트 전략)
- [ ] 6-1. `PromptLoader` — 프롬프트를 `resources/prompts/*.md`로 분리하고 `@PostConstruct`에서 eager 로드. 파일이 없으면 **애플리케이션 기동이 실패**하므로 런타임이 아니라 배포 시점에 발견된다. 플레이스홀더는 위치 기반 `%s`가 아니라 **명명 기반 `{{location}}`**
- [ ] 6-2. `PlannerAgent` — 컨셉·제목·day별 권역(`area`)·**권역 안 랜드마크(`anchor`, 필수 문자열 1개)**·슬롯 구성. **장소명은 한 개도 생성하지 않는다**(`anchor`는 검색 기준점일 뿐 코스에 들어가는 장소가 아니다). `area`는 자연어 그대로 둔다 — 시군구로 바꾸면 day 단위 locality를 잃는다(설계 문서의 후보 공급 "area → 좌표"). 결정론적 기본 플랜은 `anchor = location`. Planner를 별도 단계로 두는 이유는 "단계 분할"이 아니라 **Curator를 day별 병렬 실행하려면 day별 권역이 먼저 확정돼야 하기 때문**이다
- [ ] 6-3. Planner 출력 구조 검증 — day 수 불일치·MEAL 누락·슬롯 개수 초과를 **코드로 보정**한다(LLM 재호출 없음). `anchor`가 비면 `area` 텍스트로 대체(지오코딩 캐스케이드 4-8이 이어받는다)
- [ ] 6-4. `CuratorAgent` — day별 병렬, 슬롯당 후보 3개를 **선호 순서로**. 다른 day는 모른다. **역할은 "회상"이 아니라 "선별"이다** — 입력에 5-8의 후보 목록(`[seed n위] 이름 · styleTags · distanceKm`)이 들어가고 출력은 `source` + `listIndex` + `placeName`. 선별 규칙은 설계 문서의 우선순위대로 프롬프트에. **응답 스키마의 루트는 반드시 객체여야 한다**(0-3에서 최상위 배열이 400으로 거부되는 것을 확인했다)
- [ ] 6-7. **`SEEDED`/`LISTED` 위조 강등 검증 (코드)** — `listIndex` 범위, 목록 항목 상호명과 `placeName` 일치, 슬롯 타입 일치를 검증하고 하나라도 어긋나면 `SUGGESTED`로 **강등**(버리지 않는다 — 실존할 수 있다). "재검증 생략"의 전제는 좌표·`kakaoId`를 LLM이 옮겨 적는 게 아니라 코드가 목록에서 승계하는 것이므로, 응답 스키마에 좌표·id 필드를 두지 않는다. 메트릭 `ai.candidate.demoted`
- [ ] 6-6. 프롬프트에서 사라진 규칙 확인 — 시간 배치·동선·중복·스키마 강제는 이제 코드가 보장하므로 프롬프트에 남기지 않는다. 약 45줄이 사라지고 "취향과 컨셉"만 남는 것이 이 분리의 본질이다

### 7. `AiCoursePipeline` 오케스트레이터 + 폴백

동작 변화 없음(컨트롤러 미연결).

> 상세 실행 계획은 [STEP-7-pipeline.md](steps/STEP-7-pipeline.md) 참고. (미작성)

- [ ] 7-1. `AiCoursePipeline` — Planner → **CandidateRetrieval** → Curator → Grounding(SUGGESTED만) → RouteOptimizer → **PlaceUrlEnricher** 조립. LLM 호출 `1 + days`회, PlaceSignal 없음
- [ ] 7-2. `AiCourseErrorCode` 신설 — `AI_PLAN_FAILED`·`AI_RESPONSE_INVALID`·`AI_GROUNDING_FAILED`(503) / `AI_COURSE_TIMEOUT`(504, 5-5 데드라인) / `AI_COURSE_BUSY`(429, 세마포어 포화). `ErrorCode` 인터페이스 구현이라 `GlobalExceptionHandler`는 수정하지 않는다 + `JSON_TRANSFORMATION_FAILED` 오용 정리 — 지금은 방향이 정반대인 두 실패(응답 역직렬화 / 키워드 직렬화)가 같은 코드를 공유한다
- [ ] 7-3. **degrade, don't fail** 폴백 전량 구현 — Planner 실패 시 결정론적 기본 플랜, **후보 공급 실패 시 빈 목록으로 진행(초안 구조로 degrade)**, Curator 실패 시 **후보 목록에서 결정론적 채움**(정렬된 목록 상위 3 → 목록이 없으면 카카오 카테고리 검색 폴백), 후보 개별 탈락, 네이버 fail-open, 슬롯 전멸 시 보충
- [ ] 7-4. **hard fail은 카카오 전면 장애 하나뿐**(`AI_GROUNDING_FAILED` 503). 좌표 없는 코스는 이 기능의 핵심 가치를 잃는다 — **지금 코드가 `0.0/0.0`으로 저장해 성공을 위장하는 것이 정확히 그 실수다**
- [ ] 7-5. `ai.course.pipeline.duration{stage}` 메트릭 (202 전환 판단의 근거가 된다)
- [ ] 7-6. 폴백 경로별 테스트

### 8. AI 코스 생성 경로 교체 (스위치)

동작 변화 **있음 — 이 단계가 유일한 스위치다.** 문제가 생기면 이 커밋만 revert하면 된다.

> 상세 실행 계획은 [STEP-8-switch.md](steps/STEP-8-switch.md) 참고. (미작성)

- [ ] 8-1. `createAICourse`를 파이프라인 호출로 교체. `userId`를 요청 스레드에서 **미리 확보해 명시적으로 넘긴다** — `getCurrentUserId()`는 `SecurityContextHolder`를 읽는데 파이프라인이 다른 스레드에서 돌면 `SecurityContext`가 전파되지 않아 인증 정보가 사라진다
- [ ] 8-2. `AiCoursePersister` 분리 확정 (1-5에서 이미 도입했다면 파이프라인 결과를 받도록 조정)
- [ ] 8-3. **삽입 순서 = 표시 순서 보장** — `DaySchedule.places`에 `@OrderBy("id ASC")`가 걸려 있고 별도 `sequence` 컬럼이 없다. 최적화된 순서 그대로 `save()`해야 동선 순서가 재현된다
- [ ] 8-4. `global/gemini` 패키지 삭제 + `GEMINI_API_KEY` 제거 (`.env.example`, `application.yml`)
- [ ] 8-7. **사용자 삭제 로그 기록을 스위치와 함께 켠다** — `DELETE .../places/{placeId}` 이벤트를 장소의 `source`(`SEEDED`/`LISTED`/`SUGGESTED`)·`modifier`(스타일 쿼리 유래 여부) 태그와 함께 남긴다. **9단계 착수 조건 네 개 중 둘이 이 데이터에서만 나오고**, TourAPI 축제·Planner `styleTags` 재검토도 같은 근거를 쓴다. **소급할 수 없으므로 스위치와 동시에 켜야 한다** — 늦게 켜면 그만큼 판단이 밀린다(설계 문서의 관측)
- [ ] 8-5. E2E 검증 — 실제 요청으로 코스 생성, 좌표·시간·순서 확인
- [ ] 8-6. **파이프라인 환각률 측정** (3점 비교의 마지막 점)

### 9. `PlaceSignalStage` (3층 인기도 + 4층 속성 추출) — **조건부**

동작 변화 있음(품질). 8단계까지로 이미 확실히 낫기 때문에(환각 차단 + 실좌표 동선 + 인기 시드) **순수 부가가치이고, 그 부가가치가 실재하는지는 측정된 바 없다.** 그래서 "일단 만들어 플래그로 붙이는" 게 아니라 **아래 조건 중 하나가 실측되면 그때 만든다.** 조건은 전부 8-7의 삭제 로그에서 나온다.

> **착수 조건** (하나면 충분)
> - `SUGGESTED` 유래 장소의 삭제율이 `SEEDED` 대비 유의미하게 높다 → **3층**(인기도·폐업 감점)의 근거
> - 스타일 modifier 쿼리 유래 장소의 삭제율이 기본 쿼리 유래 대비 유의미하게 높다 → **4층**(속성 검증)의 근거 — SEO 편승이 실제 문제라는 뜻
> - 4-2 실측에서 네이버가 서술어를 매칭하지 못해 스타일 축을 retrieval에서 포기했다 → 4층이 스타일 축을 되찾는 유일한 길
> - 골든 데이터셋 평가에서 Curator 자체 순위 대비 3·4층 재정렬이 개선을 보인다
>
> **착수 순서**: 3층 → 4층. Critic은 4층 뒤에만 성립한다(traits를 전제하므로). 설계는 [지식 신호 층](design/지식-신호와-후보-공급.md)의 3·4층 절에 그대로 있다. 상세 실행 계획은 [STEP-9-place-signal.md](steps/STEP-9-place-signal.md) (착수 조건 충족 시 작성).

**3층 — 인기도**
- [ ] 9-1. `NaverBlogClient` — `display=5` 한 번에 `total`(인기도) + `postdate`(최신성) + 스니펫 5건(4층 재료)이 전부 온다. **장소당 호출 1회.** 경로는 `/search/v1/blog`로 추정되나 **이 키에서 아직 미활성화(401)라 콘솔 활성화가 착수 조건**이다(4-2 실측). 착수 전 확정: 응답 스키마, **지역검색과 쿼터 합산 여부**
- [ ] 9-2. `PopularityScorer` (순수 함수) — `popularity = log10(max(total, 1))`. **로그 스케일이 필수인 이유**: `total`이 자릿수로 벌어져 선형으로 쓰면 유명 관광지 하나가 다른 신호를 압도한다. 폐업 의심 = 최신 `postdate`가 12개월 이내인지. **전제 확인 필요 — 지역검색의 `total`은 반환 건수라 이 용도로 쓸 수 없었다**(항상 `display`와 같다). 블로그 API의 `total`이 전체 매칭 수인지 9-1에서 먼저 확인한다
- [ ] 9-3. `PlaceSignalStage` — 그라운딩 생존 후보에만 조회. **`SEEDED`·`LISTED`는 제외**(시드 순위가 이미 인기도이고 TourAPI 관광지의 인기 축도 시더가 맡는다) → `SUGGESTED`만. **역할은 재정렬이 아니라 감점.** fail-open. 메트릭 `ai.popularity.lookup{result}`
- [ ] 9-4. 3층 on/off 비교 — 삭제율로

**4층 — 속성 추출 (옛 9-1~9-6)**
- [ ] 9-5. `PlaceProfileAgent` — 블로그 `title`·`description`에서 **평가가 아니라 속성**을 추출. 요약 프롬프트가 "이 장소가 좋은가"를 물으면 협찬 문구를 그대로 받아 적는다. **광고비가 "루프탑이 있다"를 바꾸지는 못한다.** 모델은 `gpt-5-nano`. **temperature는 지정할 수 없다**(모델이 커스텀 온도를 거부한다 — 2-6) — 속성 추출에 필요한 충실성은 닫힌 태그 집합과 스키마 강제로 대신 확보한다(9-6)
- [ ] 9-6. 닫힌 태그 집합 강제 + "원문에 없으면 비워라" 스키마 강제. **어휘는 4-3의 modifier 사전과 같은 traits 집합** — 스타일 쿼리가 "주장"으로 끌어온 후보를 같은 어휘로 "사실" 검증하는 대칭이 여기서 완성된다
- [ ] 9-7. `rankScore` — `conceptScore × CONCEPT_WEIGHT`를 3층 감점 위에 얹는다. **모든 보조 신호는 감점이지 하드 드롭이 아니다**
- [ ] 9-8. **조건부 확장** — `mood` 키워드가 있으면 ATTRACTION 슬롯도 대상에 포함, 없으면 MEAL/CAFE만
- [ ] 9-9. 데드라인 임박 시 스킵 (traits 없이 진행) + LLM 실패 시 traits 비우고 진행
- [ ] 9-10. `ai.profile.traits{count}` 메트릭 + 4층 on/off 비교

### 10. 카카오·네이버·TourAPI Redis 캐싱

동작 변화 있음(지연 감소).

> 상세 실행 계획은 [STEP-10-caching.md](steps/STEP-10-caching.md) 참고. (미작성)

- [ ] 10-1. V1이 쓰는 캐시 넷 — `kakao:place:{sha1}` 7일 · `kakao:geo:{anchor}` **30일** · `naver:local:{area}:{slot}[:{modifier}]` 7일 · `tour:{~1km 격자}:{contentTypeId}` 7일. **뒤의 둘이 히트율을 지배한다** — 장소 단위가 아니라 권역·격자 단위 키라 사용자 간에 공유된다. 기존 [RedisConfig.java](../../../src/main/java/backend/yourtrip/global/config/RedisConfig.java)와 [RedisCacheErrorHandler.java](../../../src/main/java/backend/yourtrip/global/config/RedisCacheErrorHandler.java)(장애 시 fail-open) 재사용. `naver:blog`는 9단계와 함께
- [ ] 10-2. `ai.place.cache{source=kakao|naver|tour_api, result}` 메트릭 → 캐시 히트율로 쿼터 여유 실측
- [ ] 10-3. **최종 코스는 캐싱하지 않는다** — 같은 조건으로 재생성했는데 똑같은 코스가 나오면 사용자가 버그로 인식한다. 캐싱 대상은 장소 조회 결과(정적)와 Planner 출력(도시+일수+키워드 → 권역 배분은 결정적)뿐이다

### 11. 실측 결과 기록

- [ ] 11-1. 3점 비교 결과를 [멀티 에이전트 파이프라인 설계](멀티-에이전트-파이프라인.md)와 [AI-HALLUCINATION-GEMINI.md](hallucination/AI-HALLUCINATION-GEMINI.md)에 추가
- [ ] 11-2. 지연 예산 실측치와 설계 추정치(p50 9~12초 — 세마포어 2면 12~18초 / p95 17~24초) 대조 → **202 Accepted 전환 여부를 데이터로 판단**
- [ ] 11-3. 커넥션 점유 시간 before/after, 실제 토큰 비용, `mood` 키워드 포함 비율 기록

## 성공 기준

**1차 지표는 환각률이다.** 다만 이번에 LLM 벤더가 Gemini에서 OpenAI로 바뀌므로, before/after를 그대로 비교하면 **"모델 교체"와 "파이프라인 도입" 두 변수가 섞여** 개선폭을 어느 쪽에도 귀속시킬 수 없다. 그래서 측정점을 3개로 나눈다.

| 측정점 | 시점 | 분리되는 변수 | 값 |
|---|---|---|---|
| Gemini 단일 호출 | 완료 | — | **25.6%** (자동 프록시 19.8% + 세탁 5.7%) |
| **OpenAI 단일 호출** | 2단계 직후 | 모델 교체 효과 | **7.5%** (자동 프록시 6.4% + 세탁 1.08%) |
| OpenAI 파이프라인 | 8단계 직후 | 파이프라인 구조 효과 | 미측정 |

**모델 교체만으로 25.6% → 7.5%, −18.1%p(71% 감소).** `UNVERIFIABLE`을 전부 환각으로 보는 상한도 9.3%로 before의 절반에 못 미친다. 모델 선택의 대가는 크다 — 같은 조건에서 `gpt-5-nano`는 **89.4%**(진짜 환각률 41.7%)로 Gemini보다도 나쁘다. 상세는 [AI-HALLUCINATION-OPENAI.md](hallucination/AI-HALLUCINATION-OPENAI.md)

**2-6은 4조합 120요청으로 쟀다**(`BASELINE_MODEL` × `BASELINE_SCHEMA_MODE`). 이 표가 Curator 모델을 확정한 근거다.

| 조합 | 자동 프록시 환각률 | JSON 실패(전체 요청) | 응답 크기 |
|---|---|---|---|
| **luna / 프롬프트지시** | **6.4%** | 0.0% | 1,503B |
| luna / json_schema | 7.8% | 0.0% | 786B |
| nano / 프롬프트지시 | 47.8% | 3.3% | 1,553B |
| nano / json_schema | 50.7% | 0.0% | 798B |

**측정이 전제 두 개를 뒤집었다.** ① `gpt-5-nano`는 환각률이 **7배 이상**이라 비용을 아끼려고 Curator를 내리면 1차 목표를 정면으로 훼손한다. ② **구조화 출력은 환각률을 낮추지 않았고 낮출 수도 없다** — 스키마는 형식을 강제하지 내용을 강제하지 않는다. JSON 실패를 없앤 것은 모델 교체이고, 구조화 출력의 실익은 **출력 바이트 −48%**와 **스키마 밖 필드 차단**이었다.

해석의 상세(밴드별 세탁 분석, 자동 프록시가 과대평가인 이유, 온도·추론 강도가 "모델 교체" 축에 딸려 들어가는 문제)는 [AI-HALLUCINATION-OPENAI.md](hallucination/AI-HALLUCINATION-OPENAI.md)와 [STEP-2](steps/STEP-2-llm-port.md) 판정 7~9에 있다.
- 하네스는 기존 것을 그대로 쓴다: `src/test/java/backend/yourtrip/global/benchmark/AiHallucinationBaselineTest.java`
  ```bash
  ./gradlew benchmarkTest --tests '*AiHallucinationBaselineTest*' --rerun
  ```
- **동일 입력 세트(지역 10곳 × 스타일 3조합 = 30요청, 여행 일수 3일 고정)와 동일한 `score()` 로직을 유지해야** 세 측정점이 비교 가능하다. 1-2에서 매칭 로직을 바꾸므로, 하네스의 판정 로직은 변경 전 기준을 그대로 쓰도록 고정한다
- **환각률의 산출 정의를 고정한다** — 아래 절차를 그대로 따라야 세 측정점이 같은 것을 재게 된다. 근거와 한계는 [AI-HALLUCINATION-GEMINI.md](hallucination/AI-HALLUCINATION-GEMINI.md)의 "25.6%의 정의"에 있다

  ```
  환각률 = 자동 프록시(매칭 실패율) + 세탁된 환각률
    자동 프록시   = (NO_RESULT + S0 + S1_4) / 전체 장소 수
    세탁된 환각률 = Σ_전체밴드 (밴드별 LAUNDERED 비율 × 밴드별 전체 비중)
  ```

  전제: ① 밴드 경계는 1-2 변경 전 `score()` 기준(`-1`/`0`/`1~4`/`5~7`/`8~10`) ② `UNVERIFIABLE`은 정답으로 간주 ③ `WRONG_MATCH`는 환각에 포함하지 않는다 ④ 수동 검증은 밴드별 층화 추출(밴드당 최대 10건, 시드 42)

  > **[정정]** 이 정의는 **매칭에 실패한 장소를 전부 환각으로 취급**하며, `NO_RESULT`를 두 번 세는 구성이다. "LLM이 실제로 지어낸 이름"만 직접 추정하면 **5.7%**(범위 4~10%)로 훨씬 낮다. 그럼에도 **25.6%를 유지**하기로 했다 — 값을 고치면 세 측정점을 모두 같은 정의로 다시 계산해야 하는데, 비교 가능성이 정확성보다 이 지표의 목적에 부합하기 때문이다. 상세는 [BASELINE-ARTIFACT-ANALYSIS.md](hallucination/BASELINE-ARTIFACT-ANALYSIS.md) 판정 4
- BASELINE 문서가 명시한 한계를 그대로 승계한다 — **LLM 응답은 비결정적이라 수 %p 차이는 반복 측정이 필요하고**, 카카오에 미등록된 실존 업소는 원리적으로 환각과 구분할 수 없다

**부가 지표**

| 지표 | before | 목표 |
|---|---|---|
| HikariCP 커넥션 점유(최악) | ~360초 | ~50ms |
| `ai.grounding.match{name_mismatch\|no_result}` | 미계측 | 5단계부터 상시 관측 |
| JSON 파싱 실패율 | ~~28.6%~~ → **16.7%** | 구조화 출력 + 절단 방지 → **2-6에서 0.0% 관측**(luna/프롬프트지시) |

> **[정정]** 28.6%는 호출이 14건만 성공한 초기 배치의 값(4/14)이었다. 전체 30요청 기준은 **16.7%(5/30)** 다. 또 실패 5건 전부가 `Unexpected end-of-input`(응답 절단)이라 **구조화 출력만으로는 near-zero가 되지 않는다** — 2단계에서 출력 토큰 여유와 종료 사유 확인이 함께 필요하다. 근거는 [BASELINE-ARTIFACT-ANALYSIS.md](hallucination/BASELINE-ARTIFACT-ANALYSIS.md) 판정 3. 재측정 시 **분모(전체 요청 vs 호출 성공분)를 반드시 명시한다.**

## 범위에서 제외한 것

- **`CriticAgent` / `CandidateRefiner`** — 설계는 CriticAgent 설계/CandidateRefiner 설계에 남긴다. 제외 근거는 설계 문서의 지연 예산에 정리돼 있고, 재검토 조건은 두 가지다: ① 골든 데이터셋/LLM-as-judge 평가 인프라가 생겨 "Critic이 실제로 개선하는가"를 측정할 수 있을 때 ② 실제 사용자 피드백에서 컨셉 미스매치 불만이 반복될 때
- **골든 데이터셋 / LLM-as-judge 평가 인프라** — 파이프라인이 안정된 뒤 착수하는 것이 맞다
- **202 Accepted + 폴링 전환** — 동기 API 계약을 유지한 채 먼저 완성해 실측하고, p95가 목표를 넘는 것을 데이터로 확인한 뒤 전환한다. 그래야 전환이 "숫자에 근거한 결정"이 된다
- **사용자 피드백 루프** — 생성된 코스에서 사용자가 삭제한 장소가 곧 정답 라벨이고, 이건 외부 API가 아니라 우리가 축적하는 고유 자산이다. **다만 소급할 수 없는 데이터라 삭제 이벤트 기록은 일찍 시작할 가치가 있다** — 별도의 작은 작업으로 분리한다
- **TourAPI 축제·행사(`contentTypeId=15`)** — `searchFestival2(eventStartDate, eventEndDate)`에 여행 기간을 넣으면 **그 날짜에 실제로 열리는 축제**가 나온다. 다른 어느 소스도 못 하는 일이고 코스당 1회·순수 추가·fail-open이라 비용도 작지만 V1에서 뺀다: ① 제대로 살리려면 Planner가 축제 유무를 알고 슬롯 구성을 바꿔야 해서 **Planner 계약 변경**이다 ② 관광지 후보(12·14·28)가 채택되는지부터 삭제 로그로 보고 나서 붙이는 것이 순서다. **재검토 조건**: 8단계 이후 삭제 로그에서 TourAPI 유래 후보의 채택률이 확인되고, 여행 날짜에 축제가 겹치는 요청 비율이 유의미할 때
- **TourAPI 운영계정 전환** — 개발계정으로 끝낸다. 자동승인이라 발급 대기가 없고, 일 1,000건이 그대로 서비스 상한이 된다(요청당 ≤9회 + 격자 캐시라 하루 110코스 이상). 운영계정은 일 100,000건까지 열리지만 **심의승인이라 약 1주일이 걸리고, 승인 조건이 "개발계정 호출 로그 + 서비스 중인 앱·웹 URL"이라 이 저장소의 단계에는 맞지 않는다.** 트래픽이 실제로 상한에 닿는 것이 관측되면 그때 신청한다 — 그때는 이미 호출 로그가 쌓여 있어 조건도 충족된다
- **Gemini 어댑터 유지** — OpenAI 확정으로 불필요. 2단계 baseline 재측정이 끝나면 8단계에서 삭제한다
- **Spring Boot 4 마이그레이션** — Spring Boot 3.5가 2026-06-30 오픈소스 EOL에 도달했으므로 언젠가는 해야 하지만 별도 작업으로 분리한다. 코드 수정량 자체는 크지 않으나(Jackson 3 전환 11개 파일, `@MockBean`→`@MockitoBean` 3곳, springdoc 3.1.0, `springboot4-dotenv`, Security 7), **깨지는 곳 대부분이 런타임에만 드러나는 영역**(캐시 직렬화, JWT 필터, Security 체인, Swagger)인데 이 레포의 통합 테스트는 E2E 1개뿐이라 검증 비용이 크다. 상세 근거는 [STEP-0-prerequisites.md](steps/STEP-0-prerequisites.md) 참고
- **`SEQUENCE` 전환** — `Place`/`DaySchedule`이 `GenerationType.IDENTITY`라 JDBC 배치 INSERT가 원천 불가능하지만, ~20건 규모라 지금은 무시해도 된다

## 미해결 · 확인 필요

- **OpenAI RPM/TPM 티어** — `llm.max-concurrent-calls` 초기값 2의 근거. **2-6에서 1차 확인: 120요청 중 429 0건.** 다만 요청 간 5초 지연·동시 호출 1이라 조건이 느슨해 초기값의 근거로 삼기엔 부족하다 — **5단계 병렬화 이후 실제 동시 호출 조건에서 재실측이 필요하다**(5-1 참고). 2단계에서 확인된 사실 하나: Spring AI는 429를 재시도 대상으로 보지 않으므로(위 2-4 정정) 티어에 걸려도 자동 복구되지 않는다 — 우리 분류가 그걸 막고 있다
- **`max_completion_tokens` 대 `max_tokens`** — 추론 계열 모델은 추론 토큰이 출력에 포함되므로 전자를 쓴다. 실제로 gpt-5 계열이 `max_tokens`를 거부하는지는 2-6 실호출에서 확인된다(WireMock은 필드가 실려 나가는 것까지만 검증한다)
- ~~**NAVER API HUB의 지역검색 경로와 응답 스키마**~~ → **4-2에서 확정** ([STEP-4](steps/STEP-4-candidate-sources.md) 판정 2~5). **블로그 검색은 이 키에서 미활성화(401)라 9단계 착수 전 NCP 콘솔에서 켜야 한다**
- **TourAPI 실호출 확정** — 오퍼레이션명·`arrange` 거리순·좌표 형식·분류체계·상권형 명소 등록 여부. 4-7 착수 시. **키 발급은 자동승인이라 대기 항목이 아니다**(신청 즉시 + 동기화 10~30분)
- **`placeUrl == null`에 대한 FE 동작** — `PlaceUrlEnricher`가 이름 불일치·좌표 불일치 시 URL을 비운다. Swagger 명세는 nullable이지만 FE가 실제로 "링크 없음"으로 처리하는지 8단계 전 확인
- **`duration` 키워드 처리 방침** — 6-5에서 결정
- **`mood` 키워드 포함 비율** — 9-3 조건부 확장이 실제로 얼마나 자주 켜지는지(= 토큰 비용 증가폭)는 추정이 아니라 배포 후 실측이 필요하다

## 참고 문서

- [멀티 에이전트 파이프라인 설계](멀티-에이전트-파이프라인.md) — **이 로드맵의 근거 문서(허브).** 배경·설계 원칙·전체 구조·도입 순서 + 절별 문서 지도. 상세는 아래로 나뉜다
  - [지식 신호 층과 후보 공급](design/지식-신호와-후보-공급.md) — 지식 신호 층, 후보 공급(`CandidateRetrievalStage`)
  - [결정론적 단계](design/결정론적-단계.md) — `SlotType`, `RouteOptimizer`
  - [LLM 연동](design/LLM-연동.md) — 벤더 중립 LLM 포트, 프롬프트 전략
  - [운영 관심사](design/운영-관심사.md) — 트랜잭션 경계, 부분 실패 전략, 지연 예산, 비용, 관측
  - [기각한 대안](decisions/기각한-대안.md) — 기각한 대안
  - [보류와 미해결 과제](decisions/보류와-미해결-과제.md) — CriticAgent 설계 Critic, CandidateRefiner 설계 Refiner, 남는 한계
- [AI-HALLUCINATION-GEMINI.md](hallucination/AI-HALLUCINATION-GEMINI.md) — 환각률 baseline 실측 (before 값 25.6%)
- [AI-HALLUCINATION-OPENAI.md](hallucination/AI-HALLUCINATION-OPENAI.md) — **OpenAI 재측정 (중간 측정점 7.5%)**. luna/nano 비교로 Curator 모델을 확정한 근거
- [BASELINE-ARTIFACT-ANALYSIS.md](hallucination/BASELINE-ARTIFACT-ANALYSIS.md) — 위 측정의 원본 산출물 재분석. **1-2 설계의 근거**(점수 밴드 분포, 밴드×verdict 교차표, 파싱 실패 원인)
- [TASK-PRESIGN-BOTTLENECK.md](../connection-pool-bottleneck/PRESIGN-BOTTLENECK.md) — 커넥션 풀 병목 실측. 목표 4의 근거
- [TASK-PRESIGN-BOTTLENECK-FIX.md](../connection-pool-bottleneck/PRESIGN-BOTTLENECK-FIX.md) — 트랜잭션 경계 분리 선례
- [CACHING-ROADMAP.md](../redis-caching/README.md) — 이 문서가 따르는 로드맵 포맷의 선례
- `steps/STEP-N-*.md` — 단계별 상세 실행 계획서 (각 단계 착수 시점에 작성)
