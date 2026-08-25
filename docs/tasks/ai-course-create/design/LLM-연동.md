# LLM 연동 — 벤더 중립 포트와 프롬프트 전략

> [멀티 에이전트 파이프라인 설계](../멀티-에이전트-파이프라인.md)에서 분리된 문서다. **벤더 중립 LLM 추상화**와 **프롬프트 전략**을 담는다.
>
> 둘을 함께 두는 이유는 **같은 결정의 앞뒤**이기 때문이다 — 포트가 `responseJsonSchema`를 받아 구조화 출력을 디코딩 레벨에서 강제하면, 프롬프트에서 스키마·형식 지시 약 45줄이 통째로 사라진다. 벤더가 OpenAI로 확정된 뒤 갱신된 절이기도 하다.
>
> 실행 기록은 [STEP-2-llm-port.md](../steps/STEP-2-llm-port.md)에 있다 — 측정이 예상을 두 군데서 뒤집었다(Curator 모델 선택, 구조화 출력의 실제 효과).

---

## 벤더 중립 LLM 추상화

**LLM 벤더는 OpenAI로 확정됐다.** 이 절의 초안은 "Gemini는 고정이 아니고 OpenAI로 전환할 가능성이 높다"는 것을 지배적 제약으로 삼았는데, 전환이 확정되면서 그 제약은 **이미 일어난 전환**이 됐다.

그렇다고 포트가 불필요해지지는 않는다. 포트를 두는 근거는 원래 두 가지였고 **두 번째는 그대로 남기 때문**이다 — (1) 향후 벤더 전환 대비, (2) 테스트 가능성(바로 아래). 다만 첫 번째가 소진됐으므로 **어댑터는 OpenAI 하나만 만든다.** Gemini 어댑터를 함께 유지해 A/B를 돌리는 선택지는 채택하지 않는다: 벤더 교체 효과를 분리 측정하는 목적은 남는 한계의 3점 baseline 측정이 이미 달성하고, 그 측정이 끝나면 Gemini 경로는 도입 순서 8단계에서 삭제된다.

```java
public interface LlmClient {
    <T> T generate(LlmCall<T> call);
    <T> CompletableFuture<T> generateAsync(LlmCall<T> call, Executor executor);
}

public record LlmCall<T>(
    String agentName,          // 설정 조회 키 + 메트릭 태그
    String systemInstruction,  // 고정 규칙 (향후 컨텍스트 캐싱 대상이 자명해짐)
    String userPrompt,         // 가변 데이터만
    Class<T> responseType,
    String responseJsonSchema  // JSON 문자열 — 벤더 Schema 타입이 포트에 새지 않는다
) {}
```

> **[제약 확인] 응답 스키마의 루트는 반드시 객체여야 한다.** 0단계 실 API 검증에서 루트가
> `type: "array"`인 스키마를 보내면 **400**(`schema must be a JSON Schema of 'type: "object"'`)이
> 떨어지는 것을 확인했다. **CuratorAgent 응답이 정확히 이 함정에 걸린다** — 슬롯 배열을 루트에 두고
> 싶은 유혹이 있지만, 파이프라인 전체 구조의 예시(`{ "day": 1, "slots": [...] }`)처럼 반드시 객체로 감싸야 한다.
> `resources/schemas/*.json`을 쓸 때 전부 이 규칙을 지킨다.

`responseJsonSchema`를 벤더 타입이 아닌 **JSON 문자열**로 받는 것이 벤더 중립의 핵심이다.
벤더마다 이걸 받는 창구가 다르기 때문이다 — OpenAI는 `response_format: json_schema`, Gemini는
`GenerateContentConfig.responseJsonSchema(...)`. 어느 쪽이든 **어댑터가 JSON 문자열을 자기 벤더의
타입으로 옮기면 되고, 포트는 그 차이를 모른다.** 스키마는 `resources/schemas/*.json`에 둔다.

