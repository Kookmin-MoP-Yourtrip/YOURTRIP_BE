# AI 코스 생성 멀티 에이전트 파이프라인 로드맵

> [멀티 에이전트 파이프라인 설계](멀티-에이전트-파이프라인.md)에서 AI 코스 생성(`POST /api/my-courses/ai`)을 단일 LLM 호출에서 멀티 에이전트 파이프라인으로 재설계하기로 했다. 이 문서는 그 설계를 **어떤 순서로, 무엇을 만들고, 무엇을 확인해야 다음으로 넘어가는지**로 옮긴 실행 로드맵이다.
>
> 설계 문서의 도입 순서에 11단계 "도입 순서" 표가 있지만 각 단계가 한 줄 요약이라 착수/완료 판정 기준이 없다. 이 로드맵은 그 표를 승계하되 **0단계(사전 준비)를 앞에 추가**하고, 각 단계를 체크 가능한 항목으로 분해한다.
>
> V1 범위는 **Planner · CandidateRetrieval(네이버 시더 + TourAPI) · Curator · Grounding · RouteOptimizer(+PlaceUrlEnricher)** 다. `CriticAgent`·`CandidateRefiner`는 설계 문서의 CriticAgent 설계/CandidateRefiner 설계에 설계만 남기고 제외했고(근거는 설계 문서의 지연 예산), **`PlaceSignalStage`(3층 인기도 + 4층 속성 추출)도 같은 논리로 V1에서 빠져 9단계 조건부가 됐다**(근거는 설계 문서의 "PlaceSignal을 V1에서 제외한 이유"). 초안의 "다섯 단계"에서 PlaceSignal이 빠지고 CandidateRetrieval이 들어온 셈이다.
>
> **[개정] 3단계 완료 후 Curator 앞에 `CandidateRetrievalStage`(후보 공급 층)가 추가됐다.** Curator가 파라메트릭 지식만으로 상호명을 회상하면 모델이 모르는 지방 도시에서 재현율이 무너지므로, 네이버 지역검색 인기순 시드 + 카카오 키워드 검색으로 실존 후보 목록을 먼저 만들고 Curator는 그 목록에서 **선별**한다. 새 단계 번호를 매기지 않고 4단계·5단계(`CandidateRetrievalStage`, 5-8·5-9)·6단계(Curator 계약 변경, 6-4·6-7)에 항목으로 붙였다. 근거는 설계 문서의 후보 공급·기각한 대안.
>
> **[개정] 곧이어 `PlaceSignalStage`(3층 블로그 인기도 + 4층 LLM 속성 추출)를 V1에서 뺐다.** 후보 공급 층이 인기도를 시딩으로 앞에서, 스타일을 modifier 쿼리로 앞에서 반영하면서 같은 3개 후보를 사후에 다시 정렬하는 층의 존재 전제("컨셉을 판별할 유일한 외부 근거")가 사라졌고, 효과는 미측정인데 비용(LLM 1회·2~4초·네이버 35회)은 확정적이었다. 그 결과 **4단계는 `NaverBlogClient` 중심에서 `NaverLocalClient` + 후보 공급 순수 함수로 다시 채워졌고**(착수 전이라 번호를 새로 매김), 옛 3·4층 항목은 **9단계 조건부**로 이동했으며, SEEDED가 네이버 좌표를 승계하면서 GroundingStage는 SUGGESTED만 호출하고 최종 장소의 카카오 URL은 **`PlaceUrlEnricher`(5-10)**가 따로 채운다. 근거는 설계 문서의 "PlaceSignal을 V1에서 제외한 이유".
>
> **[개정] 카카오 키워드 검색을 후보 공급 소스에서 뺐다.** 스타일 modifier 쿼리로 네이버 시더가 슬롯당 8~15건을 확보하자 카카오 "커버리지"(`LISTED`)의 역할이 잉여가 됐고, `accuracy`/`distance` 정렬은 처음부터 품질을 담지 않는 임의 슬라이스였다. 풀은 **네이버 시더(`SEEDED`) + 파라메트릭(`SUGGESTED`)** 둘이고, 카카오는 **`SUGGESTED` 실존 검증 · 배치 장소 URL 보강** 전담이다. 5-8·5-9·4-5·6-7이 그에 맞춰 바뀌었다. 근거는 설계 문서의 후보 공급 "카카오 커버리지 검색을 후보 소스에서 뺀 이유".
>
> **[개정] ATTRACTION 계열 슬롯(ATTRACTION·VIEWPOINT·WALK·ACTIVITY)의 커버리지·분류 소스로 한국관광공사 TourAPI를 채택하고(`LISTED` 재도입), 네이버 시더를 전 슬롯으로 넓혔다.** 관광지는 상업 POI와 문제의 모양이 다르다 — 밀집·임의 슬라이스가 아니라 커버리지·분류가 공백이고, 그 둘이 TourAPI의 강점이다. TourAPI에 없는 인기도는 시더가 맡는다. TourAPI는 텍스트 지역명을 못 받으므로 **Planner 응답에 `anchor`(권역 안 랜드마크 1개)를 추가**해 카카오 지오코딩 1회로 좌표를 얻고, 그 좌표 기준 거리순으로 조회한다(시군구 코드표·이름 매칭 없음). 4-7~4-9, 5-8, 6-2·6-4가 그에 맞춰 바뀌었다. 근거는 설계 문서의 후보 공급 "ATTRACTION 계열 슬롯 — TourAPI 커버리지".
>
> **LLM 벤더는 OpenAI로 확정됐다.** 설계 문서 초안은 Gemini를 현행으로 두고 OpenAI 전환 "가능성"을 전제로 쓰였으나, 확정에 따라 설계 문서 본문(LLM 포트 설계·프롬프트 전략·비용 분석·도입 순서·남는 한계)이 갱신됐다. 이 로드맵은 그 갱신된 설계를 기준으로 한다.
>
> 진행 상황: **3단계 완료**(3-1 ~ 3-6, 테스트 234개 통과 + 앱 기동 확인 + 완전탐색 벤치마크 n=6~9 실측). 2단계에서 복합 환각률 25.6% → **7.5%**(luna, 수동 검증 82건)를 확인했고 Curator 모델은 `gpt-5.6-luna`로 확정됐다. 다음은 4단계(`NaverLocalClient` + 후보 공급 순수 함수). 0단계 검증 항목은 전부 통과했고 OpenAI·네이버 키도 발급됐다.
>
> 1단계에서 **로드맵 1-2의 처방이 데이터와 반대라는 것이 드러나 방향을 바꿨다.** 아래 1-2 항목의 정정과 [BASELINE-ARTIFACT-ANALYSIS.md](hallucination/BASELINE-ARTIFACT-ANALYSIS.md) 참고.

## 목표

1. **JSON 파싱 실패로 요청이 통째로 실패하는 것을 없앤다.** 단일 호출 구조의 실측 파싱 실패율은 **28.6%** — 사용자가 AI 코스 생성을 4번 시도하면 1번 이상 503을 받는다는 뜻이다. 스키마를 프롬프트 텍스트가 아니라 **디코딩 레벨에서 강제**하면(구조화 출력) 이 실패 자체가 사라진다. 현재 프롬프트의 JSON 예시에 trailing comma가 들어 있는 것이 유력한 원인이라, 프롬프트에서 예시를 걷어내는 것만으로도 상당 부분 해소될 가능성이 높다.

2. **환각 장소가 사용자 코스에 실리는 비율을 낮춘다.** 현재 실측 환각률은 **25.6%**([AI-HALLUCINATION-GEMINI.md](hallucination/AI-HALLUCINATION-GEMINI.md)) — 코스 하나를 받으면 평균 4곳 중 1곳이 존재하지 않는 장소다. 후보를 3배로 늘리고 카카오 매칭 점수에 하한선을 두어, 검증을 통과하지 못한 장소는 파이프라인에 아예 존재하지 않게 만든다.

3. **동선·시간 배치를 LLM 추측에서 실좌표 계산으로 옮긴다.** 좌표를 확보한 뒤 완전탐색으로 최적 순열을 고르므로, "시간 겹침 없음"·"day당 식사 1회"·"동선 역주행 없음"이 프롬프트 규칙이 아니라 **알고리즘 불변식**이 된다.

4. **AI 코스 생성이 다른 API를 죽이지 않게 한다.** 현재 `createAICourse`는 `@Transactional` 하나로 LLM 호출과 카카오 블로킹 호출 N회를 전부 감싸, 최악의 경우 HikariCP 커넥션 1개를 **360초** 점유한다. 이 저장소는 동시성 200에서 이미 커넥션 풀 병목을 실측한 이력이 있다([TASK-PRESIGN-BOTTLENECK.md](../connection-pool-bottleneck/PRESIGN-BOTTLENECK.md)).

