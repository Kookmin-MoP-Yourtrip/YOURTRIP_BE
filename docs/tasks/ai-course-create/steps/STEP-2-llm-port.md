# STEP-2. `LlmClient` 벤더 중립 추상화 — 실행 기록

> [ROADMAP.md](../ROADMAP.md) 2단계의 실행 기록이다. 이 단계는 **동작 변화가 없다** — 기존 `MyCourseServiceImpl` → `GeminiService` 경로는 그대로 두고, 새 포트는 8단계 스위치 전까지 프로덕션에서 호출되지 않는다.
>
> 결론부터: **포트와 어댑터는 계획대로 나왔지만, 검증 과정에서 재시도 계층이 셋으로 흩어져 있다는 것이 드러났다.** 설정한 3회가 실제 HTTP 요청 6회로 관측됐고, 그중 하나는 429를 "재시도해도 소용없음"으로 분류하고 있었다. 자세한 것은 판정 1·2.
>
> 진행 상황: **2-1 ~ 2-5 완료** (테스트 102개 전부 통과, 앱 기동 확인). **2-6 baseline 재측정은 미실행** — 실제 API 비용이 들어 승인 대기 중이다.

## 설계 결정 — 로드맵에 없던 네 가지

로드맵 2단계는 만들 것만 적혀 있고 "어떻게"는 열려 있었다. 착수 전에 네 가지를 확정했다.

| # | 결정 | 대안을 버린 이유 |
|---|---|---|
| 1 | **측정점을 둘 다 찍는다** (프롬프트지시 판 + json_schema 판, 각각 luna/nano) | 한 점만 찍으면 "모델 교체"와 "구조화 출력" 중 하나가 파이프라인 효과에 섞인다. 아래 "왜 측정점이 둘인가" |
| 2 | **어댑터가 `OpenAiChatModel`을 직접 조립** | auto-config는 API 키를 기동 필수로 만들어 "동작 변화 없음"을 깨고, `baseUrl`을 못 바꿔 WireMock 검증이 불가능해진다 |
| 3 | **절단은 어댑터가 예외로 번역** | 포트 반환 타입을 래퍼로 감싸면 모든 에이전트가 래퍼를 벗겨야 하고, 벤더별로 다른 종료 사유가 포트로 샌다 |
| 4 | **import 스캔 테스트 + 목킹 데모** | ArchUnit은 규칙 하나 때문에 의존성을 늘린다 |

### 왜 측정점이 둘인가

Gemini 25.6%에서 8단계 후 값까지, 실제로 바뀌는 변수는 셋이다.

```
V1 모델        gemini-2.5-flash  →  OpenAI
V2 출력 강제   프롬프트에 글로    →  response_format.json_schema (디코딩 레벨)
V3 구조        단일 호출          →  5단계 파이프라인
```

중간점을 하나만 찍으면 어느 쪽이든 두 변수가 뭉친다.

| 중간점 | 앞 구간 | 뒤 구간 |
|---|---|---|
| (OpenAI, 프롬프트지시, 단일) | V1 | **V2+V3** |
| (OpenAI, json_schema, 단일) | **V1+V2** | V3 |
| **둘 다** | V1 / V2 / V3 각각 | — |

덤이 하나 붙는다. [BASELINE-ARTIFACT-ANALYSIS.md](../BASELINE-ARTIFACT-ANALYSIS.md) 판정 3은 "파싱 실패 5건이 전부 절단이라 **구조화 출력으로는 안 사라진다**"고 결론냈는데, 이건 아직 추론이다. V2를 켠 판과 끈 판을 둘 다 재면 그 주장이 숫자로 확인된다.

모델 축도 붙는다 — 0단계가 `gpt-5.6-luna`의 한국어 지역 지식을 확인하지 못한 채 Curator에 배정했으므로(ROADMAP 0-4), luna/nano를 같은 조건으로 재서 확정한다.

---

## 판정 1 — 재시도가 세 계층에 흩어져 있었다 ★

WireMock으로 429를 계속 돌려주게 두고 `llm.retry.attempts: 3`으로 부르면 **실제 HTTP 요청이 6건** 나갔다.

