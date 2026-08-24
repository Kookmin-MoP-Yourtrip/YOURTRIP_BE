# STEP-7. `AiCoursePipeline` 오케스트레이터 + 폴백 — 실행 기록

> [ROADMAP.md](../ROADMAP.md) 7단계의 상세 기록. 설계 근거는 [운영 관심사](../design/운영-관심사.md)의
> "부분 실패 전략 — degrade, don't fail"과 "병렬화·지연 예산"이 소유하고, 이 문서는 **구현하며 내린
> 결정과 그 근거**만 담는다.
>
> **동작 변화 없음.** 컨트롤러는 여전히 `GeminiService`를 부른다 — 이 파이프라인으로 가는 경로는
> 8단계(유일한 스위치)가 연결한다.
>
> **진행 상황: 7단계 완료.** 가장 큰 발견은 **설계가 지정한 ErrorCode 다섯 중 셋이 같은 설계의
> degrade 정책에 막혀 발화할 수 없다**는 것이다(판정 1). 그리고 로드맵에 없던 결함을 하나 닫았다 —
> 사용자가 고른 이동수단이 `RouteOptimizer`까지 도달하지 못하고 있었다(판정 8).

## 실행 순서

로드맵의 항목 번호는 식별자이지 실행 순서가 아니다. 의존 관계로 재배열하면 이렇다.

```
[기반]   7-2 AiCourseErrorCode + JSON_TRANSFORMATION_FAILED 정리
         7-5 AiCourseMetrics 에 pipeline.duration · candidate.adopted 추가
[폴백]   7-3 DeterministicCuration (순수 함수) → 단위 테스트
[조립]   7-1 CourseBrief · AiCourseDraft/Day/Place · AiCourseProperties → AiCoursePipeline
         7-4 requireAnyPlace (hard fail / timeout 판정)
[검증]   7-6 단위 테스트 → 스텁 통합 확장 → 기동 + /actuator/prometheus → 문서
```

**ErrorCode와 메트릭을 먼저 만든 이유는 파이프라인이 둘 다 소비하기 때문**이다. 순수 함수를 조립보다
앞에 두는 것은 5·6단계가 잡은 순서(외부 의존이 없는 것부터 결정론적으로 검증)를 그대로 따랐다.

**착수 시점에 확정한 것 넷** — 카카오 카테고리 검색 폴백은 보류한다 / ErrorCode는 실제로 발화하는
둘만 만든다 / `ai.candidate.adopted`를 7-5에 함께 넣는다 / 예산 기본값은 30초로 두고 설정으로 뺀다.

---

## 판정 1 — 설계가 지정한 ErrorCode 다섯 중 둘만 만든다 ★★★ (7-2)

설계의 "신규 `AiCourseErrorCode`" 절은 다섯 개를 열거했다. 그런데 **같은 문서의 degrade 표가 그중
셋의 발화 경로를 스스로 막는다.**

| 코드 | 설계가 상정한 발화 지점 | 실제 |
|---|---|---|
| `AI_GROUNDING_FAILED` | 전 day 장소 0개 | **발화한다** — 유일한 hard fail |
| `AI_COURSE_TIMEOUT` | 데드라인 만료 | **발화한다** — 판정 2의 조건에서 |
| `AI_PLAN_FAILED` | Planner 실패 | 같은 표가 "실패 시 결정론적 기본 플랜"이라고 규정한다. 기본 플랜을 못 만드는 경우가 없어 **도달 경로가 없다** |
| `AI_RESPONSE_INVALID` | 응답 형식 오류 | 깨진 응답은 어댑터가 의미 재시도까지 소진하고 `LlmResponseException`으로 올리는데, 그걸 받는 두 지점(Planner·Curator)이 **모두 degrade로 끝난다** |
| `AI_COURSE_BUSY` | 세마포어 포화(429) | 아래 |