5. **LLM 벤더를 코드에서 분리한다.** 에이전트 코드가 벤더 SDK 타입을 한 개도 import하지 않게 해, 벤더 교체가 어댑터 하나의 교체가 되도록 한다. 부수 효과로 에이전트 단위 테스트가 가능해진다(`com.google.genai.Client`가 `public final class`라 현재는 Mockito로 묶을 수 없다).

## 배경 — 현재 구조의 문제

**① 환각을 걸러내는 게 아니라 세탁하고 있다.**
[KakaoLocalClient.java](../../../src/main/java/backend/yourtrip/global/kakao/KakaoLocalClient.java)의 `score()`는 이름 일치 +5 / 주소 일치 +3 / 카테고리 +2로 최대 10점을 매기지만 **하한선이 없다.** `max()`로 최고점을 뽑으므로 0점 후보도 그대로 반환된다. LLM이 지어낸 상호명으로 검색하면 카카오가 그 지역의 무관한 POI를 돌려주고, 그게 사용자 코스에 저장된다. BASELINE 측정이 이 경로를 `LAUNDERED`(진짜 환각)로 분류했다.

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
| 모델 배치 | Gemini 기준 `thinking-budget` | **Planner·Curator = `gpt-5.6-luna`, PlaceProfile = `gpt-5-nano`** (약 $0.0030/요청). **2-6 실측으로 Curator=luna 확정** — nano는 환각률이 7배라 후보에서 탈락했다. `thinking-budget`의 OpenAI 대응물은 `reasoning-effort`로 확인돼 agent별 설정에 들어갔다 |
| 네이버 API 키 | "착수 전 확인 필요"(남는 한계) | **미보유.** 0단계에서 발급 — 4단계의 블로커 |
| before/after 비교 | 환각률 25.6%(Gemini 단일 호출) | **OpenAI 단일 호출 baseline을 2단계에서 재측정**해 3점 비교 |
| V1 범위 | Critic·Refiner 제외(지연 예산) | 유지. 도입 순서의 11단계 전부가 이 로드맵의 범위 |

**OpenAI 확정의 근거와 파급.** 설계 문서가 벤더 중립 포트를 정당화한 두 축(향후 전환 대비 / 테스트 가능성) 중 첫 번째는 이제 "이미 일어난 전환"이 됐고, 두 번째는 그대로 남는다. 즉 `LlmClient` 포트는 여전히 필요하지만 **어댑터는 OpenAI 하나만 만든다.** Gemini 어댑터를 만들어 A/B를 유지하는 선택지는 유지 비용 대비 얻는 게 없어 채택하지 않는다 — 2단계 baseline 재측정이 끝나면 Gemini 경로는 8단계에서 삭제된다.

**Spring AI를 고르는 근거.** OpenAI의 `response_format: json_schema`는 업계 표준에 가깝게 정착돼 있어 프레임워크 지원이 안정적일 가능성이 높고, 이 저장소는 이미 `@Bean`·`application.yml` 기반 Spring 관용구가 전역에 깔려 있다. 다만 **포트를 없애고 Spring AI의 `ChatClient`를 에이전트가 직접 쓰지는 않는다** — 오케스트레이션은 어떤 프레임워크를 쓰든 직접 짜야 하는 도메인 로직이고, 툴 자율 호출·`VectorStore`·대화 메모리는 이 파이프라인에 대응물이 없다. Spring AI는 `OpenAiLlmClient` 내부의 전송 계층으로만 가둔다.

**에이전트별 모델 차등의 근거.** ~~현재 코드의 단일 `temperature 0.3`은~~

> **[정정]** 아래 온도 차등 계획은 **실행할 수 없다.** 2-6 실호출에서 `gpt-5.6-luna`·`gpt-5-nano` 모두 커스텀 `temperature`를 400으로 거부하는 것이 확인됐다(`"Only the default (1) value is supported"`). Curator 쪽 의도(높은 온도)는 기본값 1이 이미 높아 우연히 충족되지만, **PlaceProfile 쪽(낮은 온도로 충실성 확보)은 그대로 손해**라 9단계에서 닫힌 태그 집합과 스키마 강제로 보완해야 한다. 대신 **`reasoning-effort`가 실질적인 차등 수단**이 됐다 — 안 낮추면 nano는 출력 예산을 추론에 다 쓰고 본문을 0바이트로 돌려준다. 상세는 [STEP-2-llm-port.md](steps/STEP-2-llm-port.md) 판정 6

원래 근거는 다음과 같았다. 현재 코드의 단일 `temperature 0.3`은 "장소 선정은 다양해야 하고 판정은 일관돼야 한다"는 상충 요구를 하나로 뭉갠 값이다. Curator는 후보 3개가 서로 비슷하면 대체재로서 의미가 없으므로 온도를 올리고, PlaceProfile은 속성 추출이라 창의성이 아니라 충실성이 필요하므로 낮춘다. 모델도 같은 논리로 나눈다 — Planner(컨셉·권역 설계)만 추론 이득이 있고, Curator(지역 상식 회상)·PlaceProfile(속성 추출)은 이득이 적으면서 토큰 비중은 가장 크다. 구체 모델 ID와 단가는 0단계에서 확정한다.

## 문서 작성 원칙

- **이 문서에는 체크리스트만 남긴다.** 설계 논의, 발견한 버그, 성능 측정 결과 같은 상세 내용은 단계별 실행 계획서 `steps/STEP-N-*.md`에 적는다.
- **실행 계획서는 해당 단계에 착수하는 시점에 작성한다.** 미리 전부 쓰지 않는다 — 앞 단계의 결과가 뒤 단계의 설계를 바꾸기 때문이다(특히 0단계 Spring AI 검증 결과가 2단계를 좌우한다). 각 `### N.` 섹션에 걸린 `steps/` 링크는 **아직 파일이 없는 상태가 정상**이다.
- 계획이 바뀌면 지우지 않고 `> **[정정]**` 인용블록과 `~~취소선~~`으로 소급 기록한다. 이 저장소의 다른 task 문서와 같은 방식이다.
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

동작 변화 없음. 이후 모든 단계의 선행 조건이며, **여기서 나온 판정이 2·4단계의 설계를 바꾼다.**

> 검증 방법·판정 결과·근거는 [STEP-0-prerequisites.md](steps/STEP-0-prerequisites.md) 참고.

- [x] 0-1. OpenAI API 키 발급 + 크레딧 충전 완료. 충전 전에는 401이 아닌 `429 insufficient_quota`가 반환돼 키 자체는 처음부터 유효했음이 확인됐다
- [x] 0-2. **NCP 콘솔의 NAVER API HUB**에서 블로그(NAVER Search Blog API) 선택 후 키 발급 완료. 검색 API가 developers.naver.com에서 네이버 클라우드 플랫폼으로 옮겨가 발급처·엔드포인트·인증 헤더가 바뀌었다(요금 정책은 무료 그대로). **한도 초과 시 429 반환** — 4단계 fail-open 분기의 기준
- [x] 0-3. **Spring AI 구조화 출력 검증 — 전제 성립.** 스키마는 `messages[].content`에 섞이지 않고 전부 `response_format.json_schema`에 `strict: true`로 전송된다(WireMock으로 요청 본문 확인). 공식 SDK 폴백 불필요. 실 API 검증(0-3b)도 통과 — **`gpt-5.6-luna`·`gpt-5-nano` 모두 strict json_schema 지원**, 그리고 **최상위 배열 스키마는 400으로 거부**되는 것을 확인했다(6단계 Curator 스키마 설계의 제약)
- [x] 0-4. 모델 배치 확정 — **Planner·Curator = `gpt-5.6-luna`, PlaceProfile = `gpt-5-nano`** (약 $0.0030/요청). 부수적으로 비용 분석의 "비용은 전부 4층에서 나온다"는 전제가 금액 기준으로는 틀렸다는 것이 드러나 재계산했다
- [x] 0-5. 쿼터·과금 확인 — 네이버 검색 **0원 / 일 25,000건**(요청당 35회 → **약 714요청**), 카카오 100,000/일(요청당 45회 → 약 2,222요청). **네이버가 먼저 한계에 닿지만 fail-open이라 서비스가 죽지는 않는다.** 이관 후에도 무료라 설계 문서의 비용 분석의 "3층은 비용 증가가 0" 전제는 유효하다. OpenAI RPM/TPM은 키 발급 후 확인
  > **[갱신]** 위 계산은 PlaceSignal(블로그 35회) 기준이다. PlaceSignal이 V1에서 빠지고 네이버 용처가 지역검색 ~18~30회(전 슬롯 시더)로 바뀌면서 **상한이 약 830~1,400요청으로 넓어졌다.** 카카오도 45회 → ~20~33회. 0-2의 블로그 API 키는 그대로 유효하며(같은 API HUB 키로 지역검색도 호출) 9단계에서 쓴다. **TourAPI**는 개발계정 일 1,000건이라 요청당 ≤9회(격자 캐시)로 초기엔 버티지만 운영 전 증량 승인이 필요하다(4-7)