원인은 우리 `LlmRetryExecutor` 위아래로 재시도가 두 개 더 있었기 때문이다.

| 계층 | 무엇이 | 기본 동작 |
|---|---|---|
| Spring AI `OpenAiChatModel` | 자체 `RetryTemplate` | 다중 시도 + 자체 백오프 |
| **Apache HttpClient 5** | `DefaultHttpRequestRetryStrategy` | **429·503을 1회 더 시도** |
| 우리 코드 | `LlmRetryExecutor` | `llm.retry.attempts` |

Apache가 끼어든 경위가 특히 고약하다. `ClientHttpRequestFactoryBuilder.detect()`가 클래스패스를 보고 HTTP 스택을 고르는데, **httpclient5는 이 레포에 선언조차 되지 않은 전이 의존성**이다(`compileClasspath`에는 없고 `runtimeClasspath`에만 있다). 즉 아무도 의도하지 않았는데 HTTP 계층과 재시도 정책이 그쪽으로 정해져 있었다.

**대응**

- `OpenAiChatModel.retryTemplate`을 `maxAttempts(1)`로 고정
- `ClientHttpRequestFactoryBuilder.reactor()`로 HTTP 스택을 못박음. reactor-netty를 고른 이유는 ① 상태 코드 기반 자동 재시도가 없고 ② 이 레포의 다른 외부 호출(`KakaoConfig`)이 이미 reactor-netty라 스택이 하나로 모이기 때문이다
- JDK HttpClient(`jdk()`)도 후보였으나 WireMock과의 HTTP/2 협상에서 깨져 배제했다

결과적으로 **재시도 정책이 `llm.retry` 한 곳에만 존재한다.** `doesNotDoubleRetry` 테스트가 이 성질을 고정한다 — 요청 수가 정확히 `attempts`와 같은지 센다.

> 이 발견은 `max-concurrent-calls` 초기값(2)의 의미와도 직결된다. 세마포어로 동시 호출을 2로 묶어도 각 호출이 뒤에서 몰래 2배로 시도하고 있었다면, rate limit 방어라는 목적 자체가 성립하지 않는다.

---

## 판정 2 — Spring AI는 429를 "재시도 무의미"로 분류한다 ★

위 6회를 3회로 줄이고 나니 이번엔 재시도가 **아예 안 걸렸다.** 예외를 열어보니 이랬다.

```
org.springframework.ai.retry.NonTransientAiException: 429 -
    at org.springframework.ai.retry.RetryUtils$1.handleError(RetryUtils.java:73)
```

`RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER`는 **4xx 전체를 `NonTransientAiException`**(다시 시도해도 소용없음), 5xx만 `TransientAiException`으로 나눈다. 429는 4xx라 여기 걸린다 — 이름이 뜻하는 것과 실제가 정반대다.

**대응.** 메시지 문자열에서 `"429"`를 찾는 방법(기존 벤치마크 하네스가 쓰던 방식)은 벤더가 문구를 바꾸면 조용히 깨지므로 택하지 않았다. 대신 **상태 코드를 보존하는 `responseErrorHandler`를 주입해 분류를 직접 소유한다.**

```java
boolean isRetriable() {
    return status == 429 || status == 408 || status >= 500;
}
```

400(스키마 오류)·401(키 오류)은 재시도하지 않는다 — 같은 요청을 다시 보내면 같은 답이 오고, 백오프를 태우면 오류를 늦게 볼 뿐이다.

---

## 판정 3 — 설계 문서 §6의 재시도 배치는 그대로 구현할 수 없었다

§6의 표는 의미 계층 재시도를 `LlmResponseParser`의 책임으로 적었다.

| 계층 | 대상 | 구현 |
|---|---|---|
| 전송 | 429 / 5xx | 어댑터가 `llm.retry` 설정으로 지수 백오프 + 지터 |
| 의미 | 200 OK인데 깨진 JSON | `LlmResponseParser` 실패 시 1회만 재시도 |

그런데 **재시도하려면 LLM을 다시 불러야 하고, 파서는 그걸 할 수 없다.** 프롬프트를 재조립해 다시 보내는 것은 어댑터의 일이다.

**대응 — 책임을 셋으로 쪼갰다.**