`AI_COURSE_BUSY`는 두 겹으로 막혀 있다. 첫째, 세마포어 포화는
[`OpenAiLlmClient.acquirePermit`](../../../../src/main/java/backend/yourtrip/global/ai/openai/OpenAiLlmClient.java)에서
**다른 전송 실패와 같은 `LlmTransportException`으로** 나온다 — 타입으로 구분할 수 없고,
`attempts=0`도 데드라인 만료와 겹쳐 판별 키가 되지 못한다. 둘째, **구분에 성공하더라도** 그 예외는
Planner의 `catch (LlmException)`이나 Curator의 `.handle(...)`에 먹혀 degrade로 끝난다.

**발화하지 않는 상수를 미리 두지 않는 이유**는 이 enum이 곧 "이 기능이 사용자에게 실패하는 방식의
전부"라는 목록이기 때문이다. 쓰이지 않는 항목이 섞이면 그 목록은 **동작의 기록이 아니라 설계 의도의
기록**으로 바뀌고, 읽는 사람은 429가 실제로 나갈 수 있다고 믿게 된다. 폴백 정책이 바뀌어 정말
필요해지면 그때 한 줄 추가하면 된다.

> 부수 효과 하나를 적어 둔다. `GlobalExceptionHandler`가 응답 `code`를 `Enum#name()`으로 뽑으므로
> **상수 이름이 그대로 공개 API 계약**이 된다. 이름을 나중에 바꾸면 FE의 분기가 깨진다.

---

## 판정 2 — hard fail과 timeout을 한 지점에서 가른다 ★★ (7-4)

판정을 **그라운딩 직후 한 곳**에만 뒀다.

```
전 day 장소 0개?
  ├─ deadline.expired()  → AI_COURSE_TIMEOUT   (504)
  └─ 그 외               → AI_GROUNDING_FAILED (503)
```

**만료를 앞에서 따로 검사하지 않는다.** 스테이지들이 이미 만료를 각자 degrade로 흡수하도록 만들어져
있어(후보 공급은 모은 만큼만, `PlaceUrlEnricher`는 통째로 스킵), **예산이 다 됐어도 장소가 남아 있으면
그 코스는 유효하다.** 진입부에서 `expired()`를 보고 504를 내면 살릴 수 있는 요청을 죽인다 —
**만료가 실제로 해가 된 경우에만** 판정하는 것이 정확하다.

**둘을 뭉치지 않는 이유**는 사용자에게 도달하는 결과가 같아도 운영에서 할 일이 다르기 때문이다.
504가 늘면 예산이나 모델 지연을 봐야 하고, 503이 늘면 네이버·TourAPI·카카오 셋의 상태를 봐야 한다.
같은 코드로 묶으면 대시보드에서 그 둘을 나눌 수 없다 — 5-6이 `no_result`와 `failed`를 갈라야 한다고
한 것과 같은 논지다.