### 포트가 선택이 아니라 필수인 이유 — 테스트

벤더가 확정된 지금, 포트를 정당화하는 근거는 **이것 하나로 충분하다.**

`com.google.genai.Client`는 **`public final class`**이고 `models`도 **`public final` 필드**다.
Mockito로 목킹할 수 없다. 지금 코드에서 LLM 호출부의 단위 테스트가 사실상 불가능한 이유가 정확히
이것이고, [AI-HALLUCINATION-GEMINI.md](../hallucination/AI-HALLUCINATION-GEMINI.md)의 측정 하네스가
Spring 컨텍스트 없이 `new GeminiService(...)`를 수동 조립해 **실제 API를 때리는 방식**을 택한 것도
같은 제약 때문이다. 포트를 두면 에이전트(V1: Planner·Curator 2개. PlaceProfile은 9단계 조건부)의 테스트가
**벤더 SDK 타입을 한 개도 import하지 않는다.** "추상화를 위한 추상화"가 아니라 테스트 가능성이라는
구체적 대가를 받는다. 이 근거는 벤더가 무엇으로 확정되든 사라지지 않는다.

### 설정 외부화

```yaml
llm:
  provider: openai            # 확정. @ConditionalOnProperty 로 어댑터 선택 (구조는 유지)
  timeout-ms: 20000
  max-concurrent-calls: 2     # ★ RPM/TPM 티어 방어. 상위 티어 전환 시 이 값만 올린다
  retry:
    attempts: 3               # 전송 계층(429/5xx)
    semantic-attempts: 2      # 의미 계층 — 초회 + 보정 1회
    initial-delay-seconds: 0.5
    max-delay-seconds: 4.0
    jitter: 0.3
  agents:
    planner: { model: gpt-5.6-luna, max-output-tokens: 2048 }
    curator: { model: gpt-5.6-luna, max-output-tokens: 4096, reasoning-effort: low }
    # 아래는 설정만 미리 있고 V1에서 호출되지 않는다 ("PlaceSignal을 V1에서 제외한 이유").
    place-profile: { model: gpt-5-nano, max-output-tokens: 2048, reasoning-effort: minimal }
    # critic: V1 범위 밖 (지연 예산 참고)
```

> **`temperature`가 여기 없는 이유 — 모델이 거부한다.** `gpt-5.6-luna`·`gpt-5-nano` 모두 커스텀
> 온도를 **400으로 거부**한다(`"Only the default (1) value is supported"`). 설정 키 자체는 nullable로
> 남겨뒀다 — 온도를 받는 모델로 바꾸면 값만 채우면 되고, 구조를 지우면 "왜 차등하지 않는가"라는
> 정보까지 사라진다 (STEP-2 판정 6①).

> **[갱신] V1 에이전트는 Planner·Curator 둘이다.** 아래 문단들은 PlaceProfile을 세 번째 에이전트로
> 두고 쓴 초안 시점의 근거인데, PlaceSignal이 V1에서 빠지면서 PlaceProfile 관련 서술은 9단계
> 재검토 시의 참고 자료가 됐다. **Planner·Curator에 대한 근거는 그대로 유효하다.**

모델 배정 근거는 [steps/STEP-0-prerequisites.md](../steps/STEP-0-prerequisites.md)에 있다. 요지는
**"추론 난이도"가 아니라 "틀렸을 때의 파급 ÷ 토큰량"으로 골랐다**는 것이다 — Planner는 파급이
가장 큰데(권역이 틀리면 그 아래 Curator와 카카오 검색이 전부 오염된다) 호출 1회에 출력 350토큰이라
가장 싸게 투자할 수 있고, Curator는 환각률에 직결되므로 지식 폭이 넓은 최신 세대를 쓰며,
PlaceProfile은 닫힌 태그 분류라 세대 이점이 작은데 입력 토큰의 76%를 차지해 최저가로 내렸다.
**Curator를 `gpt-5.6-luna`로 확정한 것은 2단계 실측이다** — `gpt-5-nano`는 같은 조건에서 지어냄률이
38.6배(41.7% vs 1.08%, [STEP-2](../steps/STEP-2-llm-port.md) 판정 7)라 비용을 아끼려고 내리면 1차 목표를 정면으로 훼손한다.