- `LlmResponseParser` — 순수 변환만. 외부 의존이 없어 완전히 결정론적으로 테스트된다
- `LlmRetryExecutor` — 전송 계층 백오프·지터. **어댑터에서 떼어낸 이유는 테스트**다. 어댑터 안에 두면 백오프 간격을 WireMock 응답 타이밍으로 간접 확인할 수밖에 없는데, `backoffMillis`를 순수 함수로 두면 값으로 직접 검증된다
- `OpenAiLlmClient` — 둘을 조립하고 의미 재시도를 오케스트레이션

`Sleeper`를 갈아끼워 실제로 자지 않으므로, 백오프가 4초까지 늘어나는 시나리오도 밀리초 안에 끝난다.

---

## 판정 4 — 측정을 위해 프로덕션 쪽을 두 곳 보완해야 했다

2-6 하네스를 쓰다 보니 포트에 빠진 것이 드러났다.

**① 절단된 응답의 원문이 버려지고 있었다.** 어댑터가 `finish_reason`만 보고 예외를 던지면서 받은 텍스트를 흘려버렸다. 그런데 절단은 **출력 상한에 닿은 것**과 **스트림이 끊긴 것**으로 갈리고 대응이 다르다. 판정 3이 "정상 응답은 1,400~1,660바이트인데 이 건은 386바이트"라는 비교로 상한이 원인이 아님을 밝힌 것이 정확히 그 예다. → `LlmTruncatedResponseException`이 `partialText`를 들고 있게 했다.

**② 의미 재시도가 측정을 오염시킨다.** 깨진 응답을 한 번 더 물어 고치면 파싱 실패율이 "재시도 후" 값이 되는데, Gemini 측정값(16.7%)에는 그런 보정이 없었다. → `llm.retry.semantic-attempts`로 빼서 baseline은 1로 내려 잰다. 설계값 2는 프로덕션 기본값으로 유지한다.

---

## 판정 5 — 하네스가 뭉쳐 세던 것을 넷으로 갈랐다

기존 하네스는 호출 실패와 파싱 실패를 `parseSuccess` 한 칸에 뭉쳐 넣고 있었다. 그래서 "파싱 실패율 28.6%"가 **호출이 14건만 성공한 배치 기준**이라는 사실이 몇 달 뒤 재분석에서야 드러났다(전체 30요청 기준은 16.7%).

결말을 넷으로 나눴다.

| 결말 | 의미 | 파싱 실패율의 분자인가 |
|---|---|---|
| `OK` | DTO로 역직렬화 성공 | — |
| `CALL_FAILED` | 재시도 소진(429/5xx/타임아웃) | **아니다.** 응답 자체가 없다 |
| `TRUNCATED` | 응답이 끝까지 오지 않음 | 맞다. 단 구조화 출력으로 막히지 않는다 |
| `PARSE_FAILED` | 응답은 왔는데 스키마 위반 | 맞다. 구조화 출력이 없애야 할 바로 그 실패 |

리포트가 **두 분모를 모두 출력**한다(전체 요청 기준 / 호출 성공분 기준). 로드맵이 요구한 "재측정 시 분모를 반드시 명시한다"를 코드로 강제한 것이다.

`finishReason`과 응답 바이트 수도 함께 기록하고, **성공 응답의 원본까지 남긴다** — 정상 응답의 크기 분포가 있어야 절단된 건이 이상한지 판단할 수 있다.

---

## 비교 가능성을 위해 건드리지 않은 것

2-6이 의미를 가지려면 같은 자로 재야 한다.

- 입력 세트 (지역 10 × 키워드셋 3, 3일 고정)
- `KakaoLocalClient.score()` 리플렉션 호출과 점수 밴드 경계
- 층화 추출 시드 42
- **프롬프트 95줄 원문** — json_schema 판에서도 스키마 구간을 빼지 않는다. 빼면 V2 외에 프롬프트까지 변수가 되어 측정이 무의미해진다(프롬프트 슬림화는 6-6의 일이다)

