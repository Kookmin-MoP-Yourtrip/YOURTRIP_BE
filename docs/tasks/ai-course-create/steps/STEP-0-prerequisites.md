# STEP-0. 사전 준비 — 착수 전 검증과 결정

> [ROADMAP.md](../ROADMAP.md) 0단계의 실행 기록이다. 이 단계는 동작 변화가 없지만, **여기서 나온 판정이 2·4단계의 설계를 바꾸기 때문에** 맨 앞에 있다.
>
> 결론부터: **설계의 핵심 전제(스키마를 디코딩 레벨에서 강제)는 성립한다.** 다만 로드맵을 쓸 때 몰랐던 제약 하나가 드러나 Spring AI 버전 선택이 바뀌었고, 의존성 추가만으로 애플리케이션 기동이 깨지는 함정도 하나 밟았다. 둘 다 아래에 기록한다.
>
> 진행 상황: **0-3·0-4·0-5·0-6·0-7 완료. 0-1·0-2는 키 발급 대기.**

## 왜 0단계를 새로 만들었나

설계 문서 §13의 도입 순서는 1단계(기존 결함 수정)부터 시작한다. 하지만 로드맵을 쓰면서 코드를 확인해보니 **착수 자체가 막히는 선행 조건**이 여럿 있었다.

- 네이버 API 키 미보유 → 4단계 전체가 블록된다
- `src/test/resources` 디렉터리 자체가 없고 스텁 서버도 없어, 5단계 이후 결정론적 통합 테스트가 원천 불가능하다
- Spring AI가 스키마를 어떻게 전송하는지 미검증 → 이게 아니면 2단계 설계가 통째로 바뀐다
- `build.gradle`에 자바 버전 선언이 없어 컴파일 타깃이 빌드하는 사람의 로컬 JDK에 좌우된다

---

## 판정 1 — Spring AI 2.0은 쓸 수 없다

**Spring AI 2.0.x는 Spring Boot 4.0/4.1을 요구한다.** 이 레포는 Spring Boot 3.5.7이다.

| 계열 | 지원 Spring Boot | 이 레포 적용 |
|---|---|---|
| Spring AI 2.0.x | 4.0.x / 4.1.x | **불가** |
| **Spring AI 1.1.x** (최신 1.1.8, 2026-06-12) | 3.4.x / 3.5.x / 4.0 | **채택** |

**기능 손해가 없다는 것이 채택 근거다.** 이번 설계가 Spring AI에 요구하는 것은 "JSON 스키마를 `response_format.json_schema` API 필드로 전송" 하나뿐인데, 이건 1.1.x에 이미 들어 있다(아래 판정 2에서 실물 확인).

### Spring Boot 4 이전을 별도 작업으로 분리한 이유

Spring Boot 3.5는 2026-06-30에 오픈소스 EOL에 도달했으므로 언젠가는 해야 할 일이다. 다만 이번 로드맵에 끼워 넣지 않는다.

레포를 실제로 훑어 확인한 작업량은 이렇다.

| 항목 | 규모 |
|---|---|
| Jackson 3 전환 (`com.fasterxml.jackson` → `tools.jackson`) | **11개 파일** (main 7 / test 4) |
| `@MockBean` → `@MockitoBean` | 3곳 (`UploadCourseControllerE2ETest`). **이미 컴파일 경고가 뜨고 있다** |
| springdoc-openapi | `2.6.0` → `3.1.0` |
| spring-dotenv | `springboot4-dotenv` 모듈로 교체 |
| Spring Security 7 | `SecurityConfig` 필터 체인 재검토 |

**코드 수정량 자체는 크지 않다. 문제는 검증 비용이다.** 깨지는 곳 대부분이 런타임에만 드러나는 영역(캐시 직렬화, JWT 필터, Security 체인, Swagger)인데 이 레포의 통합 테스트는 E2E 1개뿐이다. 특히 `RedisConfig`의 `GenericJackson2JsonRedisSerializer`는 **이 레포가 이미 타입 정보 유실 버그를 겪은 지점**이고([TASK-4.md](../../TASK-4.md)), 직렬화 포맷이 바뀌면 배포 시 캐시가 전량 무효화돼 [CACHING-ROADMAP.md](../../../CACHING-ROADMAP.md) 설계 원칙 4번이 방어하려던 콜드 스타트 스탬피드가 재현된다.