**agent별 temperature를 다르게 두려던 근거, 그리고 그걸 실행할 수 없는 이유.** 현재 코드의 단일
`0.3`은 "장소 선정은 다양해야 하고 판정은 일관돼야 한다"는 상충 요구를 하나로 뭉갠 값이다. 의도는
**Curator를 올리고**(후보 3개가 서로 비슷하면 대체재로서 의미가 없다) **PlaceProfile을 낮추는
것**(속성 추출은 창의성이 아니라 충실성이 필요하다)이었는데, 위 제약 때문에 **둘 다 지정할 수 없다.**
결과는 반쪽이다 — Curator 쪽은 기본값 1이 이미 높아 우연히 충족되지만, **PlaceProfile 쪽은 그대로
손해로 남아** 9단계에서 닫힌 태그 집합과 스키마 강제로 보완해야 한다.

**agent별 `model`을 다르게 두는 근거도 같은 논리다.** 셋 중 추론 이득이 실제로 있는 건
Planner(컨셉·권역 설계)뿐이고, Curator(지역 상식 회상)와 PlaceProfile(속성 추출)은 추론 이득이
적으면서 **토큰 비중은 가장 크다**(비용 분석). 그래서 Planner만 상위 모델, 나머지는 mini급으로 둔다.

**`max-concurrent-calls` 세마포어가 rate limit 대응의 전부다.** 2로 두면 day별 Curator 3개가
2라운드로 나뉘어 실행된다(+3~6초). 429가 나는 대신 느려질 뿐이고, 티어 상향은 설정값 한 줄이다.
**"Curator를 day별 병렬로 만들지, 1회 통합으로 만들지"를 코드 구조로 결정하지 않는 것**이 요점이다.

초안에 있던 `thinking-budget`은 **Gemini 전용 옵션이라 제거하고**, "OpenAI에 대응하는 추론 강도
설정이 있다면 그때 다루고 없으면 사라진 채로 둔다"고 열어뒀다. **있었다 — `reasoning-effort`다.**
다만 어댑터 내부에 가두지 않고 **agent별 설정으로 노출했다.** 비용 절의 "Curator·PlaceProfile은
추론을 쓰지 않는다"를 실행하는 수단이면서, 안 낮추면 비용이 아니라 **응답 자체가 안 나오기**
때문이다 — `gpt-5-nano`는 기본 설정에서 `max-output-tokens: 4096`을 추론에만 쓰고 본문을 0바이트로
돌려줬다. 지원값이 모델마다 달라(luna는 `none`/`low`…, nano는 `minimal`/`low`) **두 모델을 같은
조건으로 비교할 수 있는 공통 최저값은 `low` 하나뿐이다** (STEP-2 판정 6②).

**`max-output-tokens`도 초안에 없던 항목인데 agent별로 추가했다** — 절단이 파싱 실패의 실제
원인이므로(아래) 출력 여유가 설정 대상이어야 한다.

### 재시도 2계층

| 계층 | 대상 | 구현 |
|---|---|---|
| 전송 | 429 / 5xx | `LlmRetryExecutor`가 `llm.retry` 설정으로 지수 백오프 + 지터 |
| 의미 | **200 OK인데 쓸 수 없는 응답** (절단 · 스키마 위반) | 어댑터가 프롬프트에 보정 지시를 붙여 **1회만** 다시 호출 |