프롬프트는 복사하지 않고 `GeminiService.buildPrompt()`를 그대로 호출한다. 하네스가 원문을 복사해 가면 drift가 생겨 "모델 교체 효과"를 재지 못한다 — `KakaoConfig.buildKakaoWebClient`가 같은 이유로 만들어진 선례다. **프롬프트 본문은 한 글자도 바뀌지 않았다**(구조만 메서드로 분리).

---

## 이 저장소 최초의 `@ConfigurationProperties`

지금까지 모든 설정이 `@Value` 필드 주입이었다. 여기서 방식을 바꾼 것은 취향이 아니라 **구조 때문**이다.

- `agents.planner.model`처럼 **2단계 중첩 + 키가 열린 맵**은 `@Value`로 표현 자체가 안 된다
- `@Validated`가 모델 ID 오타를 **기동 시점에** 잡는다. 요청 30건을 날리고 나서 아는 것과 부팅이 실패하는 것의 차이다
- `agents`를 `Map<String, Agent>`로 두면 `LlmCall.agentName`이 그대로 조회 키가 된다

바인딩 테스트는 **픽스처가 아니라 배포되는 `application.yml` 원본**을 읽는다. 확인하려는 것이 "record가 바인딩 가능한 모양인가"가 아니라 "우리가 실제로 배포하는 설정이 유효한가"이기 때문이다.

---

## 포트 격리를 테스트로 강제한다

설계 문서 §6은 "에이전트 코드가 벤더 SDK 타입을 한 개도 import하지 않는다"를 이 작업의 성과로 내세운다. 그런데 **주장만으로는 지켜지지 않는다** — 6~9단계에서 에이전트를 만들다 보면 `OpenAiChatOptions` 하나만 잠깐 쓰고 싶은 순간이 온다.

- `LlmPortIsolationTest` — `global/ai` 아래에서 `openai/`를 뺀 모든 소스에 벤더 import가 없는지 검사한다. **어댑터가 실제로 벤더 SDK를 쓴다는 것도 함께 단언**해, 검사기가 헛돌아서 통과한 것이 아님을 보인다
- `LlmClientMockingDemoTest` — 6단계 에이전트의 대역을 목으로 검증한다. `com.google.genai.Client`가 `public final`이라 목킹이 불가능했던 것이 포트의 유일한 근거였으므로, **이 테스트가 초록불인 것 자체가 2단계의 성과다**

어댑터도 벤더 예외를 전부 포트 예외로 번역한다. 하나라도 새면 위쪽 파이프라인이 그걸 잡으려고 Spring AI를 import하게 되고, 그 순간 포트가 무의미해진다. 400 응답조차 `LlmTransportException`으로 나오는지 테스트가 확인한다.

---

## 검증 기록

**테스트** — 102개 전부 통과 (2단계에서 24개 추가).

| 테스트 | 확인하는 것 |
|---|---|
| `AiLlmPropertiesTest` (5) | 배포되는 yml이 검증을 통과해 바인딩되는가, 키 없이도 되는가 |
| `LlmResponseParserTest` (3) | 순수 변환, 실패 시 원문 보존 |
| `LlmRetryExecutorTest` (6) | 백오프·지터 값, 재시도 소진, 비대상 예외 통과 |
| `OpenAiLlmClientTest` (11) | 요청 본문 형태, 실패 번역, 재시도 2계층, 동시 호출 게이트 |
| `LlmPortIsolationTest` (2) | 벤더 SDK 유출 |
| `LlmClientMockingDemoTest` (3) | 포트 목킹 가능성 |

**기동 확인** (로드맵 원칙: 컴파일 성공으로 끝내지 않는다)

```
LLM 어댑터 활성화: provider=openai, timeoutMs=20000, maxConcurrentCalls=2,
  agents=[planner=gpt-5.6-luna(t=0.7), curator=gpt-5.6-luna(t=0.9), place-profile=gpt-5-nano(t=0.2)]
Started YourtripApplication in 13.738 seconds
```