**부분 실패는 통과시킨다.** day 1에 장소가 있고 day 2가 비어도 실패가 아니다(설계 표가 "전 day 장소
0개"만 hard fail로 규정한다). `AiCoursePipelineTest`가 이 경계를 세 케이스로 고정했다.

---

## 판정 3 — 카카오 카테고리 검색 폴백을 보류한다 ★★ (7-3)

설계의 degrade 표는 카테고리 검색 폴백을 두 자리에 요구한다 — ① Curator가 실패했는데 후보 목록마저
비었을 때, ② 슬롯 전멸로 day 장소가 3개 미만일 때. **둘 다 만들지 않았다.**

근거는 5-9 실측이다. **빈 슬롯이 0%**였고, 같은 실측을 근거로 "0건일 때만 카카오" 폴백이 이미 조건
미발동으로 기각된 선례가 있다. 여기에 두 가지가 더 붙는다.

- **신규 외부 호출 경로가 생긴다.** `KakaoLocalClient`에는 카테고리 검색이 없어(`searchPlace`·
  `findBestPlace`·`lookupBestPlace`·`lookupFirstPlace`뿐) 클라이언트부터 손대야 하고, 그 결과를
  `PlaceCandidate`/`GroundedPlace`로 옮기는 변환도 새로 만들어야 한다.
- **폴백이 장애 상황에서 외부 호출을 늘린다.** 이 폴백이 발동하는 조건은 대개 후보 공급이 죽은
  상황인데, 거기서 카카오를 추가로 두드리는 것은 방향이 반대다. 채택한 폴백(후보 목록에서 결정론적
  채움)은 **카카오를 한 번도 부르지 않는다**(판정 4).

**대신 그 자리는 빈 채로 둔다.** 슬롯이 비면 `RouteOptimizer`에 넘길 장소가 하나 줄 뿐이고, 전 day가
0개가 되는 극단에서만 판정 2가 발동한다.

---

## 판정 4 — `DeterministicCuration`이 슬롯 구조를 다시 만들지 않는다 (7-3)

계획 단계에서는 이 순수 함수의 시그니처를 `fill(PlannerPlan, List<CuratedDay>, CandidatePool)`로 잡았다.
Curator가 실패한 day는 슬롯 리스트 자체가 비어 있을 수 있으니 `plan`으로 자리를 재구성해야 한다고
본 것이다. **코드를 읽어 보니 아니었다.**

`CuratedChoiceValidator.validate`는 응답이 `null`이어도 **Planner의 자리 구성 그대로** `CuratedSlot`을
만들고 선택만 비워 둔다. 그 코드의 주석이 직접 지목하고 있다 — *"응답에 없는 자리는 빈 선택으로
남는다(7-3의 결정론적 채움이 그 자리를 메운다)"*. `CuratorAgent.emptyDays`도 같은 검증기를 타므로
**전면 실패 경로에서도 자리는 남는다.**

그래서 시그니처를 `fill(List<CuratedDay>, CandidatePool)`로 줄였다. **`plan`을 받으면 자리 구성이라는
같은 책임을 두 곳이 갖게 되고**, 둘이 어긋나는 순간 어느 쪽이 옳은지 알 수 없어진다. 이 함수는
자리 구성이 아니라 **빈 자리의 내용물**만 소유한다.

채워 넣는 후보는 목록의 상위 3개를 **순서 그대로** 쓴다. 5-8이 이미 사전식으로 정렬해 시드 그룹을
앞에 뒀으므로 여기서 다시 점수를 매기면 정렬 규칙이 두 곳으로 갈린다. `listIndex`는 0-based 리스트
인덱스 그대로여야 하는데, **어긋나도 예외가 나지 않는 종류의 값**이라 테스트로 못 박았다 — 틀리면
`GroundingStage`가 좌표 승계에 실패해 그 후보를 `SUGGESTED`로 강등시키고, 그러면 폴백이 오히려 카카오
호출을 늘린다.

---

## 판정 5 — `ai.candidate.adopted`의 태그 축을 4에서 2로 줄인다 (7-5)

설계의 관측 표는 `{source, modifier, seeded, official}` 4축이었다. **2축(`source`·`modifier`)으로 줄였다.**

`seeded`(네이버 시더에서 왔는가)와 `official`(공식 목록에서 왔는가)은 후보 소스가 카카오를 포함하던
초안의 축이다. 카카오가 후보 공급에서 빠지면서 `SEEDED`는 정의상 네이버, `LISTED`는 정의상 TourAPI가
됐고, **두 축이 `source`가 이미 말하는 것의 재표현**이 됐다. 남겨 두면 시계열 수만 네 배가 되고
(3×2×2×2 = 24), 값이 서로 종속이라 어느 쪽으로 잘라도 같은 그림이 나온다.

`modifier`(`GroundedPlace.matchedModifier != null`)는 남겼다 — **8-7 삭제 로그가 SEO 편승을 재는 축이
정확히 이것**이고, 두 지표가 같은 이름으로 갈려야 나눗셈이 성립한다.

**집계 지점이 파이프라인인 이유**는 분모가 여기서 처음 생기기 때문이다. 후보 공급 시점의 "몇 개를
모았는가"는 채택률의 분모가 아니다 — 5-8이 이 메트릭을 7단계로 미룬 것이 그 이유다.

---

## 판정 6 — 파이프라인 예산을 `llm.*`이 아니라 `ai.course.*`에 둔다 (7-1)

`llm.timeout-ms`(20초) 옆에 두는 안을 버리고 `AiCourseProperties`(prefix `ai.course`)를 새로 만들었다.
**재는 대상이 다르기 때문**이다 — 그쪽은 **호출 1건**의 상한이고 이쪽은 **요청 전체**의 상한이라,
후자가 항상 전자보다 커야 한다는 제약이 있다. 같은 prefix 아래 두면 그 관계가 보이지 않는 채로
둘을 같은 종류의 값으로 튜닝하게 된다.

기본값 **30초**는 설계 지연 예산의 p95 상단(17~24초) 위에 여유를 둔 값이다. **이 값이 p95보다 낮으면
정상 요청이 504가 되고, 너무 높으면 데드라인이 사실상 없는 것과 같아진다** — `CallerRunsPolicy`를
유지한 채 "무한정 느린 성공"만 잘라내는 것이 이 값의 목적이다. 8단계 E2E 실측 뒤 조정한다.

---

## 판정 7 — `JSON_TRANSFORMATION_FAILED`는 (B)만 고친다 (7-2)

로드맵이 지목한 "방향이 정반대인 두 실패"는 이렇다.

- **(A) 응답 역직렬화** — `MyCourseServiceImpl:536`, Gemini 응답 파싱 실패
- **(B) 키워드 직렬화** — `KeywordType.buildKeywordsJson:101`

**(A)는 건드리지 않았다.** 8-4가 `global/gemini`와 함께 이 경로를 통째로 지운다 — 지금 고치면
"이 커밋만 revert하면 된다"는 8단계의 성질에 무관한 diff가 섞인다.

**(B)는 `IllegalStateException`으로 강등했다.** 업로드 코스 도메인인데 *"AI 코스 생성에 실패했습니다"*
503을 내고 있고, `Map<String, List<String>>`(전부 `String`) 직렬화라 **현실적으로 도달 불가능한 경로**다.
도달 불가능한 자리에 사용자 대면 ErrorCode를 남겨 두면 **만에 하나 터졌을 때 거짓 메시지를 낸다.**
게다가 이 메서드의 main 호출자는 `GeminiService` 하나뿐이라(새 경로는 `KeywordRenderer`가 대체했다)
8-4 이후 프로덕션 호출자가 0이 된다.

`MyCourseControllerSpec:1209`의 Swagger 문구는 **그대로 뒀다** — 그 문서는 AI 코스 생성 엔드포인트의
503을 설명하는 것이라 (A)를 가리키고, (A)는 아직 살아 있다. 8-4에서 함께 갱신한다.

---

## 판정 8 — 사용자가 고른 이동수단이 최적화까지 도달하지 못하고 있었다 ★★ (로드맵에 없던 것)

`RouteOptimizer`는 `TravelMode`별로 유효속도와 고정 오버헤드가 다르다(WALK 12km/h·10분,
CAR 25km/h·5분, UNSPECIFIED 15km/h·8분). 그런데 **그 값을 채우는 자리가 코드 어디에도 없었다.**

사용자는 이미 고르고 있다 — `KeywordType.WALK`("뚜벅이")·`CAR`("자차")가 `travelMode` 카테고리
키워드로 요청에 실려 온다. 지금까지 그 값이 `TravelMode`로 옮겨지지 않은 이유는 **옮길 자리가 없었기
때문**이다: `RouteOptimizer`를 부르는 코드가 이번에 처음 생겼다.

그래서 `CourseBrief.of(location, days, keywords)`가 키워드에서 읽도록 했다. 옮기지 않으면 뚜벅이
여행도 시속 15km로 계산돼 **이동시간이 어긋난 채 방문 시각이 확정된다** — 조립만 하고 넘어갔으면
"기능은 도는데 시간이 이상한" 형태로 남았을 결함이다.

**둘 다 골랐으면 `UNSPECIFIED`다.** 모순을 임의로 한쪽으로 풀면 그 판단이 코드 안에 숨고, 중간값은
두 경우 모두에서 크게 틀리지 않는다.

---

## 판정 9 — 계획했던 `RouteRequest` 팩토리 추가는 불필요했다 (정정)

착수 전 조사에서 `RouteRequest.DEFAULT_DAY_END`와 `RoutedDay.empty`가 package-private이라
`pipeline` 패키지에서 쓸 수 없다고 봤고, `RouteRequest`에 public 팩토리를 하나 더 만들 계획이었다.
**소스를 직접 읽으니 아니었다** — `DEFAULT_DAY_START`·`DEFAULT_DAY_END`는 `public static final`이고,
표준 생성자가 `null`을 기본값으로 흡수한다. `RoutedDay.empty`도 필요 없다: `optimize`가 빈 입력을
받으면 내부에서 그것을 반환한다.

그래서 `new RouteRequest(day, places, dayStartTime, null, travelMode)` 한 줄로 끝났고 **route 패키지는
한 글자도 바뀌지 않았다.** 조사 요약을 근거로 계획을 세울 때는 시그니처를 소스에서 한 번 더 확인해야
한다는 사례로 남긴다.

---

## 판정 10 — 테스트가 잡은 결함: `Stream.findFirst()`는 `null`에 NPE를 던진다

Planner의 `dayStartTime`을 찾는 코드를 처음에 이렇게 썼다.

```java
plan.days().stream().filter(d -> d.day() == day)
    .map(PlannerDayPlan::dayStartTime).findFirst().orElse(null);
```

**`dayStartTime`은 실제로 `null`일 수 있다** — `DefaultPlannerPlans`가 비워 두기 때문이다. 그런데
`Stream.findFirst()`는 첫 원소가 `null`이면 `Optional.of`에서 NPE를 던진다. 즉 **Planner가 실패해
기본 플랜으로 degrade한 요청만 NPE로 죽는다** — 폴백이 폴백 경로에서만 터지는, 정상 경로 테스트로는
절대 잡히지 않는 형태다.

`map`을 `findFirst` 뒤로 옮겨 고쳤다(`Optional.map`은 `null`을 빈 `Optional`로 받는다).
**"Planner 실패 → 기본 플랜"을 테스트로 재현한 것이 이걸 잡았다** — 7-6을 폴백 경로별로 나눈 이유가
정확히 이런 것이다.

---

## 판정 11 — 스텁 통합에서 채택 집계를 "분모 일치"로 단언한다

정상 경로 통합 테스트에서 `ai.candidate.adopted{source=seeded, modifier=false}`가 0이라 처음에 실패했다.
원인은 결함이 아니라 **테스트 설계**였다 — WireMock 스텁은 경로 단위라 기본 쿼리와 스타일 modifier
쿼리가 **같은 응답**을 받고, 어느 쪽 유래로 병합되는지는 이 테스트가 통제하는 축이 아니다.

단언을 두 축의 **합이 최종 코스의 장소 수와 같은가**로 바꿨다. 원래 묻고 싶었던 것이 "채택 집계의
분모가 실제 코스와 일치하는가"였으므로 오히려 강해진 단언이다. modifier 축이 제대로 갈리는지는
목 기반 테스트(`recordsAdoptedBySource`)가 `SEEDED`/`LISTED` × `modifier` 조합으로 따로 고정한다.

---

## 판정 12 — 지연을 히스토그램 버킷으로 낸다 ★★ (7-5 보강)

7-5를 구현한 직후에는 `Timer.builder(...).register(registry)` 그대로였다. 실제 노출 형태를 확인해
보니 **`_count`·`_sum`·`_max` 셋뿐이고 `_bucket`이 없었다** — Micrometer Timer의 기본값이다.

그 상태에서 뽑히는 것은 평균(`rate(_sum)/rate(_count)`)과 최댓값뿐인데, **11-2가 요구하는 판단
기준은 p95**다. 꼬리가 긴 분포에서 평균은 "20명 중 1명이 25초를 기다린다"를 가리므로 대체재가
되지 못한다. 측정이 끝난 뒤에 알았다면 30요청을 다시 돌려야 했다.

### 클라이언트 계산 백분위를 쓰지 않는 이유

| | `publishPercentiles(0.5, 0.95)` | `publishPercentileHistogram()` (채택) |
|---|---|---|
| 계산 위치 | 앱 안 (롤링 윈도우 기본 2분) | Prometheus (`histogram_quantile`) |
| **배치 측정** | **스크레이프 시점을 놓치면 감쇠해 사라진다** | 누적 카운터라 나중에 구간을 잘라 볼 수 있다 |
| 시계열 수 | 태그 조합당 2개 | 태그 조합당 ~69개 |
| 사후 재분석 | 불가 | 가능 |

**8-6·11-2는 30요청을 몰아 돌리고 끝난 뒤에 분석하는 배치 측정**이다. 롤링 윈도우 위에서 계산되는
값은 그 용도에 구조적으로 맞지 않는다 — 측정이 끝나고 2분이 지나면 값이 없다.

### 범위를 예산에 맞춰 버킷을 줄인다

`minimumExpectedValue` 1ms / `maximumExpectedValue` 30초로 잘랐다. 상한을 **요청 예산과 같은 값**으로
둔 것이 요점이다 — 모든 스테이지가 그 예산 안에서 기다림을 자르므로 그보다 오래 걸리는 단계는
원리적으로 없고, **`+Inf` 버킷에 값이 잡힌다면 그 자체가 "예산을 넘겼다"는 답**이 된다.
실측 결과 단계당 69개 버킷 × 6단계 = **414개 시계열**이다.

`ai.llm.call`(5-11)도 같은 결함을 갖고 있어 함께 고쳤다(상한 45초 — 세마포어 대기 20초 + 호출 20초).

### 설정이 아니라 코드에 둔 이유

`management.metrics.distribution.percentiles-histogram.<이름>: true`로도 켤 수 있지만, **그쪽은 메트릭
이름을 문자열로 다시 적는다.** 오타가 나면 아무 오류 없이 히스토그램만 빠지고, 그 사실은 11-2에서야
드러난다. `AiCourseMetrics`가 "태그 문자열이 여러 클래스로 흩어지면 오타 하나가 조용히 새 시계열을
만든다"는 이유로 만들어진 클래스라, 같은 함정을 설정 파일에 다시 만들 이유가 없다.

---

## 판정 13 — 폴백 발동 빈도를 슬롯 단위로 센다 ★★ (7-3 관측)

7-3의 폴백은 **응답을 200으로 유지한다.** Curator가 전 day 실패해도 코스는 나오고, 에러율도 지연도
정상이며, 오히려 LLM을 안 기다려 빨라진다. 바뀌는 것은 내용뿐이다 — "컨셉에 맞게 고른 장소"가
"검색 결과 상위 3개"가 된다. **어떤 지표에도 빨간불이 안 들어온다.**

`ai.candidate.adopted`로는 가릴 수 없다. 그쪽의 `source`는 후보의 **출처**(네이버냐 TourAPI냐)를
말할 뿐이라, LLM이 고른 시드 후보와 코드가 채운 시드 후보가 **똑같이 `seeded`로 찍힌다.**

### 8-6 환각률 측정이 오염되는 경로

이게 이 지표를 지금 넣는 이유다. **폴백으로 채운 장소는 환각률이 구조적으로 0에 가깝다** — 네이버·
TourAPI가 실제로 반환한 실존 장소만 들어가므로 LLM이 이름을 지어낼 여지가 없다. 8-6 측정 30요청을
도는 동안 Curator가 조용히 죽어 있었다면 결과는 이렇게 된다.

- 측정값: 환각률 거의 0%
- 실제 의미: 파이프라인이 좋아서가 아니라 **LLM을 안 썼기 때문**

그리고 그 숫자가 3점 비교의 마지막 점으로 로드맵에 기록된다. **틀린 결론을 데이터가 뒷받침하는
것처럼 보이는 상태**다.

### 세 값이 전체를 나눈다 — 분모를 따로 두지 않는다

`ai.curation.slot{result=curator|fallback|unfilled}`. 슬롯 하나는 반드시 셋 중 하나이므로
`fallback / (curator + fallback + unfilled)`가 곧 폴백 비율이다. 별도 분모 메트릭을 두면 두 값이
어긋날 때 어느 쪽이 옳은지 알 수 없어진다.

`unfilled`(비었는데 후보도 없어 못 채운 자리)를 따로 센 것은 **hard fail의 선행 지표**이기 때문이다 —
이 값이 전 슬롯으로 번지면 7-4의 `AI_GROUNDING_FAILED`가 된다.

### 집계는 값으로 돌려준다

`DeterministicCuration`은 순수 함수로 남아야 하므로 레지스트리를 만지지 않고 `Filled.slotCounts()`로
넘기고, 기록은 호출자인 `AiCoursePipeline`이 한다. **6-7의 `CuratedChoiceValidator`가 강등 집계에 대해
세운 구조 그대로다.**

---

## 검증 기록

| 항목 | 방법 | 결과 |
|---|---|---|
| 단위·통합 테스트 | `./gradlew test` | **754개 전부 통과**(7단계에서 46개 추가) |
| 정상 기동 | `bootRun` | **14.3초**, `Started YourtripApplication` |
| 빈 배선 | 위와 동일 | `AiCoursePipeline`이 `AiCourseProperties`를 요구하므로 **기동 성공이 곧 배선 성공**이다 |
| 신규 메트릭 0 등록 | `/actuator/prometheus` | `ai_course_pipeline_duration_seconds_count` **6개** + `ai_candidate_adopted_total` **6개** + `ai_curation_slot_total` **3개**, 전부 0 |
| 히스토그램 버킷 | 같은 엔드포인트 | `ai_course_pipeline_duration_seconds_bucket` 단계당 **69개**(총 414 시계열). `histogram_quantile`로 p95 계산 가능 |
| 기존 메트릭 무회귀 | 같은 엔드포인트 | 5·6단계 계열 **42개 시계열 그대로** |
| 기존 경로 무변화 | 컨트롤러 확인 | 여전히 `GeminiService` — 파이프라인으로 가는 경로 없음 |

```bash
./gradlew test
```

```bash
./gradlew bootRun
```

> 기동 로그의 `RedisConnectionFailureException`은 이번 변경과 무관하다 — `docker compose up -d redis`를
> 띄우지 않은 로컬의 예상된 동작이고, 캐시는 fail-open이라 앱은 정상 기동한다(CLAUDE.md).

**실호출 프로브는 돌리지 않았다.** 이 단계가 더한 것은 조립·판정·집계이고 셋 다 스텁으로 결정론적으로
재현된다. 실호출로만 드러나는 것(모델 응답의 실제 형태)은 6단계 프로브가 이미 봤고, 파이프라인 전체의
실호출은 8단계 E2E가 같은 경로를 태운다 — 여기서 한 번 더 쏘면 비용만 두 배가 된다.

### 테스트 구성 (36개)

| 파일 | 개수 | 무엇을 묻는가 |
|---|---|---|
| `AiCoursePipelineTest` | 17 | 조립 순서·degrade 분기·hard fail 경계·인덱스 재조립·메트릭 |
| `DeterministicCurationTest` | 14 | 빈 자리만 채우는가, `listIndex`가 0-based인가, 슬롯 집계가 전체를 나누는가 |
| `CourseBriefTest` | 7 | 이동수단이 키워드에서 읽히는가(판정 8) |
| `AiCourseMetricsTest` | 4 | **실제 Prometheus 출력**에 버킷과 0 시계열이 실리는가(판정 12) |
| `AiCourseStagesStubIntegrationTest$FullPipeline` | 4 | 실제 스텁 응답이 여섯 스테이지를 통과하는가 |

`AiCourseMetricsTest`만 `PrometheusMeterRegistry`를 쓴다 — **`SimpleMeterRegistry`는 집계 가능한
백분위를 지원하지 않아 `publishPercentileHistogram()`을 켜도 버킷을 만들지 않는다.** 즉 목 레지스트리로
단언하면 설정이 빠져 있어도 테스트가 통과한다(4단계 판정 11의 `Content-Type` 사건과 같은 종류의 함정).

`RouteOptimizer`와 `AiCourseMetrics`는 **목이 아니라 실물**을 쓴다 — 전자를 목으로 바꾸면 "순서와 시각이
실제로 실리는가"를 물을 수 없고, 후자는 시계열 값 자체가 단언 대상이다(5·6단계가 세운 관례).

---

## 남은 작업 — 다음 단계로 넘긴 것

- **컨트롤러 연결·`AiCoursePersister` 조정·`global/gemini` 삭제** — 전부 8단계. `AiCourseDraft` →
  `ResolvedDay`/`ResolvedPlace` 변환은 `domain.mycourse` 쪽에서 한다(파이프라인은 `domain`을 모른다)
- **`MyCourseControllerSpec:1209`의 `JSON_TRANSFORMATION_FAILED(503)` 문구** — 지금은 (A)를 정확히
  가리키므로 그대로 두고, 8-4에서 `AI_GROUNDING_FAILED`/`AI_COURSE_TIMEOUT`으로 갱신한다
- **예산 30초의 실측 근거** — 지금은 설계 추정치(p95 17~24초)에서 유도한 값이다. `ai.course.pipeline.duration`이
  쌓이면 8단계 E2E에서 실제 분포를 보고 조정한다. 그 분포가 11-2(202 Accepted 전환 판단)의 입력이기도 하다
- **`llm.max-concurrent-calls: 2` 재실측** — 7단계에서 "Planner 1회 + Curator days회"라는 실제 조건이
  코드로는 완성됐지만, 실호출을 하지 않았으므로 여전히 미측정이다. 8단계 E2E 이후 별도 작업
- **데드라인으로 잘라도 LLM 호출 자체는 취소되지 않는다** — 6단계가 남긴 항목이 그대로 남는다.
  파이프라인은 기다림을 자를 뿐 permit을 쥔 호출을 죽이지 못한다. 부하가 걸린 상태에서 이것이 실제로
  문제가 되는지는 실측이 필요하다
- **어댑터를 끄면 파이프라인도 못 뜬다** — `llm.provider`를 비우면 `LlmClient` 빈이 없어 두 에이전트가,
  이제는 `AiCoursePipeline`까지 기동에 실패한다. V1이 OpenAI로 확정돼 실질 위험은 없으나
  "키 없는 환경에서 기동을 살리는 탈출구"는 8단계 스위치 이후 사실상 사라진다
- ~~**폴백 발동 빈도가 계측되지 않는다**~~ → **해소했다**(판정 13). `ai.curation.slot{result}`로
  슬롯 단위 집계를 추가했다
- ~~**지연 Timer 가 p95 를 못 낸다**~~ → **해소했다**(판정 12). 두 Timer에 히스토그램 버킷을 켰다
- **요청 전체의 p95는 아직 없다** — 지금 있는 것은 단계별 분포뿐이고, **p95의 합은 합의 p95가
  아니다.** 8단계에서 컨트롤러가 연결되면 Actuator의 `http_server_requests`가 `POST /api/my-courses/ai`를
  잡으므로 별도 메트릭은 필요 없지만, **그쪽도 히스토그램을 켜야 한다**
  (`management.metrics.distribution.percentiles-histogram.http.server.requests`). 전 엔드포인트에
  적용되는 설정이라 카디널리티 판단이 따로 필요해 여기서는 손대지 않았다
- **히스토그램 414개 시계열의 비용** — 로컬 Prometheus에서는 문제가 없지만, 배포 환경의 보존 기간과
  함께 한 번은 확인할 값이다. 줄이려면 `serviceLevelObjectives`로 필요한 경계만 남기는 방법이 있다

---

## 참고 문서

- [ROADMAP.md](../ROADMAP.md) — 7단계 체크리스트
- [운영 관심사](../design/운영-관심사.md) — degrade 표·`AiCourseErrorCode` 초안·지연 예산(이 단계가 실행한 설계)
- [멀티 에이전트 파이프라인](../멀티-에이전트-파이프라인.md) — 조립 순서와 데이터 흐름 그림
- [STEP-6-agents.md](STEP-6-agents.md) — 판정 4가 근거로 삼은 `CuratedChoiceValidator`의 계약
- [STEP-5-grounding.md](STEP-5-grounding.md) — 판정 3의 근거인 5-9 실측(빈 슬롯 0%)
- [STEP-3-route-optimizer.md](STEP-3-route-optimizer.md) — 판정 8이 다루는 `TravelMode`별 이동시간 모델