- [x] 0-6. **테스트 인프라 신설** — `src/test/resources` + `application-test.yml`, `wiremock-standalone` 추가. 셰이딩판을 고른 이유는 이 레포의 테스트 클래스패스에 Jackson·Guava가 이미 여러 버전으로 경합 중이기 때문이다(추가 후 해석 결과 변화 없음을 확인)
- [x] 0-7. `build.gradle`에 Java 21 toolchain 고정 — 바이트코드 major version 65 확인

### 1. 기존 결함 수정

동작 변화 **있음(버그 수정)**. 파이프라인과 **완전히 독립적으로 옳은 수정**이라 리뷰가 쉽고, 작업이 중단돼도 가치가 남는다. 그래서 맨 앞에 둔다.

> 상세 실행 기록은 [STEP-1-existing-defects.md](steps/STEP-1-existing-defects.md) 참고.

- [x] 1-1. `Place`의 `@Builder` 파라미터를 `double` → `Double`로 교체 (좌표 `0.0/0.0` 저장 차단) + `PlaceMapper.toCopyEntity`의 언박싱 NPE 수정. **응답 DTO 3종과 `PlaceCacheItem`도 함께 승격해야 했다** — 게터를 읽는 쪽이 전부 새 언박싱 NPE 후보가 되기 때문이다. 응답의 좌표가 nullable이 되므로 **API 계약 변경이고 FE 공유가 필요하다**
- [x] 1-2. ~~`KakaoLocalClient.score()`에 점수 하한선 도입~~ → **이름 정규화 + 이름 일치 필수 게이트**

  > **[정정]** 산출물을 집계해보니 **총점 하한선은 역효과**였다. `S1_4`(3점)는 표본 18건이 전부 정답인데 `S5_7`(5·7점)은 31%가 불량이라, `≥5` 하한선은 정답 밴드를 버리고 불량 밴드를 남긴다. 원인은 검색 키워드가 "지역명 + 장소명"이라 주소(+3)·카테고리(+2) 가점이 거의 자동으로 붙어 **이름이 하나도 안 맞아도 5점이 나오는** 구조다. 그래서 하한선이 아니라 이름 일치를 별도 조건으로 두고, `contains`의 거짓 음성(띄어쓰기·중점)은 정규화로 없앴다. **`score()`는 건드리지 않았다** — 하네스가 리플렉션으로 직접 호출하므로 재측정 비교 가능성이 깨진다. 근거는 [BASELINE-ARTIFACT-ANALYSIS.md](hallucination/BASELINE-ARTIFACT-ANALYSIS.md) 판정 1·2
- [x] 1-3. `KakaoConfig`의 `WebClient`에 connect/response 타임아웃 + 커넥션 풀 명시, `.block(Duration.ofSeconds(20))` 제거. 현재는 타임아웃 초과 시 `IllegalStateException`이 던져져 `WebClientResponseException` catch를 빠져나가 원시 500이 된다 → **catch를 `WebClientException`으로 확장하는 것이 한 세트여야 한다.** 호출당 최악 지연 20초 → 5초
- [x] 1-4. `buildKeywordsJson(null)` NPE 수정 + `AICourseCreateRequest.keywords`에 검증 추가(`@NotEmpty`)
- [x] 1-5. `createAICourse`의 `@Transactional` 경계 분리 — 외부 I/O를 트랜잭션 밖으로 빼고 저장만 짧은 트랜잭션으로. **`AiCoursePersister`를 반드시 별도 빈으로 둔다**(같은 클래스 내부 호출은 Spring AOP 프록시를 우회해 트랜잭션이 아예 안 걸린다). 이 저장소에 `MyCourseDetailReader`라는 동일한 분리 선례가 있다. **걸림돌은 어노테이션이 아니라 더티체킹 의존이었다** — `ResolvedPlace`/`ResolvedDay` 중간 표현으로 "결과를 다 모은 뒤 저장" 순서로 뒤집었다
- [x] 1-6. 회귀 테스트 (56 → 73개). ~~커넥션 점유 시간 before/after 확인~~ → **8단계 E2E로 미룬다** — AI 코스 생성은 요청마다 LLM을 호출해 부하 테스트가 부적합하고, 스텁으로 대체하면 측정의 의미가 옅어진다
- [x] 1-7. **E2E 검증 완료(로컬)** — 순천 3일 코스 생성 201/14.4초, 장소 12개 중 **`0.0/0.0` 0건**, 매칭 실패 2건은 좌표 `null`로 저장·응답. `keywords` 생략·빈 배열 모두 400. 요청 중 ERROR 0건. 정규화가 실제로 구제한 사례 1건 관측(`"순천 문화의 거리"` → `"순천문화의거리"`)

### 2. `LlmClient` 벤더 중립 추상화 + 설정 외부화 + baseline 재측정

동작 변화 없음(기존 `GeminiService` 경로는 그대로 둔다).

> 상세 실행 기록은 [STEP-2-llm-port.md](steps/STEP-2-llm-port.md) 참고.

- [x] 2-1. `LlmClient` 포트 + `LlmCall` record 정의. `responseJsonSchema`는 벤더 타입이 아니라 **JSON 문자열**로 받는다 — 이게 벤더 중립의 핵심이다. ~~스키마는 `resources/schemas/*.json`~~ → **프로덕션 스키마 디렉터리는 6단계에 만든다**(실제 에이전트 스키마가 그때 생긴다). 2-6 측정용 스키마만 `src/test/resources/schemas/`에 뒀다. 부수적으로 `responseJsonSchema`를 **nullable로 확장**했다 — 2-6이 "구조화 출력을 끈" 측정점을 필요로 하고, 스키마 강제를 포트의 요구사항으로 못박으면 오히려 중립성이 좁아진다
- [x] 2-2. `OpenAiLlmClient` 어댑터 구현 — 전송 계층은 Spring AI(0-3 판정대로 공식 SDK 폴백 불필요). **auto-config를 쓰지 않고 어댑터가 `OpenAiChatModel`을 직접 조립한다** — 켜면 API 키가 기동 필수가 되어 이 단계가 동작 변화를 만들고, `baseUrl`을 못 바꿔 WireMock 검증이 불가능해진다
- [x] 2-3. `AiLlmProperties` 등 `@ConfigurationProperties` 도입 — agent별 model/temperature, `timeout-ms`, `max-concurrent-calls`, retry 설정. **이 저장소 최초의 `@ConfigurationProperties`다**(현재 전 설정이 `@Value` 필드 주입). 설계 초안에 없던 **`max-output-tokens`를 agent별로 추가**했다(지금은 설계 문서에도 반영돼 있다) — 절단이 파싱 실패의 실제 원인이므로 출력 여유가 설정 대상이어야 한다
- [x] 2-4. `LlmResponseParser` + 재시도 2계층 — 전송 계층(429/5xx 지수 백오프 + 지터)과 의미 계층(200 OK인데 깨진 JSON → 1회만 재시도). 2회 이상은 지연 예산만 태운다

  > **[정정]** 설계 초안의 재시도 표는 의미 재시도를 `LlmResponseParser`의 책임으로 적었지만 **재호출은 파서가 할 수 없다.** 파서는 순수 변환만 맡고, 전송 재시도는 `LlmRetryExecutor`로 떼어냈으며(백오프 계산을 순수 함수로 테스트하기 위해), 의미 재시도는 어댑터가 오케스트레이션한다.
  >
  > **재시도 계층이 셋으로 흩어져 있던 것을 발견해 제거했다** — 설정한 3회가 실제 HTTP 요청 6회로 관측됐다. ① Spring AI `OpenAiChatModel`의 자체 `RetryTemplate` ② **선언조차 되지 않은 전이 의존성** Apache HttpClient 5가 `detect()`에 선택돼 429를 자체적으로 1회 더 시도 ③ 우리 executor. 또 **Spring AI는 429를 `NonTransientAiException`(재시도 무의미)으로 분류**하는데 실제로는 정반대라, 상태 코드를 보존하는 `responseErrorHandler`를 주입해 분류를 직접 소유한다. 근거는 [STEP-2-llm-port.md](steps/STEP-2-llm-port.md) 판정 1·2