**재시도는 `LlmResponseParser`의 책임이 아니다.** 초안의 이 표는 의미 계층을 파서에 맡겼지만,
재시도하려면 LLM을 다시 불러야 하고 그건 파서가 할 수 있는 일이 아니다. 파서는 **순수 변환**만 맡아
외부 의존 없이 결정론적으로 테스트되고, 재호출은 어댑터가 오케스트레이션하며, 전송 재시도는
백오프 계산을 순수 함수로 떼어내기 위해 `LlmRetryExecutor`로 분리했다. 보정 수단도 temperature
조정이 아니라 **프롬프트에 덧붙이는 보정 지시**다 — 온도를 바꿀 수 없기 때문이다.

**파싱 실패를 없앤 것은 구조화 출력이 아니라 모델 교체다.** 초안은 "스키마를 디코딩 레벨에서
강제하므로 파싱 실패율이 near-zero"라고 적었는데 실측이 그 인과를 뒤집었다 — Gemini의 파싱 실패는
전부 응답 **절단**이었고 **절단은 스키마로 막히지 않는다.** OpenAI 재측정 120요청에서 절단이 0건인
것은 모델이 달라졌기 때문이다. 구조화 출력의 실익은 다른 데 있었다: **출력 바이트 −48%**(pretty-print
제거)와 **스키마 밖 필드 차단**(`additionalProperties: false`). 어느 쪽이든 2회 이상 시도는 지연
예산만 태운다 (STEP-2 판정 6·9 · BASELINE-ARTIFACT-ANALYSIS 판정 3).

**resilience4j를 추가하지 않는다.** 전송 재시도는 위로 충분하고, 서킷브레이커를 얹으면 LLM 장애 시
"코스를 아예 못 만드는" 상태가 되는데 아래 폴백 전략이 어차피 에이전트 실패를 개별 흡수한다.

### `LlmClient` 구현체는 직접 짜되, 전송 계층은 Spring AI로

**오케스트레이션(Planner→CandidateRetrieval→Curator→Grounding→RouteOptimizer)은 어떤 프레임워크를
쓰든 항상 직접 짜야 하는 도메인 로직이다.** Spring AI(`ChatModel`/`ChatClient`)나 LangChain4j(`ChatLanguageModel`)가
실제로 대신해주는 부분은 "벤더 SDK 차이를 가리는 통일 인터페이스"뿐이고, 이건 이미 `LlmClient`로
직접 만들어뒀다. 두 프레임워크의 나머지 기능(툴 자율 호출, RAG용 `VectorStore`, 대화 메모리)은
이 파이프라인에 대응물이 없다 — 툴 자율 호출은 기각한 대안에서 명시적으로 기각했고, RAG는 이 파이프라인
어디에도 없다(카카오·네이버는 벡터 검색이 아니라 REST 직접 호출). 그래서 프레임워크를 전면 도입하면
쓰지도 않을 표면을 위해 버전 안정성 리스크(Spring AI는 1.0 GA가 비교적 최근)와 벤더 커버리지
불확실성을 떠안는 것 대비 얻는 게 적다.

다만 **포트(`LlmClient`)는 유지하고, 그 구현체(`OpenAiLlmClient`) 내부의 전송 계층만 Spring AI의
`ChatModel`로 구현하는 절충은 채택한다.**

```
LlmClient (interface, 우리 도메인 타입만 다룸)          ← 유지
  └─ OpenAiLlmClient implements LlmClient
        내부에서 Spring AI OpenAiChatModel 사용
```

**왜 절충이 타협이 아니라 정당한 설계인가**
- `LlmClient` 인터페이스를 유지하는 한, 에이전트 코드(`PlannerAgent`/`CuratorAgent`, 나중에 `PlaceProfileAgent`)는
  Spring AI의 존재 자체를 모른다. 앞서 확보한 테스트 가능성 근거(`com.google.genai.Client`가 final이라
  Mockito로 못 묶는 문제를 포트로 우회한 것)가 그대로 유지된다.