AI 파이프라인과 무관한 이 리스크를 0단계 앞에 쌓을 이유가 없다.

### 남는 리스크 — 버전 스큐

Spring AI 1.1.8이 요구하는 버전이 Spring Boot 3.5.7의 관리 버전으로 **끌어내려진다.**

| 라이브러리 | Spring AI 1.1.8 요구 | 실제 해석 |
|---|---|---|
| spring-core / web / context | 6.2.19 | **6.2.12** |
| jackson-databind | 2.21.4 | **2.19.2** |

지금까지는 문제가 관측되지 않았다(판정 2의 검증 테스트가 실제 HTTP 요청 직렬화까지 통과했다). 다만 **Spring AI가 6.2.13~6.2.19나 Jackson 2.20~2.21에서 추가된 API를 쓰는 경로에 들어가면 `NoSuchMethodError`가 날 수 있다.** 2단계에서 어댑터를 실제로 구현할 때 이 증상이 나오면 1.1.x 중 더 낮은 버전으로 내리는 것이 1차 대응이다.

---

## 판정 2 — 스키마는 프롬프트가 아니라 API 필드로 나간다 ★

이 단계에서 가장 중요한 검증이다. 설계 문서 §6·§7은 **"JSON 스키마를 프롬프트 텍스트가 아니라 디코딩 레벨에서 강제한다"**는 전제 위에 서 있고, 그 위에 세 가지 결론이 얹혀 있다.

- 프롬프트에서 스키마·출력예시 약 36줄이 사라진다 (§7)
- 파싱 실패율이 near-zero가 된다 (§6)
- "의미 재시도"를 1회로 제한할 수 있다 (§6)

전제가 깨지면 Spring AI를 버리고 OpenAI 공식 SDK로 어댑터를 짜야 했다.

### 검증 방법

키도 비용도 필요 없는 방법을 택했다 — **WireMock으로 요청 본문을 가로채 무엇이 어디에 실렸는지 직접 본다.**

`src/test/java/backend/yourtrip/global/ai/SpringAiStructuredOutputVerificationTest.java`

```bash
./gradlew test --tests '*SpringAiStructuredOutput*' --rerun
```

### 결과 — 통과

실제로 전송된 요청 본문이다.

```json
{
  "messages" : [ {
    "content" : "경주 3일 코스의 컨셉과 제목을 정해줘",
    "role" : "user"
  } ],
  "model" : "gpt-5-nano",
  "response_format" : {
    "type" : "json_schema",
    "json_schema" : {
      "name" : "planner_output",
      "schema" : {
        "additionalProperties" : false,
        "type" : "object",
        "properties" : {
          "title" : { "type" : "string" },
          "concept" : { "type" : "string" }
        },
        "required" : [ "title", "concept" ]
      },
      "strict" : true
    }
  },
  "stream" : false
}
```

**`messages[0].content`에는 사용자 프롬프트만 있다 — 스키마도 형식 지시문도 한 글자 없다.** 스키마는 전부 `response_format.json_schema`에 `strict: true`로 실렸다.

테스트가 단언하는 것도 "응답이 잘 오는가"가 아니라 이 두 가지다.
1. 스키마가 `response_format.json_schema.schema`에 있고 `strict`가 켜져 있는가
2. 스키마에만 등장하는 표식(`additionalProperties`)이 `messages[].content`에 **없는가**

초록불만 남기지 않고 전송된 본문을 콘솔에 출력하게 해뒀다. 이 문서가 인용하는 근거를 언제든 재현할 수 있어야 하기 때문이다.

### 아직 확인하지 못한 것 — 0-3b

실제 OpenAI 호출로 확인해야 하는 항목이 둘 남아 있다. 같은 테스트 클래스에 `@Tag("benchmark")`로 들어 있고, **키가 없으면 명확한 메시지와 함께 스킵된다**(현재 상태).