- [x] 2-5. 포트 기반 단위 테스트 — 에이전트 코드가 벤더 SDK 타입을 import하지 않는다는 것을 테스트로 확인. 에이전트는 6단계에 생기므로 **소스 import 스캔 + 포트 목킹 데모** 두 가지로 갈음했다. 어댑터가 실제로 벤더 SDK를 쓴다는 것도 함께 단언해 검사기가 헛돌지 않음을 보인다
- [x] 2-6. **OpenAI 단일 호출 baseline 재측정** — 기존 `AiHallucinationBaselineTest` 하네스의 LLM만 교체하고 입력 세트·`score()` 로직은 그대로. **환각률과 JSON 파싱 실패율을 함께 집계하고, 이번에는 결과 산출물을 파일로 남긴다**(Gemini 측정 때 파싱 실패율 28.6%가 수치만 남고 산출물이 남지 않아 재확인이 불가능했다)

  측정 축이 둘로 늘었다 — `BASELINE_MODEL`(luna/nano) × `BASELINE_SCHEMA_MODE`(prompt/json_schema)의 **4조합 120요청, 전량 완료**(약 32분). 요청 결말을 `OK`/`CALL_FAILED`/`TRUNCATED`/`PARSE_FAILED` 넷으로 갈라 **두 분모를 모두 출력**한다.

  | 조합 | 자동 프록시 환각률 | JSON 실패(전체 요청) | 응답 크기 평균 |
  |---|---|---|---|
  | **luna / 프롬프트지시** | **6.4%** | 0.0% | 1,503B |
  | luna / json_schema | 7.8% | 0.0% | 786B |
  | nano / 프롬프트지시 | 47.8% | 3.3% (1/30) | 1,553B |
  | nano / json_schema | 50.7% | 0.0% | 798B |

  > **[중요] 측정이 이 로드맵의 전제 두 개를 뒤집었다.** 상세는 [STEP-2-llm-port.md](steps/STEP-2-llm-port.md) 판정 7·8·9.
  >
  > ① **Curator 모델은 `gpt-5.6-luna`로 확정한다**(0-4가 2-6에 넘긴 숙제). `gpt-5-nano`는 환각률이 **7배 이상**이고 `NO_RESULT` 밴드가 40%를 넘는다 — AI가 부른 이름의 40%가 카카오에서 검색조차 안 된다(`"경주 전통찜닭골목"`, `"부산항대교 남항스카이워크"` 같은 그럴듯한 조어). 비용을 아끼려고 Curator를 nano로 내리면 1차 목표를 정면으로 훼손한다.
  >
  > ② **위 "목표 1"의 인과가 틀렸다.** 구조화 출력은 환각률을 낮추지 않았고(오히려 +1.4~2.9%p, 표본 오차 범위) 낮출 수도 없다 — **스키마는 형식을 강제하지 내용을 강제하지 않는다.** JSON 실패를 해결한 것은 구조화 출력이 아니라 **모델 교체**다(120요청 중 절단 0건). 구조화 출력의 실익은 다른 데 있었다: **출력 바이트 −48%**(pretty-print 제거, 비용 분석이 지목한 Curator 출력 비용에 직결)와 **스키마 밖 필드 차단**(유일한 파싱 실패 1건이 프롬프트 내부 불일치인 `placeLocation`이었고 `additionalProperties: false`가 이를 없앴다).

### 3. `RouteOptimizer` + `SlotType` + `GeoUtils`

동작 변화 없음. 순수 함수라 외부 의존이 없고 단위 테스트가 완전히 결정론적이다.

> 상세 실행 기록은 [STEP-3-route-optimizer.md](steps/STEP-3-route-optimizer.md) 참고.

- [x] 3-1. `SlotType` enum — 체류시간·인기도 가중치·허용 카테고리 코드를 enum이 소유. LLM이 내보내는 필드가 하나 줄면 스키마 위반 가능성도 하나 줄고, 튜닝이 코드 리뷰 대상이 된다
- [x] 3-2. `GeoUtils` — haversine 거리(반경 6371.0088km). 유클리드 근사와의 차이는 한국 도시 규모에서 0.1% 미만이지만, 20줄이고 CPU 비용이 무의미하므로 근사 오차라는 변수를 아예 없앤다. **내부 항을 `[0,1]`로 클램프해야 했다** — 대척점에서 `NaN`이 나오면 예외 없이 최적 순열 선택만 망가진다
- [x] 3-3. `RouteOptimizer` 완전탐색 — `n ≤ 7`이면 `7! = 5,040` 순열이 1ms 미만(**실측 589µs**). 비용 함수는 순수 TSP가 아니라 **거리 + 식사 시간창 위반 + 하루 초과 페널티**

  > **설계 초안이 비워둔 계수 세 개를 정해야 했고, 그 전에 단위부터 맞춰야 했다.** `km × 계수`와 `분 × 계수`는 같은 저울에 올릴 수 없다. `DISTANCE_WEIGHT`를 "km당 분"(4.0 = 60÷15km/h)으로 정의해 세 항을 전부 분으로 환산했다. 식사 2.0 = "30분 늦은 점심 ≈ 15km 우회", 초과 3.0.
  >
  > **식사 윈도우 배정의 첫 구현이 벌점의 목적과 정반대로 동작했다** — 잘 벌어진 아침·점심·저녁(480)이 몰아넣은 배열(285)보다 비쌌다. 두 윈도우를 총합 최소 조합으로 배정하도록 고쳤다. 상세는 [STEP-3](steps/STEP-3-route-optimizer.md) 판정 1·3
- [x] 3-4. 시간 모델 — `t[i] = t[i-1] + 체류 + 이동`, travelMode별 유효속도·고정 오버헤드. `startTime` 5분 단위 올림

  > **5분 올림은 출력 직전 한 번만 한다.** 계산 중에 적용하면 장소마다 최대 4분씩 밀려 7개면 종료가 28분 뒤로 가고, **넘치지 않는 코스가 넘친 것으로 판정돼 축소·드롭이 발동한다** — 표시용 반올림이 장소를 지운다. 같은 이유로 내부 시각은 `LocalTime`이 아니라 `int`(분)다(`plusMinutes`의 자정 랩어라운드가 초과 판정을 뒤집는다)
- [x] 3-5. 하루 초과 처리 — 체류시간 0.8배 축소 → 후순위 슬롯 드롭 → day당 최소 3개에서 중단

  > **[정정] 하루 종료 기본값을 21:00 → 23:59로 넓혔다.** 하루 예산이 690분 → 869분이 되어 장소 7개를 넣어도 224분이 남으므로, **축소·드롭은 기본값에서 사실상 발동하지 않는다.** 그럼에도 구현한 것은 `dayEndTime`이 요청 필드라 6단계에서 Planner가 이른 종료 시각을 넘기면 살아나기 때문이다 — 죽은 코드가 아니라 호출자가 켜는 안전장치다.
  >
  > 드롭 서열은 `SHOPPING → WALK → VIEWPOINT → CAFE → ACTIVITY → ATTRACTION`이고 MEAL은 제외다. **`popularityWeight`를 기준으로 쓰면 안 된다** — 그건 블로그 신호의 신뢰도이지 중요도가 아니라, 오독하면 관광명소(0.2)가 카페(1.0)보다 먼저 버려진다
- [x] 3-6. 단위 테스트 129개 + `@Tag("benchmark")`로 `n=6,7,8,9` 소요시간 실측 (`n ≥ 8` 폴백 임계값의 근거)

  | n | 순열 수 | 1일 | 3일 코스 |
  |---|---|---|---|
  | 6 | 720 | 174µs | 0.52ms |
  | **7** | **5,040** | **589µs** | **1.77ms** |
  | 8 | 40,320 | 4,995µs | **14.99ms** |
  | 9 | 362,880 | 74,055µs | **222.16ms** |

  **임계값 7이 데이터로 뒷받침된다** — 설계 문서의 지연 예산이 잡은 `<10ms`인데 `n=8`은 3일 기준 15ms로 이미 넘고 `n=9`는 20배 초과다. **NN + 2-opt 폴백은 구현하지 않았다**(Planner가 슬롯을 3~6개로 clamp해 현재 도달 경로가 없다 — 도달하지 않는 코드는 검증되지 않은 채 썩는다). 임계값 가드만 두고 초과 시 입력 순서를 유지한다

### 4. `NaverLocalClient` + `TourApiClient` + 후보 공급 순수 함수

동작 변화 없음. **0-2(네이버 키 발급)와 TourAPI 키 발급이 선행돼야 착수 가능하다.** 5단계보다 앞에 두는 이유는 클라이언트와 순수 함수(사전·매핑·dedupe 키)가 외부 의존이 적고 단위 테스트가 가능하기 때문 — 먼저 검증해두면 5단계가 조립에만 집중할 수 있다.

> **[개정] 이 단계는 원래 `NaverBlogClient` + `PopularityScorer` + 컨셉 사전(3·4층 재료)이었다.** PlaceSignalStage가 V1에서 빠지면서(설계 문서의 "PlaceSignal을 V1에서 제외한 이유") 그 셋은 9단계(조건부)로 이동했고, 4단계는 **후보 공급 층의 재료**로 다시 채워졌다. 착수 전이라 번호를 새로 매긴다. 옛 4-1~4-6은 9단계 항목으로 옮겨 적었다.
>
> 상세 실행 계획은 [STEP-4-candidate-sources.md](steps/STEP-4-candidate-sources.md) 참고. (미작성)