- 헥사고날 아키텍처에서 "포트는 직접 정의, 어댑터 내부 구현은 서드파티 SDK"는 흔히 권장되는
  형태다 — 프레임워크를 전역에 노출하지 않고 어댑터 하나에 가둬 쓰는 것이다.
- 어댑터 내부 구현이 raw SDK 호출에서 Spring AI 호출로 바뀌어도 `LlmCall`/`LlmResponseParser` 등
  포트 바깥의 코드는 전혀 바뀌지 않는다.

**착수 전 검증했던 것 — 결과는 "전제 성립"이다.** 실패했다면 이 절충 자체가 성립하지 않았다.

> 초안에는 검증 항목이 둘이었다. 첫 번째(~~Spring AI의 Gemini 통합이 API 키 방식을 지원하는가,
> Vertex AI 전용인가~~)는 **벤더가 OpenAI로 확정되면서 물음 자체가 사라졌다.**

남은 질문은 **Spring AI의 구조화 출력이 스키마를 디코딩 레벨에서 강제하는가**(OpenAI의
`response_format: json_schema`를 그대로 노출하는가), 아니면 프롬프트 지시 기반 JSON 모드로
떨어지는가였다. **0단계에서 성립을 확인했다** — 스키마가 `messages[].content`에 섞이지 않고 전부
`response_format.json_schema`에 `strict: true`로 실려 나가는 것을 WireMock으로 봤고, 실 API 호출로
`gpt-5.6-luna`·`gpt-5-nano` 모두 strict json_schema를 지원하는 것까지 확인했다. 그래서 아래 폴백은
발동하지 않았고 2단계 어댑터는 Spring AI 전송 계층으로 구현됐다.

> **막혔다면**: Spring AI를 포기하고 **OpenAI 공식 Java SDK(`com.openai:openai-java`)로 어댑터를
> 구현**할 계획이었다. 포트는 그대로이므로 어댑터 내부 구현이 무엇이든 `LlmCall`·`LlmResponseParser`
> 등 포트 바깥 코드는 전혀 바뀌지 않는다 — "프레임워크를 검증 없이 전면 채택하지 않고, 실제 기능
> 지원 여부를 확인한 뒤 도입했다"는 것 자체가 이 결정의 근거로 남는다.

**LangChain4j가 아니라 Spring AI를 고르는 이유**: 이 레포는 이미 전역에 `@Bean`·
`@ConfigurationProperties`·`application.yml` 기반 Spring 관용구가 깔려 있다(`SecurityConfig`,
`RedisConfig` 등). Spring AI는 같은 관용구라 자연스럽게 붙지만, LangChain4j는 Spring Boot starter가
있어도 별도 생태계에서 이식된 프로젝트라는 이질감이 남는다.

---

## 프롬프트 전략 — 95줄 중 절반은 그냥 사라진다

| 구간 | 줄 수 | 새 위치 |
|---|---|---|
| JSON 스키마 + 출력 예시 | ~30 | **`responseJsonSchema` (프롬프트에서 소멸)** |
| 코드블록/필드추가/null/큰따옴표 금지 | ~6 | **구조화 출력이 디코딩 레벨에서 강제 (소멸)** |
| startTime 오름차순·겹침 금지·09~20시 | ~5 | **RouteOptimizer가 계산 (소멸)** |
| 동선 역주행 금지 | ~2 | **RouteOptimizer가 계산 (소멸)** |
| day당 식사 1회 | ~2 | **Planner 출력 검증에서 강제 (소멸)** |
| 실존 상호명만 / 괄호·설명 금지 | ~5 | Curator system instruction |
| 키워드 JSON 해석 규칙 | ~15 | Planner + Curator 공통 system instruction |
| 동행유형·분위기·예산별 톤 조정 | ~15 | Curator system instruction |
| day 수 / 장소 수 / title 작명 | ~5 | Planner (+ 코드 검증) |
| *(신규)* day별 권역 `area` + 랜드마크 `anchor` | – | Planner. `anchor`는 권역 안의 구체적 랜드마크 1개 — 카카오 지오코딩용(후보 공급 "area → 좌표") |
| *(신규)* 후보 목록 선별 규칙 — 테마 적합 → seed 표식 우선 → anchor 거리 → SUGGESTED는 확신할 때만 → 선호 순서 3개 | – | Curator system instruction. 목록 항목은 `listIndex`로 참조(후보 공급 "시더 ↔ TourAPI 병합과 Curator 입력 목록") |