```bash
./gradlew benchmarkTest --tests '*SpringAiStructuredOutput*' --rerun
```

- 선택한 모델이 strict `json_schema`를 실제로 지원하는가 — 지원하지 않으면 그게 **모델 선택의 하한선**이 된다
- **최상위 JSON 배열 제약** — OpenAI 구조화 출력은 루트가 배열인 스키마를 받지 않는 것으로 알려져 있다. Curator 응답(`slots[]`)이 여기 해당하므로 객체로 감싸야 하는지 실물로 확정해야 한다

---

## 판정 3 — starter를 넣는 것만으로 애플리케이션이 죽는다

의존성만 추가하고 테스트를 돌렸더니 **컨텍스트 로드가 통째로 실패했다.**

```
BeanCreationException: Error creating bean with name 'openAiAudioSpeechModel'
  ...
Caused by: java.lang.IllegalArgumentException:
  OpenAI API key must be set. Use the connection property: spring.ai.openai.api-key
```

`spring-ai-starter-model-openai`는 chat 하나가 아니라 **OpenAI의 모든 모델 타입(embedding, image, moderation, audio speech/transcription)을 자동 구성**하고, 그 전부가 기동 시점에 API 키를 요구한다. 우리가 쓰는 건 chat뿐인데 쓰지도 않는 TTS 모델 때문에 앱이 뜨지 못한 것이다.

`application.yml`에 이렇게 조치했다.

```yaml
spring:
  ai:
    model:
      embedding: none
      image: none
      moderation: none
      audio:
        speech: none
        transcription: none
      chat: none   # ← 2단계에서 openai 로 바꾼다
```

**chat까지 끈 것이 의도적인 선택이다.** 0단계는 Spring AI를 *검증*만 하는 단계이고 실제로 쓰기 시작하는 것은 2단계다. 여기서 chat을 켜두면 `OPENAI_API_KEY`가 없는 환경에서 `./gradlew bootRun`이 실패하는데, 그러면 **아직 AI 경로를 건드리지도 않은 이 단계가 "동작 변화 없음" 약속을 깨게 된다.** 검증 테스트는 Spring 컨텍스트 없이 모델을 직접 조립하므로 chat이 꺼져 있어도 영향받지 않는다.

---

## 결정 — 모델 배치

세 에이전트 모두 **추론이 필요한 작업이 아니다.** Planner는 권역 배분(얕은 제약 만족), Curator는 지식 회상, PlaceProfile은 닫힌 태그 집합 분류다. 그래서 선택 기준을 "추론 난이도"가 아니라 **"틀렸을 때의 파급 ÷ 토큰량"**으로 잡았다.

| 에이전트 | 모델 | 단가(입/출, 1M) | 요청당 | 비중 | 근거 |
|---|---|---|---|---|---|
| Planner | `gpt-5.6-luna` | $0.20 / $1.20 | $0.00056 | 19% | **파급 최대, 토큰 최소.** `area`가 Curator 컨텍스트이자 카카오 검색 접두사라 권역이 틀리면 그 아래가 전부 오염된다. 호출은 1회뿐이고 출력도 350토큰이라 상위 모델을 써도 싸다 |
| Curator | `gpt-5.6-luna` | $0.20 / $1.20 | $0.00186 | 62% | 환각률에 직결. **최신 세대의 경량 티어**라 지역 상호명 지식의 폭·최신성에서 유리하다 |
| PlaceProfile | `gpt-5-nano` | $0.05 / $0.40 | $0.00055 | 19% | 닫힌 태그 분류라 세대 이점이 작은데 **입력 토큰의 76%**를 차지한다 |
| | | | **$0.0030 (약 4.2원)** | | 3일 코스 1회 생성 기준 |

**`gpt-5.6-luna`가 스윗스팟인 이유**: 지역 상호명 환각은 추론력이 아니라 학습 데이터의 폭과 최신성에 좌우되는데, luna는 최신 세대(5.6)의 경량 티어이면서 구세대 nano급 가격대에 있다. 지식이 필요한 두 에이전트에 세대 이점을 거의 공짜로 주는 선택이다.

### 미확인 — 2단계에서 실측으로 확정한다