- [ ] 4-1. **`NaverLocalClient` (지역검색 시더, 전 슬롯 — 설계 문서의 후보 공급)** — `"{area} {searchHint}"` + `sort=comment` + `display=5`. 관광 슬롯도 같은 클라이언트·같은 규칙(`searchHint` = 관광명소/전망대/산책로/체험). API HUB 인증·`WebClient` 인프라. **V1의 네이버 의존은 이 클라이언트 하나다.** 응답에서 상호명(`<b>` 태그 스트립)·`roadAddress`·`category`·**`mapx`/`mapy`(WGS84×10⁷ → 나눠서 실좌표로 쓴다)**를 모두 취한다 — 초안의 "상호명만 쓰고 카카오로 공식화"는 철회됐다
- [ ] 4-2. **실호출 확정** (설계 문서의 남는 한계 항목 그대로) — ① API HUB 이관 여부, `sort=comment`·`display≤5`·`start=1` 제약 ② `mapx`/`mapy` 실제 형식과 **정밀도**(카카오 좌표와의 오차 표본 → 5-10 `PlaceUrlEnricher`의 300m 임계값 근거) ③ `category` 문자열 실제 형태·최상위 분류 목록 ④ `title` 태그 형태 ⑤ **서술어 매칭 범위**(`"황리단길 카페"` vs `"황리단길 루프탑 카페"` — 상호명·카테고리만 매칭하면 5-8의 스타일 modifier 확장이 무력화된다) ⑥ 쿼터가 검색 API 전체 합산인지 ⑦ **관광 슬롯 쿼리 품질** — `"{area} 관광명소"` sort=comment가 유의미한 5건을 주는지, `searchHint` 표현(관광명소/명소/가볼만한곳) 선택
- [ ] 4-3. **키워드→스타일 modifier 사전** (순수 함수) — 사용자 키워드를 traits 닫힌 태그 집합(설계 문서의 지식 신호 층의 4층 표의 어휘를 그대로 재사용)의 가점 태그 상위 1~2개로 매핑. **여기에 LLM을 쓰지 않는다.** 4층이 V1에서 빠져도 이 사전은 살아 있다 — modifier 쿼리의 재료이고, 나중에 4층을 켜면 같은 어휘로 검증한다
- [ ] 4-4. **네이버 `category` → `SlotType` 매핑 사전** (순수 함수, 설계 문서의 지식 신호 층의 2층) — `음식점` → MEAL, `카페,디저트` → CAFE, `관광,명소`·`문화,예술` → ATTRACTION/ACTIVITY. SEEDED에 카테고리 하드 제약을 걸기 위한 것. TourAPI 후보는 `contentTypeId`(12·14 → ATTRACTION/VIEWPOINT/WALK, 28 → ACTIVITY)로 같은 제약을 건다. 매핑에 없는 분류는 통과시키되 표시(감점, 하드 드롭 아님)
- [ ] 4-5. **후보 dedupe·매칭 키** (순수 함수) — MEAL/CAFE/SHOPPING은 provider가 네이버 하나라 기본/스타일 modifier 쿼리 간 중복만 정규화 상호명 + 도로명주소로 잡는다. **관광 슬롯은 시더↔TourAPI 매칭**이 필요하다: 거리 ≤ 300m **그리고** 정규화 이름 유사(괄호·지역 접두사·공백 제거 후 포함 관계 또는 토큰 겹침). 좌표만 쓰면 대릉원 안의 천마총이 합쳐지고 이름만 쓰면 전국의 "향교"가 합쳐진다. 임계값은 4-8 실호출 표본으로 확정. 매칭되면 **병합**(TourAPI 좌표·`cat3` + 네이버 `seedRank`를 한 레코드에), 안 되면 양쪽 다 풀에
- [ ] 4-6. 단위 테스트 (순수 함수 전량) + `NaverLocalClient` 스텁 테스트
- [ ] 4-7. **`TourApiClient` (관광지 커버리지, 설계 문서의 후보 공급 "ATTRACTION 계열 슬롯")** — `locationBasedList2(mapX, mapY, radius=20000, contentTypeId=12|14|28, arrange=거리순, numOfRows=50)`. 응답에서 `title`·`addr1`·`mapx`/`mapy`(WGS84)·`cat1~3`·`contentid`·`modifiedtime`·`firstimage`를 취한다. 반경은 튜닝값이 아니라 최대 고정 울타리이고 실질 필터는 거리순 + cap이다. 캐시 키 `(~1km 격자 좌표, contentTypeId)`, TTL 7일(관광지 목록은 거의 정적이라 더 길어도 된다). **착수 전 실호출로 확정할 것**: 이관 후 오퍼레이션명, `arrange` 거리순 존재 여부, 좌표 형식, 분류체계(`cat1~3` vs 새 체계), 응답 필드, 상권형 명소 등록 여부, 무인지 시군구 항목 수 표본. **운영계정 승인 신청**(개발계정 일 1,000건)을 이 항목 착수와 함께 낸다
- [ ] 4-8. **area 지오코딩** (카카오, 설계 문서의 후보 공급 "area → 좌표") — Planner의 `anchor`를 `"{location} {anchor}"`로 카카오 키워드 검색해 대표 장소 좌표를 얻는다. **캐스케이드**: `anchor` → `area` 텍스트 → `location`, 전부 실패하면 그 day의 TourAPI만 건너뛴다(시더는 텍스트 기반이라 영향 없음). 캐시 키 = 쿼리 텍스트, TTL 30일. 메트릭 `ai.geocode{result=hit|fallback_area|fallback_location|failed}` — `fallback_*`가 잦으면 Planner `anchor` 지시를 손본다
- [ ] 4-9. **`cat3` → 스타일 태그 결정론 매핑** (순수 함수, 설계 문서의 후보 공급 "`cat3` → 스타일 태그") — TourAPI 소분류를 4-3과 **같은 traits 어휘**로 옮긴다(폭포·계곡·수목원 → 자연·조용함 / 해수욕장·전망대 → 뷰맛집 / 고택·사찰·민속마을 → 한옥·역사 / 테마공원·체험 → 아이동반·액티비티 / 박물관·미술관 → 문화·실내). 필터가 아니라 **표시** — 후보에 `styleTags`를 달아 Curator 입력과 목록 정렬에 쓴다. 코드표는 4-7 실호출(`categoryCode2`)로 받아 확정

### 5. `CandidateRetrievalStage` + `GroundingStage` + `PlaceUrlEnricher`

동작 변화 없음(파이프라인이 아직 컨트롤러에 연결되지 않는다).

> **[개정]** 원래 제목은 "`GroundingStage` + `PlaceSignalStage`"였다. PlaceSignal이 9단계(조건부)로 빠지고, 후보 공급(5-8)과 URL 보강(5-10)이 들어왔다. GroundingStage의 역할도 "카카오 검색"에서 **"실존 확인 + 좌표 확보"**로 재정의됐다 — SEEDED는 네이버 응답, LISTED는 TourAPI 응답을 승계하고 **SUGGESTED만 카카오를 호출한다**(설계 문서의 후보 공급).
>
> 상세 실행 계획은 [STEP-5-grounding.md](steps/STEP-5-grounding.md) 참고. (미작성)