- **`OPENAI_API_KEY` 없이 기동됐다** — 결정 2가 지켜졌다는 증거다. `api-key: ${OPENAI_API_KEY:}`의 빈 기본값과 `spring.ai.model.chat: none` 유지가 그 조건이다
- 어댑터가 조건부 빈이라 기동 성공만으로는 활성 여부가 드러나지 않아, **활성 provider와 agent별 모델을 기동 로그에 남기게 했다**(운영에서도 설정 파일을 열지 않고 확인된다). API 키는 찍지 않는다
- 헬스 UP, 기동 중 ERROR 0건
- AI 코스 생성 경로는 **E2E 검증 대상이 아니다**(로드맵 원칙은 1·8·9단계만 지정). `domain/`과 `global/gemini/`의 변경이 0줄인 것으로 무변경을 확인했다

**기동 중 잡은 결함.** `LlmRetryExecutor`와 `OpenAiLlmClient`가 테스트용 생성자를 하나씩 더 갖고 있는데 주입 대상을 표시하지 않아, Spring이 기본 생성자를 찾다가 컨텍스트가 통째로 깨졌다. 단위 테스트만 돌렸다면 못 봤을 결함이다.

---

## 커밋

| 커밋 | 항목 |
|---|---|
| `dcd52e2` | 2-1 포트 + `LlmCall` + 예외 계층 |
| `5c1b93c` | 2-3 `AiLlmProperties` + 설정 외부화 |
| `4ce0a21` | 2-4 `LlmResponseParser` + 전송 계층 재시도 |
| `e196a8e` | 2-2 `OpenAiLlmClient` 어댑터 (숨은 재시도 2개 제거 포함) |
| `f35a2dc` | 2-5 포트 격리 테스트 |
| `d1cbc60` | 기동 로그 |
| `17d099e` | 절단 원문 보존 + 의미 재시도 설정화 |
| `05f39b1` | 2-6 하네스 교체 |

구현 순서를 로드맵 번호와 다르게 가져갔다 — 어댑터(2-2)가 `AiLlmProperties`(2-3)와 `LlmResponseParser`(2-4)를 생성자로 받으므로 의존 방향대로 2-3 → 2-4 → 2-2 순서로 만들었다.

---

## 남은 작업 — 2-6 baseline 재측정

**실행 전 승인이 필요하다**(로드맵 "적용 원칙": 외부 API를 실제로 호출하는 작업은 비용과 쿼터를 소모한다).

- 규모: 30요청 × 모델 2종 × 출력강제 2종 = **120요청**
- 예상: 약 25~35분, $0.15 이하
- **`.env`에 `OPENAI_API_KEY`가 없다** — 현재 이 워크트리의 `.env`에는 `GEMINI_API_KEY`만 있다. 키를 추가해야 실행된다(없으면 `assumeTrue`로 스킵된다)

```bash
BASELINE_MODEL=luna BASELINE_SCHEMA_MODE=prompt ./gradlew benchmarkTest --tests '*AiHallucinationBaselineTest*' --rerun
```

네 조합을 순차 실행하고, 산출물은 `results/hallucination-baseline-openai-{model}-{mode}-{ts}.csv`로 축이 파일명에 박힌다(`results/`는 `.gitignore` 대상이라 이름이 유일한 라벨이다).

측정 후 채울 것:

- 이 문서에 4점 집계표 (환각률 / JSON 실패율 두 분모 / 절단 상세)
- [ROADMAP.md](../ROADMAP.md) 성공 기준 표의 "OpenAI 단일 호출" 행
- **Curator 모델 확정** (luna vs nano 환각률 비교 — 0단계가 2-6에 넘긴 숙제)
- **판정 3 검증** — 구조화 출력을 켰을 때 절단이 줄지 않는다면 판정 3이 옳았던 것이다
- OpenAI RPM/TPM 티어 — 429 빈도로 `max-concurrent-calls` 초기값 2를 재검토

## 참고 문서

- [ROADMAP.md](../ROADMAP.md) — 2단계 체크리스트
- [TASK-AI-MULTI-AGENT.md](../TASK-AI-MULTI-AGENT.md) §6 — 포트 설계의 근거
- [BASELINE-ARTIFACT-ANALYSIS.md](../BASELINE-ARTIFACT-ANALYSIS.md) 판정 3·4 — 절단 원인과 환각률 정의
- [STEP-0-prerequisites.md](STEP-0-prerequisites.md) — Spring AI 검증, 모델 배치
- [STEP-1-existing-defects.md](STEP-1-existing-defects.md) — 1단계 실행 기록