`gpt-5.6-luna`의 **한국어 지역 지식 품질은 직접 확인하지 않았다.** 그래서 2-6 baseline 재측정에서 **luna와 nano 두 모델로 각각 30요청을 돌려 환각률을 비교**한다. 추가 비용은 30원 남짓이고, 그 결과가 Curator 모델을 감이 아니라 데이터로 확정해준다.

PlaceProfile을 nano로 내린 선택이 틀렸는지는 설계 문서 §12에 이미 있는 `ai.profile.traits{count}` 메트릭이 알려준다 — 추출률이 낮게 나오면 그때 올린다.

### 설계 문서 §11의 전제 하나가 틀렸다

§11은 "입력 토큰이 9배가 되는 것이 이 설계의 최대 비용이고, 전부 4층(PlaceProfile)에서 나온다"고 했다. **토큰 수로는 맞지만 금액으로는 아니다.**

| | 토큰 비중 | 금액 비중 |
|---|---|---|
| PlaceProfile 입력 | 76% | **19%** |
| Curator 출력 | 71% (출력 중) | **62%** |

출력 단가가 입력의 8배라 두 비중이 어긋난다. **금액을 지배하는 것은 PlaceProfile의 입력이 아니라 Curator의 출력이다.** 이는 §11의 "조건부 확장으로 약 40% 절감" 논의 전제도 바꾼다 — ATTRACTION 슬롯을 PlaceProfile 대상에서 빼는 것으로 아낄 수 있는 금액은 생각보다 훨씬 작다.

---

## 쿼터와 과금

> **[정정] 네이버 검색 API가 NAVER API HUB(NCP)로 옮겨갔다.** 이 문서의 초안은 ~~developers.naver.com에서 발급하고 무료 쿼터가 일 25,000회~~라고 적었으나, 현재 검색 API는 네이버 클라우드 플랫폼의 **NAVER API HUB**로 제공되며 **종량제(pay-as-you-go)** 다. 무료 쿼터 전제가 사라졌다.

| API | 한도/과금 | 요청당 호출 | 비고 |
|---|---|---|---|
| **네이버 검색 (API HUB)** | **종량제 — 단가 미확인** | ~35 | 콘솔에서 일별/월별 이용 한도를 반드시 설정 |
| 카카오 로컬 | 100,000/일 (API 종류별) | ~45 | 약 2,222 요청/일 |
| OpenAI | 계정 티어별 RPM/TPM | 5 | 미확인 — 키 발급 후 확인 |

### 네이버 이관이 설계에 미치는 영향

설계 문서 §11은 이렇게 단언한다.

> **3층(인기도)은 비용 증가가 0이다** — 무료 쿼터 안에서 동작하고 LLM 토큰을 한 톨도 늘리지 않는다. **이 설계에서 가장 비용 효율이 좋은 품질 개선 수단이다.**

**무료 쿼터 전제가 사라졌으므로 이 문장은 그대로 둘 수 없다.** 규모를 보면 왜 중요한지 분명하다 — 코스 1건에 네이버 호출이 약 35회이고 일 1,000요청이면 **월 약 105만 회**다. LLM 비용이 요청당 4.2원이므로, **네이버 단가가 호출당 0.12원만 넘어도 네이버가 LLM보다 비싸진다.**

따라서 두 가지가 따라온다.

1. **§11의 "가장 비용 효율이 좋은 수단"이라는 평가가 단가에 따라 뒤집힐 수 있다.** 단가 확인 후 재작성 대상이다.
2. **10단계 Redis 캐싱(`naver:blog:{sha1}`, TTL 7일)이 "지연↓"에서 "비용 절감"으로 격상된다.** 경주·제주·부산에 요청이 몰리는 특성상 캐시 히트율이 높아 절감폭이 클 것으로 보인다.

네이버는 hard fail이 아니라 fail-open이므로(설계 §9), 한도를 넘겨도 코스 생성 자체는 계속된다 — 인기도·속성 신호만 빠진다. 과금 사고를 막는 안전장치가 설계에 이미 들어 있는 셈이다.

### NAVER API HUB 발급 절차와 스펙