- [ ] 5-1. 스레드풀 2개 신설 — `aiAgentExecutor`(LLM)와 `placeGroundingExecutor`(카카오·네이버·TourAPI 공유). **벌크헤드로 나누는 이유**는 외부 장소 API가 느려질 때 그 대기가 LLM 슬롯을 잠식하면 안 되기 때문이다(LLM은 3~10초짜리 소수, 장소 API는 0.15~0.3초짜리 다수). **여기서 `llm.max-concurrent-calls: 2`를 재실측한다** — 2-6 측정은 요청 간 5초 지연·동시 호출 1이라는 느슨한 조건이었고(429 0/120), day별 Curator가 실제로 동시에 몰리는 이 단계에서 재야 초기값의 근거가 된다
- [ ] 5-2. `GroundingStage` — **`SUGGESTED`만 카카오 병렬 검증**, 점수 하한 미달 탈락. **검증 성공 시 응답의 `place_url`을 좌표·주소와 함께 승계**해 5-10이 같은 장소를 다시 부르지 않게 한다. `SEEDED`는 네이버 응답(좌표·주소·카테고리), `LISTED`는 TourAPI 응답(좌표·주소·`cat3`)을 **코드가** 승계해 호출 없이 통과. 4-5의 dedupe 키로 전 day 중복 제거. **여기를 통과 못 한 장소는 파이프라인에 존재하지 않는다.** Curator 출력 순서가 선호 순위이며, 슬롯당 통과한 1순위가 배치 대상이다(사후 재정렬 층 없음)
- [ ] 5-8. **`CandidateRetrievalStage` (설계 문서의 후보 공급)** — Planner 직후, day × 슬롯타입별 병렬로 실존 후보 목록을 만든다. 소스는 `CandidateSource` 인터페이스 목록: `NaverLocalSeedSource`(**전 슬롯**, `SEEDED`, 텍스트 쿼리 `"{area} {searchHint}"` — 반경 파라미터 없음) + `TourApiSource`(**ATTRACTION·VIEWPOINT·WALK·ACTIVITY**, `LISTED`, 4-8 좌표 기준 거리순). **카카오는 후보 소스로 쓰지 않는다**(설계 문서의 후보 공급 "카카오 커버리지 검색을 후보 소스에서 뺀 이유"). 관광 슬롯은 4-5 규칙으로 시더↔TourAPI를 **병합**(제거가 아님 — `seedRank`·`official`·`styleTags`·`distanceKm`를 한 레코드에)하고, **목록을 사전식으로 정렬**한다: ① seed 후보(seedRank 순) → ② 스타일 태그가 키워드와 맞는 TourAPI 후보(거리순) → ③ 나머지(거리순), **20~25건 cap**. LLM의 위치 편향을 억누르지 않고 쓰는 것이며 가중치 튜닝값이 없다(설계 문서의 후보 공급 "시더 ↔ TourAPI 병합과 Curator 입력 목록"). `listIndex` 부여. **SEEDED/LISTED는 응답 좌표·주소·분류를 그대로 목록에 싣는다**(카카오 재검색 없음). **스타일 modifier 쿼리 확장** — 사용자 키워드를 4-3의 modifier 사전에 넣어 가점 태그 상위 1~2개를 `"{area} {trait} {searchHint}"`로 추가 질의(전 슬롯), 결과는 기본 쿼리와 **합집합**, 후보에 `matchedModifier` 힌트 부여(검색이 그렇게 주장했다는 힌트일 뿐 검증된 속성이 아님을 Curator 프롬프트에 명시). 풀이 스타일을 모르면 Curator 선별은 천장 아래에서만 움직인다는 것이 근거(설계 문서의 후보 공급). **fail-open** — 소스별 실패는 그 소스만 빠지고, 전부 실패하면 빈 목록으로 Curator를 돌린다(초안 구조로 degrade, hard fail 아님). 캐시 키는 네이버 `(area, slotType[, modifier])`, TourAPI `(격자, contentTypeId)`. 메트릭 `ai.candidate.retrieval{source=naver_local|tour_api, result}`, `ai.candidate.adopted{source, modifier, seeded, official}`. **추후 개선(범위 밖)**: 사전이 day 문맥을 못 잡는 것이 실측되면 Planner `dayPlans[].styleTags`(traits enum, 최대 3)를 사전 태그와 합집합으로 추가
- [ ] 5-9. **후보 공급 실측** — 하네스 지역 세트(유명/무인지)로 네이버 시더의 슬롯당 확보 건수와 빈 결과 비율, 관광 슬롯의 시더↔TourAPI 겹침·오매칭 표본(4-5 임계값 근거)을 잰다. 빈 결과가 잦은 지역·슬롯이 있으면 "0건/실패 시에만 카카오" 폴백을 그때 붙인다 (설계 문서의 남는 한계)
- [ ] 5-3. 슬롯별 카테고리 하드 제약 — 현재 `category_group_code`를 가점 +2로만 쓰는 것을 하드 제약으로 승격(MEAL←FD6, CAFE←CE7, ATTRACTION←AT4/CT1). SEEDED에는 4-4의 네이버 카테고리 매핑으로 같은 제약을 건다. 비용이 사실상 0인데 "점심에 호프집"이 구조적으로 사라진다
- [ ] ~~5-4. `PlaceSignalStage`~~ → **9단계로 이동** (V1 제외, 조건부). 이 자리는 비워둔다 — 번호를 당기면 5-5 이하를 참조하는 문서가 흔들린다
- [ ] 5-10. **`PlaceUrlEnricher` (설계 문서의 후보 공급)** — RouteOptimizer가 배치를 확정한 뒤, **URL이 빈 장소(`SEEDED`·`LISTED`)에만** `"{상호명} {지역}"`으로 카카오 키워드 검색 1회. `SUGGESTED`는 5-2에서 승계한 `place_url`을 그대로 쓴다. **수락 조건 두 개**: 점수 하한 통과 **그리고** 카카오 좌표↔후보 좌표(네이버/TourAPI) 거리 ≤ 300m(4-2 실측으로 조정). 하나라도 미달이면 `null` — **엉뚱한 장소 URL은 URL 없음보다 나쁘다**(배경 "환각 세탁"을 URL에서 반복하지 않는다). FE가 `placeUrl`로 카카오 플레이스에 진입하므로 필요하지만 코스 성립 조건은 아니다 → fail-open, 전용 ErrorCode 없음. URL이 빈 배치 장소 ~10~15개에만 호출(후보 45개가 아니라). 메트릭 `ai.place.url{result=hit|below_threshold|too_far|failed}`
- [ ] 5-5. 파이프라인 하드 데드라인 — `CompletableFuture.allOf(...).get(remainingMs, MILLISECONDS)`. `CallerRunsPolicy`를 유지하되(거부보다 느린 성공이 낫다) 요청 스레드가 장소 API I/O를 직접 수행해 순차 실행으로 퇴화하는 것을 데드라인으로 막는다
- [ ] 5-6. `ai.grounding.match{result=hit|below_threshold|no_result, source=seeded|listed|suggested}` 메트릭 — **환각률의 운영 프록시이자 이 작업의 핵심 지표.** 이 저장소는 커스텀 Micrometer 메트릭이 아직 0건이므로 `MeterRegistry` 주입 패턴을 여기서 처음 세운다
- [ ] 5-7. 스텁 기반 통합 테스트 (0-6의 WireMock 인프라 사용)

### 6. `PlannerAgent` / `CuratorAgent`

동작 변화 없음.

> 상세 실행 계획은 [STEP-6-agents.md](steps/STEP-6-agents.md) 참고. (미작성)

- [ ] 6-1. `PromptLoader` — 프롬프트를 `resources/prompts/*.md`로 분리하고 `@PostConstruct`에서 eager 로드. 파일이 없으면 **애플리케이션 기동이 실패**하므로 런타임이 아니라 배포 시점에 발견된다. 플레이스홀더는 위치 기반 `%s`가 아니라 **명명 기반 `{{location}}`**
- [ ] 6-2. `PlannerAgent` — 컨셉·제목·day별 권역(`area`)·**권역 안 랜드마크(`anchor`, 필수 문자열 1개)**·슬롯 구성. **장소명은 한 개도 생성하지 않는다**(`anchor`는 검색 기준점일 뿐 코스에 들어가는 장소가 아니다). `area`는 자연어 그대로 둔다 — 시군구로 바꾸면 day 단위 locality를 잃는다(설계 문서의 후보 공급 "area → 좌표"). 결정론적 기본 플랜은 `anchor = location`. Planner를 별도 단계로 두는 이유는 "단계 분할"이 아니라 **Curator를 day별 병렬 실행하려면 day별 권역이 먼저 확정돼야 하기 때문**이다
- [ ] 6-3. Planner 출력 구조 검증 — day 수 불일치·MEAL 누락·슬롯 개수 초과를 **코드로 보정**한다(LLM 재호출 없음). `anchor`가 비면 `area` 텍스트로 대체(지오코딩 캐스케이드 4-8이 이어받는다)
- [ ] 6-4. `CuratorAgent` — day별 병렬, 슬롯당 후보 3개. 다른 day는 모른다. **역할은 "회상"이 아니라 "선별"이다(설계 문서의 후보 공급)** — 입력에 5-8의 슬롯별 후보 목록이 들어가고, 입력 항목은 `[seed n위] 이름 · styleTags · distanceKm` 한 줄 형태이고 출력은 `source`(`SEEDED`/`LISTED`/`SUGGESTED`) + `listIndex` + `placeName`. **선별 규칙을 프롬프트에 우선순위 순으로 적는다**: 테마·키워드 적합 → seed 표식 우선(근거이지 명령 아님) → `distanceKm` 작은 쪽 → `SUGGESTED`는 확신할 때·목록에 없을 때만 → 3개를 선호 순서로. 목록 밖 파라메트릭 제안(`SUGGESTED`)은 허용하되 그라운딩 검증을 거친다. **응답 스키마의 루트는 반드시 객체여야 한다** — 0단계에서 최상위 배열 스키마가 400으로 거부되는 것을 확인했으므로, 슬롯 배열을 루트에 두면 안 된다
- [ ] 6-7. **`SEEDED`/`LISTED` 위조 강등 검증 (코드)** — `listIndex` 범위, 목록 항목 상호명과 `placeName` 일치, 슬롯 타입 일치를 검증하고 하나라도 어긋나면 `SUGGESTED`로 **강등**(버리지 않는다 — 실존할 수 있다). "재검증 생략"의 전제는 좌표·`kakaoId`를 LLM이 옮겨 적는 게 아니라 코드가 목록에서 승계하는 것이므로, 응답 스키마에 좌표·id 필드를 두지 않는다. 메트릭 `ai.candidate.demoted`
- [ ] 6-5. **`duration` 키워드 처리 방침 결정** — 무시할지, `days`와의 모순 검증에 쓸지. 지금은 "보내지만 아무도 해석하지 않는" 상태이며 label 표기도 어긋나 있다(설계 문서의 프롬프트 전략)
- [ ] 6-6. 프롬프트에서 사라진 규칙 확인 — 시간 배치·동선·중복·스키마 강제는 이제 코드가 보장하므로 프롬프트에 남기지 않는다. 약 45줄이 사라지고 "취향과 컨셉"만 남는 것이 이 분리의 본질이다