**약 45줄이 사라지고 남는 것은 순수하게 "취향과 컨셉"뿐이다.** 그게 정확히 LLM에게 시켜야 할 일이다.
이것이 프롬프트 분리 전략의 본질이지 단순 3등분이 아니다.

덤으로 현재 프롬프트의 결함도 해소된다:
- 규칙 5는 `placeLocation`을 채우라 하는데 `PlaceDto`에 그 필드가 없고 규칙 12는 스키마 외 필드를
  금지한다 — **실행되지 않는 죽은 지시문**이다.
- 프롬프트 JSON 예시에 **trailing comma**가 있다(54·102·106줄). 유효하지 않은 JSON을 예시로
  보여주는 것이라 그 자체로 고칠 값어치가 있다. 다만 **초안이 이것을 파싱 실패의 유력한 원인으로
  지목한 것은 실측으로 반증됐다** — Gemini 실패 5건은 전부 응답 절단(`Unexpected end-of-input`)이고
  trailing comma 유형은 하나도 없었다. 실패율도 28.6%가 아니라 **16.7%(5/30)** 다 — 28.6%는 호출이
  14건만 성공한 초기 배치의 값이었다 (BASELINE-ARTIFACT-ANALYSIS 판정 3).
- **`duration` 키워드 카테고리가 사실상 죽은 신호다.** `KeywordType`에 `ONE_DAY`/`TWO_DAYS`/
  `WEEKEND`/`LONG` 4개가 있고 `buildKeywordsJson`이 이를 JSON에 실어 보내지만, ① 여행 일수는 이미
  `days`로 별도 전달되고 ② 현재 프롬프트는 `duration`의 사용 규칙을 설명하지 않으며(travelMode·
  companionType·mood·budget만 설명) ③ 프롬프트 예시의 `"1박2일"`은 실제 label `"1박 2일"`(공백 있음)과
  일치하지도 않는다. **Planner 프롬프트를 설계할 때 `duration`을 아예 빼거나, `days`와의 모순 검증
  용도로 재정의하거나 둘 중 하나를 택해야 한다.** 지금처럼 "보내지만 아무도 해석하지 않는" 상태를
  그대로 옮기지 않는다.

**프롬프트는 `src/main/resources/prompts/*.md`로 분리한다.**
- 현재 코드에 `\\"` 이스케이프가 실제로 존재한다. 프롬프트에 JSON을 넣는 한 이스케이프 지옥은 계속된다.
- 프롬프트 diff가 자바 로직 diff와 섞이지 않아 리뷰·`git blame`이 유의미해진다. 프롬프트 튜닝은
  로직 변경보다 훨씬 잦다.
- ~~"재컴파일 없이 변경 가능"~~ — **jar에 패키징되므로 성립하지 않는다. 이 흔한 논거는 쓰지 않는다.**
- 텍스트블록의 유일한 실질 장점인 컴파일타임 안전성은 `PromptLoader`가 `@PostConstruct`에서 eager
  로드해 상쇄한다. 파일이 없으면 **애플리케이션 기동이 실패**하므로 런타임이 아니라 배포 시점에 발견된다.

플레이스홀더는 위치 기반 `%s`가 아니라 **명명 기반 `{{location}}`**을 쓴다. 현재
`.formatted(location, days, keywordsJson, days)`처럼 같은 값을 두 번 넘기고 순서에 의존하는 방식은
프롬프트를 편집할 때 조용히 깨진다.