| 항목 | 값 |
|---|---|
| 콘솔 경로 | Menu → All Services → AI·NAVER API → Application → [Application 등록] |
| 등록 정보 | 앱 이름(최대 20자) + 사용할 서비스에서 **검색(Search)** 선택 + 서비스 환경(Web URL / Android 패키지 / iOS Bundle ID 각 최대 10개) |
| 인증 정보 확인 | Application 목록 → **[인증 정보]** 버튼 (Client Secret은 [재발급] 가능) |
| Base URL | **`https://naverapihub.apigw.ntruss.com`** |
| 인증 헤더 | **`X-NCP-APIGW-API-KEY-ID`**(Client ID) / **`X-NCP-APIGW-API-KEY`**(Client Secret) |
| 제공 검색 종류 | 뉴스·백과사전·**블로그**·웹문서·이미지·지식iN·지역·카페글 + 성인 검색어 판별·오타 변환 |

구 방식(`openapi.naver.com` + `X-Naver-Client-Id`/`X-Naver-Client-Secret`)과 **엔드포인트도 헤더도 다르다.** 4단계에서 `NaverBlogClient`를 짤 때 이 스펙을 기준으로 한다.

### 4단계 착수 전 실호출로 확정할 것

- **블로그 검색의 정확한 경로** — 새 Base URL 아래에서 `/v1/search/blog.json`이 유지되는지
- **응답 스키마 유지 여부** — 설계의 3·4층은 `total`·`postdate`·`title`·`description`에 의존한다. 바뀌었다면 두 층의 설계를 손봐야 한다
- **검색 API 호출 단가와 무료 제공량 유무** — 위 두 결론(§11 재평가, 캐싱 우선순위)이 여기 걸려 있다

OpenAI RPM/TPM은 `llm.max-concurrent-calls` 초기값의 근거가 되므로 키 발급 후 확인이 필요하다.

---

## 남은 작업 — 키 발급 (사용자)

내가 대신할 수 없는 항목이다. 두 키 모두 `.env`에만 넣고 커밋하지 않는다(`.gitignore:40`으로 제외돼 있다). 레포에 추가한 것은 `.env.example`의 값 없는 자리표시자뿐이다.

- **`OPENAI_API_KEY`** — platform.openai.com에서 발급 + 결제 수단 등록. 발급 후 **계정 RPM/TPM 티어**도 함께 확인. 이 키가 있으면 0-3b 검증이 바로 돌아간다
- **`NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET`** — **NCP 콘솔의 NAVER API HUB**에서 발급한다(위 "NAVER API HUB 발급 절차와 스펙" 표 참고). developers.naver.com이 아니다. **종량제이므로 콘솔에서 일별/월별 이용 한도를 함께 걸어둘 것.** 4단계의 블로커다

---

## 검증 기록

| 항목 | 명령 | 결과 |
|---|---|---|
| Java 21 고정 | `./gradlew compileJava` | 바이트코드 major version **65** 확인 |
| 회귀 없음 | `./gradlew test` | **56 tests, 0 failures** (0단계 시작 시점 55 + 검증 테스트 1) |
| WireMock 셰이딩 | `./gradlew dependencies --configuration testRuntimeClasspath` | transitive 의존성 **0개**, `jackson-databind` 해석 결과 **변화 없음** |
| Spring AI 전송 방식 | `./gradlew test --tests '*SpringAiStructuredOutput*'` | **통과** — 판정 2 참고 |
| 실 API 검증 | `./gradlew benchmarkTest --tests '*SpringAiStructuredOutput*'` | **스킵** (키 대기) |

## 참고 문서

- [ROADMAP.md](../ROADMAP.md) — 전체 단계 체크리스트
- [TASK-AI-MULTI-AGENT.md](../TASK-AI-MULTI-AGENT.md) — 설계 근거. §6(벤더 중립 추상화)·§11(비용)이 이 문서의 판정으로 갱신됐다
- [TASK-AI-HALLUCINATION-BASELINE.md](../TASK-AI-HALLUCINATION-BASELINE.md) — 2단계에서 재사용할 측정 하네스