### 7. `AiCoursePipeline` 오케스트레이터 + 폴백

동작 변화 없음(컨트롤러 미연결).

> 상세 실행 계획은 [STEP-7-pipeline.md](steps/STEP-7-pipeline.md) 참고. (미작성)

- [ ] 7-1. `AiCoursePipeline` — Planner → **CandidateRetrieval** → Curator → Grounding(SUGGESTED만) → RouteOptimizer → **PlaceUrlEnricher** 조립. LLM 호출 `1 + days`회, PlaceSignal 없음
- [ ] 7-2. `AiCourseErrorCode` 신설 (`ErrorCode` 인터페이스 구현이라 `GlobalExceptionHandler`는 수정하지 않는다) + `JSON_TRANSFORMATION_FAILED` 오용 정리 — 지금은 방향이 정반대인 두 실패(응답 역직렬화 / 키워드 직렬화)가 같은 코드를 공유한다
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
- [ ] 8-5. E2E 검증 — 실제 요청으로 코스 생성, 좌표·시간·순서 확인
- [ ] 8-6. **파이프라인 환각률 측정** (3점 비교의 마지막 점)

### 9. `PlaceSignalStage` (3층 인기도 + 4층 속성 추출) — **조건부**

동작 변화 있음(품질). 8단계까지만으로도 이미 확실히 낫기 때문에(환각 차단 + 실좌표 동선 + 인기 시드) **순수 부가가치다.**

> **[개정] "플래그"에서 "조건부"로.** 초안은 4층(PlaceProfile)만 여기 두고 3층(인기도)은 V1 본선(옛 4·5단계)에 넣었다. 후보 공급 층이 인기도를 시딩으로 앞에서 반영하면서 **3층·4층 모두 사후 정렬층으로서의 고유 가치를 잃었고**(설계 문서의 "PlaceSignal을 V1에서 제외한 이유"), 그 부가가치가 실재하는지 **측정된 바 없다.** 그래서 "일단 만들어 플래그로 붙이는" 게 아니라, **아래 착수 조건 중 하나가 실측되면 그때 만든다.** 조건은 전부 8단계와 함께 켜는 사용자 삭제 로그(`source`·`modifier` 태그 포함, 설계 문서의 관측 설계)에서 나온다.
>
> **착수 조건** (하나면 충분):
> - `SUGGESTED` 유래 장소의 삭제율이 `SEEDED` 대비 유의미하게 높다 → **3층**(인기도·폐업 감점)의 근거
> - 스타일 modifier 쿼리 유래 장소의 삭제율이 기본 쿼리 유래 대비 유의미하게 높다 → **4층**(속성 검증)의 근거 — SEO 편승 후보가 실제 문제라는 뜻
> - 4-2 실측에서 네이버 지역검색이 서술어를 매칭하지 못해 스타일 축을 retrieval에서 포기했다 → 4층이 스타일 축을 되찾는 유일한 길
> - 골든 데이터셋 평가에서 Curator 자체 순위 대비 3·4층 재정렬이 개선을 보인다
>
> **착수 순서**: 3층 → 4층. Critic(CriticAgent 설계)은 4층 뒤에만 성립한다(traits를 전제하므로).
>
> 상세 실행 계획은 [STEP-9-place-signal.md](steps/STEP-9-place-signal.md) 참고. (미작성 — 착수 조건 충족 시)

**3층 — 인기도 (옛 4-1·4-2·4-3·4-5·5-4에서 이동)**
- [ ] 9-1. `NaverBlogClient` — `display=5`로 조회하면 응답 한 번에 `total`(인기도) + `postdate`(최신성) + 스니펫 5건(4층 재료)이 전부 들어온다. **장소당 호출은 1회다.** 착수 전 실호출로 확정: API HUB 경로·응답 스키마, **지역검색과 쿼터 합산 여부**(비용 분석의 상한 계산 — 켜면 코스당 ~35회가 더해져 상한이 초안 수준으로 내려온다)
- [ ] 9-2. `PopularityScorer` (순수 함수) — `popularity = log10(max(total, 1))`. **로그 스케일이 필수인 이유**: `total`은 1건에서 수백만 건까지 자릿수로 벌어져, 선형으로 쓰면 유명 관광지 하나가 다른 모든 신호를 압도한다. 폐업 의심 = 최신 `postdate`가 12개월 이내인지
- [ ] 9-3. `PlaceSignalStage` — 그라운딩 생존 후보에만 네이버 조회. **`SEEDED`·`LISTED`는 제외**(시드 순위가 이미 인기도이고, TourAPI 관광지의 인기 축도 시더가 맡는다) → `SUGGESTED`만. **역할은 재정렬이 아니라 감점** — Curator 선호 순서를 1차로 두고, 언급 0건·최신 글 없음인 후보를 뒤로 미는 것만 한다. **fail-open**: 네이버 장애 시 층 전체 스킵. 메트릭 `ai.popularity.lookup{result}`
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

- [ ] 10-1. V1이 실제로 쓰는 캐시 넷을 건다 — `kakao:place:{sha1}`(검증·URL 보강) TTL 7일 · `kakao:geo:{anchor}`(지오코딩) **TTL 30일** · `naver:local:{area}:{slot}[:{modifier}]` TTL 7일 · `tour:{~1km 격자}:{contentTypeId}` TTL 7일. **뒤의 둘이 히트율을 지배한다** — 장소 단위가 아니라 `(area, slot)`·격자 단위 키라 인기 권역은 사용자 간에 공유된다. 기존 [RedisConfig.java](../../../src/main/java/backend/yourtrip/global/config/RedisConfig.java)와 [RedisCacheErrorHandler.java](../../../src/main/java/backend/yourtrip/global/config/RedisCacheErrorHandler.java)(Redis 장애 시 fail-open)를 그대로 재사용. `naver:blog:{sha1}`은 9단계를 켤 때 함께 붙인다
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

> **[2-6 중간 결과]** `gpt-5.6-luna` / 프롬프트지시 / 추론 `low` 조합 30요청 전량 성공, 장소 454개 기준 **자동 프록시 환각률 6.4%**(Gemini 19.8% → −13.4%p), **JSON 실패율 0.0%**(Gemini 16.7% → 0/30). `S8_10` 밴드 비중이 66.1% → 84.6%로 올랐다.
>
> **수동 검증 82건 완료**(luna 40 + nano 42) — luna의 세탁된 환각률 1.08%p가 나와 복합 지표가 25.6%와 같은 정의로 완성됐다. 밴드별 `LAUNDERED`는 `NO_RESULT` 3/10 · `S1_4` 1/10 · `S5_7` 0/10 · `S8_10` 0/10이다.
>
> **비중 84.6%인 `S8_10`에서 세탁이 0인 것은 표본운이 아니라 구조 때문이다** — `score()`의 가점 조합상 8점 이상은 반드시 이름 일치(+5)를 포함하므로, 지어낸 이름이 "카카오 상호명과 부분 일치 + 지역 일치"를 동시에 만족하기 어렵다. 반대로 **`S5_7`은 `5 = 주소3 + 카테고리2`로 이름이 하나도 안 맞아도 도달**하므로 위험 밴드라는 1-2의 진단이 여기서도 유효하다(다만 luna는 부른 이름이 전부 실존해 `LAUNDERED` 0건).
>
> **자동 프록시 6.4%는 과대평가다.** `NO_RESULT` 10건 중 3건(통영 분소식당, 제주 민트 레스토랑, 통영 연대도몽돌해변)이 **실존 업소인데 카카오가 못 찾은 것**이었다. BASELINE 문서가 명시한 한계가 실제로 관측된 셈이다.
>
> **JSON 실패율 0%는 구조화 출력의 성과가 아니다.** 이 조합은 스키마를 보내지 않은 판이므로, Gemini의 16.7%가 구조화 출력 부재가 아니라 **모델 성질**이었다는 뜻이다 — [BASELINE-ARTIFACT-ANALYSIS.md](hallucination/BASELINE-ARTIFACT-ANALYSIS.md) 판정 3("원인은 절단")과 방향이 같다.
>
> **"모델 교체" 축에 온도와 추론 강도가 딸려 들어간다** — 두 모델 모두 커스텀 온도를 거부하고(기본값 1 고정) 추론 강도도 Gemini와 다르다. 우리가 고를 수 있는 변수가 아니라 모델 선택에 딸려오는 성질이며, 온도가 높은 쪽이 불리하므로 6.4%는 보수적인 값이다. 상세는 [STEP-2-llm-port.md](steps/STEP-2-llm-port.md) 판정 6

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
| `ai.grounding.match{below_threshold\|no_result}` | 미계측 | 5단계부터 상시 관측 |
| JSON 파싱 실패율 | ~~28.6%~~ → **16.7%** | 구조화 출력 + 절단 방지 → **2-6에서 0.0% 관측**(luna/프롬프트지시) |

> **[정정]** 28.6%는 호출이 14건만 성공한 초기 배치의 값(4/14)이었다. 전체 30요청 기준은 **16.7%(5/30)** 다. 또 실패 5건 전부가 `Unexpected end-of-input`(응답 절단)이라 **구조화 출력만으로는 near-zero가 되지 않는다** — 2단계에서 출력 토큰 여유와 종료 사유 확인이 함께 필요하다. 근거는 [BASELINE-ARTIFACT-ANALYSIS.md](hallucination/BASELINE-ARTIFACT-ANALYSIS.md) 판정 3. 재측정 시 **분모(전체 요청 vs 호출 성공분)를 반드시 명시한다.**

## 범위에서 제외한 것

- **`CriticAgent` / `CandidateRefiner`** — 설계는 CriticAgent 설계/CandidateRefiner 설계에 남긴다. 제외 근거는 설계 문서의 지연 예산에 정리돼 있고, 재검토 조건은 두 가지다: ① 골든 데이터셋/LLM-as-judge 평가 인프라가 생겨 "Critic이 실제로 개선하는가"를 측정할 수 있을 때 ② 실제 사용자 피드백에서 컨셉 미스매치 불만이 반복될 때
- **골든 데이터셋 / LLM-as-judge 평가 인프라** — 파이프라인이 안정된 뒤 착수하는 것이 맞다
- **202 Accepted + 폴링 전환** — 동기 API 계약을 유지한 채 먼저 완성해 실측하고, p95가 목표를 넘는 것을 데이터로 확인한 뒤 전환한다. 그래야 전환이 "숫자에 근거한 결정"이 된다
- **사용자 피드백 루프** — 생성된 코스에서 사용자가 삭제한 장소가 곧 정답 라벨이고, 이건 외부 API가 아니라 우리가 축적하는 고유 자산이다. **다만 소급할 수 없는 데이터라 삭제 이벤트 기록은 일찍 시작할 가치가 있다** — 별도의 작은 작업으로 분리한다
- **Gemini 어댑터 유지** — OpenAI 확정으로 불필요. 2단계 baseline 재측정이 끝나면 8단계에서 삭제한다
- **Spring Boot 4 마이그레이션** — Spring Boot 3.5가 2026-06-30 오픈소스 EOL에 도달했으므로 언젠가는 해야 하지만 별도 작업으로 분리한다. 코드 수정량 자체는 크지 않으나(Jackson 3 전환 11개 파일, `@MockBean`→`@MockitoBean` 3곳, springdoc 3.1.0, `springboot4-dotenv`, Security 7), **깨지는 곳 대부분이 런타임에만 드러나는 영역**(캐시 직렬화, JWT 필터, Security 체인, Swagger)인데 이 레포의 통합 테스트는 E2E 1개뿐이라 검증 비용이 크다. 상세 근거는 [STEP-0-prerequisites.md](steps/STEP-0-prerequisites.md) 참고
- **`SEQUENCE` 전환** — `Place`/`DaySchedule`이 `GenerationType.IDENTITY`라 JDBC 배치 INSERT가 원천 불가능하지만, ~20건 규모라 지금은 무시해도 된다

## 미해결 · 확인 필요

- ~~**Spring AI ↔ Spring Boot 버전 스큐**~~ — **2단계에서 문제 없이 통과했다.** 어댑터 구현·WireMock 검증·앱 기동 전부에서 `NoSuchMethodError`가 관측되지 않았다. 다만 아직 쓰지 않은 경로(스트리밍, 툴 콜링)가 남아 있으므로 완전히 닫힌 항목은 아니다
- **OpenAI RPM/TPM 티어** — `llm.max-concurrent-calls` 초기값 2의 근거. **2-6에서 1차 확인: 120요청 중 429 0건.** 다만 요청 간 5초 지연·동시 호출 1이라 조건이 느슨해 초기값의 근거로 삼기엔 부족하다 — **5단계 병렬화 이후 실제 동시 호출 조건에서 재실측이 필요하다**(5-1 참고). 2단계에서 확인된 사실 하나: Spring AI는 429를 재시도 대상으로 보지 않으므로(위 2-4 정정) 티어에 걸려도 자동 복구되지 않는다 — 우리 분류가 그걸 막고 있다
- **`max_completion_tokens` 대 `max_tokens`** — 추론 계열 모델은 추론 토큰이 출력에 포함되므로 전자를 쓴다. 실제로 gpt-5 계열이 `max_tokens`를 거부하는지는 2-6 실호출에서 확인된다(WireMock은 필드가 실려 나가는 것까지만 검증한다)
- **NAVER API HUB의 지역검색 경로와 응답 스키마** — 새 Base URL(`naverapihub.apigw.ntruss.com`) 아래에서 `/v1/search/local.json`이 유지되는지, `sort=comment`·`start=1` 제약, `mapx`/`mapy` 형식·정밀도, `category` 형태, 서술어 매칭 범위. **4단계(4-2) 착수 시 실호출로 확정.** 블로그 검색(`/v1/search/blog.json`, `total`·`postdate`·`title`·`description`)은 9단계 착수 시 확인
- **TourAPI 운영계정 승인과 실호출 확정** — 오퍼레이션명·`arrange` 거리순·좌표 형식·분류체계·상권형 명소 등록 여부. 4-7 착수 시. 승인은 며칠 걸리므로 4단계 시작과 함께 신청
- **`placeUrl == null`에 대한 FE 동작** — `PlaceUrlEnricher`가 점수 미달·좌표 불일치 시 URL을 비운다. Swagger 명세는 nullable이지만 FE가 실제로 "링크 없음"으로 처리하는지 8단계 전 확인
- ~~**네이버 일 25,000건 한도 초과 시의 응답 코드**~~ — **429로 확인됨.** 4단계에서 이 코드는 재시도 대상이 아니라 그날의 쿼터 소진으로 다뤄야 한다(지수 백오프를 태우면 이미 소진된 쿼터에 지연 예산만 낭비된다)
- **`duration` 키워드 처리 방침** — 6-5에서 결정
- **`mood` 키워드 포함 비율** — 9-3 조건부 확장이 실제로 얼마나 자주 켜지는지(= 토큰 비용 증가폭)는 추정이 아니라 배포 후 실측이 필요하다
- ~~**파싱 실패율 28.6%의 산출물이 남아 있지 않다**~~ — **산출물은 남아 있었다.** `claude/multi-agent-travel-course-fc4b56` 워크트리의 `results/`에 CSV와 Gemini 원본 응답이 보존돼 있었고, 집계 결과를 [BASELINE-ARTIFACT-ANALYSIS.md](hallucination/BASELINE-ARTIFACT-ANALYSIS.md)에 옮겼다.

  > **[정정]** 재분석 결과 이 항목의 서술 두 가지가 틀렸다. ① **28.6%는 호출이 14건만 성공한 초기 배치의 값**(4/14)이고, 전체 30요청 기준으로는 **16.7%(5/30)** 다 ② 위 목표 1이 추정한 "trailing comma가 유력한 원인"은 **뒷받침되지 않는다** — 실패 5건 전부 `Unexpected end-of-input`(응답 절단)이고, 원본이 남은 1건은 386바이트에서 키 이름 중간에 잘려 있었다(정상 응답은 1,400~1,660바이트). **절단이 원인이면 구조화 출력만으로는 해소되지 않으므로**, 2단계에서 출력 토큰 여유와 종료 사유를 함께 확인해야 한다.

  2-6 재측정부터는 **환각률과 파싱 실패율 모두 CSV로 남기고, 집계 결과를 `docs/`의 문서로 옮긴다**(`results/`는 `.gitignore` 대상이라 CSV 자체는 레포에 남지 않는다).

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
