# TASK-AI-MULTI-AGENT. AI 여행 코스 생성 멀티 에이전트 파이프라인 설계

> 현재 AI 코스 생성(`POST /api/my-courses/ai`)은 Gemini를 한 번 호출해 코스 전체를 만든다. 답변 퀄리티가 낮은 원인은 프롬프트 튜닝 부족이 아니라 **구조**다. 이 문서는 그 구조를 멀티 에이전트 파이프라인으로 재설계한 근거를 남긴다. 구현 이전의 설계 문서이며, 실측 결과는 구현 후 이 문서에 추가한다.
>
> 이 설계의 핵심 논지는 두 가지다. **(1) LLM에게 결정론으로 풀 수 있는 일을 시키지 않는다. (2) 멀티 에이전트는 추론을 재구성할 뿐 지식을 추가하지 않으므로, 외부 지식 신호를 명시적으로 붙인다.**
>
> **V1 범위**: `CriticAgent`와 `CandidateRefiner`(§5-3, §5-4)는 설계는 남기되 **V1에서 제외**한다.
> 근거는 §10 "Critic·Refiner를 V1에서 제외한 이유"에 정리했다 — 현재 프로젝트 규모 대비 확보되는
> 가치가 얇고(특히 ATTRACTION 슬롯은 외부 근거 없이 Curator와 같은 지식으로 재추측하는 것에
> 가깝다), 판단 기준도 아직 미확정이며, 효과를 측정할 인프라도 없다. 반면 Planner·Curator·
> Grounding·PlaceSignal·RouteOptimizer만으로도 환각 차단·동선 계산·인기도 랭킹이라는 확실한
> 개선이 있고, [AI-HALLUCINATION-GEMINI.md](hallucination/AI-HALLUCINATION-GEMINI.md) 실측에서
> **JSON 파싱 실패율 28.6%**라는 더 크고 확실한 문제가 이미 드러나 우선순위가 그쪽에 있다.
>
> **LLM 벤더**: 이 문서의 초안은 Gemini를 현행으로 두고 OpenAI 전환 "가능성"을 전제로 썼으나,
> **전환이 OpenAI로 확정됐다.** 그에 따라 §6(어댑터·설정)·§7(프롬프트 결함)·§11(비용 근거)·
> §13(도입 순서)·§15(착수 전 확인)를 갱신했고, 벤더 교체 효과와 파이프라인 효과를 분리하기 위한
> **3점 baseline 측정**을 §13 2단계에 추가했다.
>
> **실행 로드맵**: 이 문서는 "왜 이렇게 설계했는가"를 담는다. 착수 전 준비, 단계별 체크리스트,
> 완료 판정 기준은 [ROADMAP.md](ROADMAP.md)에 있다.
>
> **[개정] 후보 공급(retrieval) 층 추가 — Curator의 역할을 "회상"에서 "선별"로 바꾼다.**
> 초안의 Curator는 슬롯당 상호명 3개를 **파라메트릭 지식만으로 회상**했고, 외부 데이터는 그
> *뒤*(카카오 검증·네이버 랭킹)에서만 붙었다. 이 구조는 환각을 걸러내지만(정밀도) **모델이 애초에
> 모르는 지역에서 좋은 후보를 만들어내지는 못한다**(재현율) — 그리고 이 서비스의 대상은 한국
> 지방 도시라, 인지도가 낮은 지역일수록 그 공백이 커진다는 것이 이 개정의 출발점이다. 그래서
> Curator *앞*에 **실존 장소 후보를 외부 API로 먼저 확보하는 `CandidateRetrievalStage`**를 두고,
> Curator는 그 목록에서 컨셉에 맞는 것을 고르는 역할로 바꾼다(§5-0). 후보 풀은 **네이버 지역검색
> 인기순 시드 + 카카오 커버리지 검색 + Curator 파라메트릭 제안** 3원 구성이며, 이에 따라 §14에서
> 기각했던 두 항목(카카오 카테고리 검색을 Curator 입력으로 / 네이버 지역검색)이 **역할을 바꿔
> 재채택**됐다. 근거와 조사 결과(카카오·구글·Foursquare·TripAdvisor의 인기도 정렬 지원 여부)는
> §5-0·§14에 있다.

---

## 1. 배경 — 왜 단일 호출로는 안 되는가

### 현재 구조

`GeminiService.generateAICourse`의 95줄짜리 프롬프트 하나가 **컨셉 설계 + 장소 선정 + 시간 배치 +
동선 최적화 + 제목 작명**을 동시에 요구하고, `MyCourseServiceImpl.createAICourse`가 그 결과를
카카오 장소 검색으로 사후 보정한다.

```java
@Transactional
public AICourseCreateResponse createAICourse(AICourseCreateRequest request) {
    int days = (int) ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
    String json = geminiService.generateAICourse(...);          // LLM 1회
    GeminiCourseDto dto = objectMapper.readValue(json, ...);
    // … day/place 저장하며 place마다 kakaoLocalClient.findBestPlace() 블로킹 호출
}
```

### 근본 원인 6가지

1. **LLM은 지리 정보를 모른다.** 좌표 없이 "동선 최적화"를 텍스트로만 시도하니 실제로는 지그재그 동선이 나온다.

2. **환각을 걸러내는 게 아니라 세탁하고 있다.** `KakaoLocalClient.findBestPlace`의
   `docs.stream().max(...)`에는 **점수 하한선이 없어서 0점 후보도 반환**한다. LLM이 지어낸 가짜
   상호명으로 검색하면 카카오가 그 지역의 무관한 POI를 돌려주고, 그게 그대로 사용자 코스에 저장된다.
   지금 구조는 환각을 **"실존하는 엉뚱한 장소"로 바꿔서 통과시킨다.**

3. **단일 호출에 과부하.** 다섯 가지 일을 한 번에 시켜서 각각이 다 얕다.

4. **자기 검증 루프가 없다.** 결과가 사용자 키워드를 반영했는지 아무도 확인하지 않는다.

5. **후보군이 없다.** 장소당 1개만 뽑아서 검증 실패 시 대체재가 없다.

6. **품질 신호가 없다.** 카카오 Local API는 평점·리뷰 수를 제공하지 않는다. **"실존하지만 아무도
   안 가는 동네 가게"와 "진짜 맛집"을 구분할 방법이 파이프라인 어디에도 없다.**

### 함께 고쳐야 할 기존 결함

설계 과정에서 발견한, 품질과 별개로 존재하는 버그들이다.

| 결함 | 증상 |
|---|---|
| `Place`의 `@Builder` 파라미터가 primitive `double` | 필드는 `Double latitude/longitude`(래퍼)인데 **`@Builder` 생성자 파라미터만 primitive `double`**이다. 이 한 줄의 불일치가 서로 다른 두 버그를 만든다 — ① `PlaceMapper.toEntityFromGeminiDto`가 좌표를 세팅하지 않으므로 Lombok이 기본값 `0.0`을 넣어, 카카오 매칭 실패 장소가 `null`이 아니라 `0.0/0.0`으로 저장된다. Swagger는 `null`을 약속하는데 실제로는 **기니만 앞바다에 핀이 찍히고**, null 체크로는 걸러낼 수도 없다. ② `PlaceMapper.toCopyEntity`는 `Double`을 `double` 파라미터에 넘기므로 좌표가 `null`인 코스를 fork/upload 복제하면 **언박싱 NPE**가 난다 |
| `KeywordType.buildKeywordsJson(null)` | `keywords`는 선택 입력(`AICourseCreateRequest`에 검증 애노테이션이 없다)인데 `new HashSet<>(selectedKeywords)`가 NPE로 500 |
| `MyCourseErrorCode.JSON_TRANSFORMATION_FAILED` 오용 | **방향이 정반대인 두 실패가 같은 코드를 공유한다** — LLM 응답 *역*직렬화 실패와 위 키워드 *직*렬화 실패. 게다가 LLM 호출 자체의 실패(타임아웃·429·safety block)에 대응하는 ErrorCode는 아예 없어 원시 500으로 떨어진다 |
| `.block(Duration.ofSeconds(20))` | 타임아웃 시 `IllegalStateException`이라 `WebClientResponseException` catch를 빠져나가 원시 500 |
| **`@Transactional` 안의 외부 I/O** | 타임아웃 없는 LLM 호출 + 최대 18회 × 20초 카카오 호출. 최악의 경우 HikariCP 커넥션 1개를 **360초** 점유(`open-in-view: false`라 트랜잭션 전체에 묶임). 카카오가 느려지면 무관한 API까지 같이 죽는다 |

마지막 항목은 품질 문제가 아니라 **가용성 문제**이고, 파이프라인 재설계와 별개로 우선 고쳐야 한다.

> 이 표에는 원래 **`Period.between(...).getDays()`로 다개월 여행 일수가 틀리는 결함**(`1/1 ~ 3/5`는 `P2M4D`라 `.getDays()`가 `4` → `days=5`, 실제 64일)이 함께 있었으나, 커밋 `7cdda90`에서 `ChronoUnit.DAYS.between`으로 교체되어 **이미 해소됐다.** §13 도입 순서의 1단계 범위에서도 제외한다.

---

## 2. 설계 원칙

### 원칙 1 — LLM에게 결정론으로 풀 수 있는 일을 시키지 않는다

LLM은 "무엇을 고를지(취향·컨셉·큐레이션)"에만 쓰고, "어디에 어떻게 배치할지(동선·시간·중복)"는
실제 좌표 기반 알고리즘으로 푼다.

이 원칙의 직접적 결과로, 검증이 필요해 보이던 항목 대부분이 **애초에 발생 불가능**해진다.

| 검증 항목 | 처리 방식 |
|---|---|
| 시간 겹침 | `t[i] = t[i-1] + 체류 + 이동`으로 계산 → **단조증가가 구조적으로 보장** |
| day당 식사 1회 | Planner 출력 검증 시 코드로 강제 삽입 |
| 중복 장소 | 카카오 `Document.id` 기준 전 day dedupe |
| 슬롯↔업종 불일치 (점심에 호프집) | 카카오 `category_group_code` 하드 제약 |
| **키워드/컨셉 반영도** | **여기만 판단이 필요하다** |

Planner를 별도 단계로 두는 이유도 "단계 분할"이 아니다. **Curator를 day별로 병렬 실행하려면
day별 권역이 먼저 확정돼야 한다.** Planner는 병렬화의 전제조건이다.

### 원칙 2 — 멀티 에이전트는 추론을 재구성할 뿐, 지식을 추가하지 않는다

에이전트를 아무리 쪼개도 장소 지식의 출처는 여전히 **LLM의 파라메트릭 지식(학습 데이터에 각인된
기억) 하나**다. 모델이 순천의 좋은 카페를 모르면 에이전트가 몇 개든 여전히 모른다.

| 멀티 에이전트 재구성만으로 | 개선 여부 |
|---|---|
| 동선·시간 배치 | **확실히 개선** (추측 → 실좌표 계산) |
| 환각 장소가 코스에 박히는 것 | **확실히 개선** (후보 3배 + 점수 하한) |
| 컨셉 일관성 | 개선 (Planner가 권역을 좁혀 Curator 컨텍스트가 명확해짐) |
| 선택지 다양성 | 개선 (temperature 상향 + 후보 3배 + 교체 가능) |
| **"이 지역에 진짜 좋은 곳이 어디인가"** | **개선 안 됨** |

같은 이유로 **자기 채점(self-critique)도 독립 검증이 아니다.** Critic이 Curator와 같은 모델·같은
파라메트릭 지식으로 판단하면, 자기가 뽑은 걸 자기가 확인하는 확증편향 구조가 된다. 새 정보가
한 톨도 들어오지 않는다.

그래서 **외부 지식 신호 층을 명시적으로 추가**한다. 이것이 이 설계의 두 번째 축이다.

**이 원칙은 Curator의 뒤뿐 아니라 앞에도 적용된다.** 초안은 외부 신호를 Curator 출력의 검증
(카카오)과 랭킹(네이버)에만 썼는데, 그러면 위 표의 "개선 안 됨" 항목이 그대로 남는다 — 모델이
순천의 카페를 모르면 후보 3개가 전부 그라운딩에서 탈락하고, 결국 카카오 카테고리 검색 폴백(§9)으로
떨어진다. **즉 인지도가 낮은 지역일수록 파이프라인에서 품질이 가장 낮은 경로가 기본 경로가 된다.**
이 역설을 없애려면 후보 자체를 외부에서 공급해야 하므로, `CandidateRetrievalStage`(§5-0)가 실존
장소 목록을 먼저 확보하고 Curator는 그 목록에서 **선별**한다. "회상"은 파라메트릭 지식의 약점을
정면으로 때리는 과제지만, "이 15개 실존 카페 중 힐링·감성에 맞는 3개를 골라라"는 취향·컨셉
판단이라 정확히 LLM에게 시켜야 할 일이다(원칙 1).

> **이 개정의 출발 가설("인지도가 낮은 지역일수록 환각률이 높다")은 아직 실측되지 않았다.**
> [AI-HALLUCINATION-OPENAI.md](hallucination/AI-HALLUCINATION-OPENAI.md)의 입력 세트에는 유명 지역과
> 비교적 안 알려진 지역(순천·영주·공주·통영·삼척)이 모두 있으므로 **같은 아티팩트를 지역 그룹별로
> 다시 집계하면 소급 검증이 가능하다.** 또한 §12의 후보 출처 메트릭이 배포 후 같은 가설을 운영
> 데이터로 확인한다.

---

## 3. 지식 신호 4층

| 층 | 출처 | 답하는 질문 | 답하지 못하는 것 |
|---|---|---|---|
| 1 | **LLM 파라메트릭 지식** | 무엇이 이 컨셉에 어울리는가 | 실존하는가 (환각) · **이 지역에 무엇이 있는가** (무인지 지역) |
| 2 | **카카오 Local API** | 실제로 있는가, 어디인가, **무슨 업종인가** · **이 권역에 어떤 실존 POI가 있는가** (후보 공급) | 좋은 곳인가 |
| 2′ | **네이버 지역검색 `sort=comment`** | **이 권역에서 리뷰가 많은 곳은 어디인가** (인기 후보 시드) | 5건뿐 · 어떤 곳인가 |
| 3 | **네이버 블로그 `total`** | **사람들이 실제로 가는가, 아직 영업하는가** | 어떤 곳인가 |
| 4 | **네이버 블로그 스니펫 → 속성 추출** | **어떤 물리적 특징이 있는가** | 왜 좋은가 |

**2층·2′층은 검증뿐 아니라 후보 공급에도 쓴다.** 초안의 2층은 Curator 출력을 사후 검증하는
역할뿐이었는데, 같은 API를 Curator *앞*에서 호출하면 실존 후보 목록을 미리 확보할 수 있다.
2′층(네이버 지역검색)은 그 목록에 "리뷰가 많은 곳"을 심는 시더다 — 카카오에는 인기도 정렬이 없어
밀집 지역에서 후보가 임의 슬라이스가 되는 문제를 메운다. 상세는 §5-0.

**3층과 4층은 같은 API 호출 하나에서 나온다.** `display=5`로 조회하면 응답 한 번에
`total`(인기도) + `postdate`(최신성) + `title`·`description` 5건(속성 추출 재료)이 전부 들어 있다.
**네이버 호출은 장소당 1회다.**

각 층에서 **의도적으로 하지 않기로 한 것**이 설계의 절반이다.

### 2층 — 카카오: 좌표뿐 아니라 업종 정합성까지 쓴다

현재 코드는 `category_group_code`를 **가점 +2**로만 쓴다(`FD6/CE7/AT4`면 가산). 이걸
**슬롯별 기대 카테고리 하드 제약**으로 승격시킨다.

```
MEAL       슬롯 ← FD6 필수. 단 category_name 이 "술집" 계열이면 감점 (점심에 호프집 방지)
CAFE       슬롯 ← CE7 필수
ATTRACTION 슬롯 ← AT4 / CT1(문화시설) 허용, FD6·CE7 배제
```

컨셉까지는 아니지만 **최소한의 정합성**이고, 비용이 사실상 0이다. 원칙 1의 연장으로, LLM이나
후속 검토 단계가 볼 필요가 없는 항목이 하나 더 구조적으로 사라진다.

### 3층 — 네이버 `total`: 후기를 "읽지 않고" 인기도만 잰다

```
GET https://naverapihub.apigw.ntruss.com/v1/search/blog.json
    ?query={카카오 공식 상호명} {지역}&display=5&sort=date
  헤더: X-NCP-APIGW-API-KEY-ID / X-NCP-APIGW-API-KEY
  → { "total": 12847, "items": [ { title, description, postdate }, … ] }
```

> **[정정]** 초안은 엔드포인트를 ~~`https://openapi.naver.com`~~, 인증을
> ~~`X-Naver-Client-Id`/`X-Naver-Client-Secret`~~으로 적었으나, 검색 API가
> **NAVER API HUB(네이버 클라우드 플랫폼)** 로 이관되며 둘 다 바뀌었다(§11 정정 참고).
> **경로(`/v1/search/blog.json`)와 응답 필드가 그대로 유지되는지는 아직 확인하지 못했다** —
> 이 설계의 3·4층은 `total`·`postdate`·`title`·`description`에 의존하므로, §13 4단계 착수 시
> 실호출로 먼저 확정한다.

```java
popularity = log10(max(total, 1));                    // 0건→0, 100건→2, 1만건→4, 100만건→6
recentlyMentioned = 최신 postdate 가 12개월 이내;      // 폐업 감지
```

**로그 스케일이 필수인 이유**: `total`은 1건에서 수백만 건까지 **자릿수 단위로 벌어진다.**
선형으로 쓰면 유명 관광지 하나가 다른 모든 신호를 압도한다.

**협찬 편향이 상당 부분 무력화된다.** 협찬 포스팅이 많다는 건 최소한 "사람들이 가는 곳"이라는
신호이기도 하다. 절대적 품질은 못 재도 **무명 vs 유명을 가르는 데는 충분히 유효**하고, 이 층에
필요한 건 딱 그 변별력이다.

**검색 쿼리는 카카오 공식 상호명으로 던진다.** LLM이 추측한 이름이 아니라 카카오 검증을 통과한
`doc.place_name()`을 쓴다 — 표기 흔들림이 없어 `total`이 정확해지고, 동명 업소 문제도 지역명을
붙여 해소한다("황남밀면"은 전국에 여러 곳이다). **이것이 네이버를 카카오 이후에 배치하는 이유다.**

**슬롯 타입별 가중치** — 이 신호를 어디에 투입할 것인가:

| 슬롯 | 가중치 | 근거 |
|---|---|---|
| `MEAL`, `CAFE` | **1.0 (핵심 용처)** | 진짜 맛집과 동네 가게가 자릿수로 갈린다. 변별력 최대 |
| `SHOPPING`, `ACTIVITY` | 0.6 | 중간 |
| `ATTRACTION`, `VIEWPOINT`, `WALK` | **0.2** | 대릉원·첨성대 둘 다 수십만 건이라 구분이 안 된다. 관광지 선택은 Planner의 권역 설계가 대부분 정한다 |

### 4층 — 네이버 스니펫: 평가가 아니라 **속성**을 추출한다

컨셉·분위기를 판별할 유일한 외부 근거다. 다만 **무엇을 요약시키느냐**가 성패를 가른다.

요약 프롬프트가 "이 장소가 좋은가 / 어떤 분위기인가"를 물으면 협찬 문구를 그대로 받아 적는다.
대신 **원문에 사실로 적힌 물리적 속성만** 뽑는다.

| 뽑는다 (사실) | 버린다 (평가) |
|---|---|
| 야경, 루프탑, 한옥, 통창 | 분위기 최고, 강추 |
| 웨이팅 있음, 주차 어려움 | 재방문 의사 100% |
| OO역 도보 5분, 좌석 20석 | 인생 맛집 |
| 반려동물 동반, 아이 의자 | 감성 뿜뿜 |

**협찬 글에도 이런 사실은 정확하게 적혀 있다.** 광고비가 "루프탑이 있다"를 바꾸지는 못한다.
편향은 평가어에 실리지 속성에는 잘 실리지 않는다.

부수적으로 얻는 것이 하나 더 있다 — **접근성 정보**다. "황리단길 도보 5분", "주차장 협소" 같은
문구는 블로그에 흔한데 카카오 좌표만으로는 절대 나오지 않는다. `travelMode: WALK`(뚜벅이)일 때
직접적으로 유용하다.

**블로그 `title`이 `description`보다 신호가 강할 수 있다.** SEO 목적으로 특징을 압축해 넣기
때문이다 — "경주 황리단길 야경 예쁜 루프탑 카페 OO" 같은 제목이 실제로 많다. 100자 스니펫보다
밀도가 높다.

**`traits`는 닫힌 태그 집합에서만 고르게 강제한다.** 자유 텍스트로 두면 요약 단계 자체가 새로운
환각 지점이 되고 테스트도 불가능해진다.

| 범주 | 태그 |
|---|---|
| 뷰/분위기 | `야경` `뷰맛집` `루프탑` `통창` `한옥` `레트로` `넓음` `아늑함` `조용함` `시끌벅적` |
| 접근성 | `역세권` `도보접근` `주차가능` `주차난` |
| 혼잡 | `웨이팅` `예약필수` `한적함` |
| 동반 | `반려동물동반` `아이동반` `단체가능` |
| 가격 | `저렴` `보통` `고가` |
| 시간 | `아침영업` `야간영업` `브레이크타임` |

**키워드 → traits 매핑은 결정론적 사전으로 처리한다.** 여기에 LLM을 또 쓸 이유가 없고(원칙 1),
사전은 순수 함수라 완전히 테스트 가능하다.

| 사용자 키워드 | 가점 traits | 감점 traits |
|---|---|---|
| `COUPLE`(연인) | 야경, 루프탑, 통창, 조용함, 뷰맛집 | 시끌벅적 |
| `FAMILY`(가족) | 주차가능, 아이동반, 넓음, 단체가능 | 웨이팅, 주차난 |
| `FRIENDS`(친구) | 시끌벅적, 단체가능, 야간영업 | – |
| `SOLO`(혼자) | 아늑함, 조용함, 한적함 | 단체가능 |
| `WALK`(뚜벅이) | 역세권, 도보접근 | – |
| `CAR`(자차) | 주차가능 | 주차난 |
| `HEALING`(힐링) | 조용함, 한적함, 뷰맛집 | 웨이팅, 시끌벅적 |
| `SENSIBILITY`(감성) | 통창, 한옥, 레트로, 루프탑 | – |
| `COST_EFFECTIVE`(가성비) | 저렴 | 고가 |
| `PREMIUM` | 고가, 뷰맛집 | 저렴 |

### 최종 랭킹 점수

```java
rankScore = kakaoMatchScore                                  // 실존·이름·주소 일치도
          + popularity × slot.popularityWeight                // 3층
          + conceptScore × CONCEPT_WEIGHT                     // 4층 (traits ↔ 키워드 사전 매칭)
          - closedSuspicionPenalty;                           // 폐업 의심
```

**모든 보조 신호는 감점이지 하드 드롭이 아니다.** 블로그 언급 0건이 곧 폐업은 아니고(최근 오픈한
신규 매장일 수 있다), traits가 비어 있는 게 곧 부적합은 아니다. 후순위로 밀되 같은 슬롯에 다른
후보가 없으면 여전히 쓴다. **보조 신호를 필수 조건으로 승격시키지 않는다**가 원칙이다.

---

## 4. 파이프라인 전체 구조

```
CourseBrief (location, days, keywords, travelMode)
   │
   ├─[LLM] PlannerAgent ──────────► TravelPlan
   │        컨셉·제목·day별 권역·슬롯 구성. 장소명은 한 개도 생성하지 않음
   │
   ├─[Naver+Kakao] CandidateRetrievalStage ► CandidatePool × day   (day·슬롯타입별 병렬)
   │        area × 슬롯타입별 실존 후보 목록 확보 (§5-0)
   │          ├ 네이버 지역검색 sort=comment 상위 5   → SEEDED (인기 검증된 후보)
   │          │    + 스타일 modifier 쿼리 "{area} {trait} {searchHint}" (키워드→traits 사전 재사용, 합집합)
   │          └ 카카오 키워드 검색 "{area} {searchHint}" ~10 → LISTED (커버리지, 좌표·업종 즉시 확정)
   │        ★ fail-open: 둘 다 실패하면 빈 목록으로 진행 (= 초안의 회상 구조로 degrade)
   │
   ├─[LLM] CuratorAgent × day ────► DayCandidates              (day별 병렬)
   │        슬롯당 후보 3개 — 목록에서 선별(SEEDED/LISTED, 목록 인덱스로 참조)
   │        + 목록 밖 파라메트릭 제안(SUGGESTED, 유명 장소 회수용)
   │
   ├─[Kakao] GroundingStage ──────► GroundedPlace              (후보 병렬 검증)
   │        SUGGESTED·SEEDED: 실존 검증 + 좌표/주소/URL 확정 (기존 경로)
   │        LISTED: 카카오 응답에서 이미 확정됨 → 재검증 생략
   │        업종 정합성 하드 제약. 점수 하한 미달 탈락. kakaoId 기준 전 day dedupe
   │        ★ 여기를 통과 못 한 장소는 파이프라인에 존재하지 않는다 = 환각 원천 차단
   │
   ├─[Naver] PlaceSignalStage ────► + 인기도 + 속성            (생존 후보 병렬, 장소당 1회 호출)
   │    ├─ PopularityScorer  (순수함수) : total → 인기도, postdate → 폐업 감지
   │    └─ PlaceProfileAgent (LLM 배치) : title·description → traits (닫힌 태그 집합)
   │        → rankScore 계산 후 슬롯 내 후보 재정렬
   │        ★ fail-open: 네이버 장애 시 층 전체를 스킵하고 진행
   │
   └─[순수함수] RouteOptimizer ───► CourseDraft   (V1은 여기서 끝 — 최종 결과)
            haversine 거리 + 식사 시간창 페널티 최소화 완전탐색. startTime 계산
```

**V1 범위는 여기까지다.** 설계상으로는 이 뒤에 `CriticAgent`(블라인드 리뷰) → `CandidateRefiner`
(문제 장소 교체 + 재최적화, 최대 1회)가 이어질 수 있지만, **V1에서는 제외**했다 — 근거는 §10
"Critic·Refiner를 V1에서 제외한 이유"에, 설계 자체는 §5-3·§5-4에 그대로 남겨뒀다.

### 실제 데이터 흐름 예시

**입력**: 경주 / 3일 / `뚜벅이, 연인, 힐링, 감성, 가성비`

**① PlannerAgent** — 장소명은 한 개도 생성하지 않는다
```json
{
  "title": "경주, 천년의 밤을 걷다",
  "concept": "도보 이동 중심의 구시가지, 한옥 골목과 야경 위주의 여유로운 템포",
  "dayPlans": [
    { "day": 1, "area": "황리단길·대릉원 일대", "theme": "한옥 골목 산책과 야경",
      "dayStartTime": "10:00",
      "slots": ["ATTRACTION", "MEAL", "CAFE", "ATTRACTION", "MEAL"] }
  ]
}
```
핵심은 `area`다. 다음 단계의 **검색 키워드 접두사로 직접 쓰이고**, day별 병렬 실행의 분할 기준이 된다.

**①′ CandidateRetrievalStage (day 1)** — `area` × 슬롯타입별로 실존 후보 목록을 만든다
```
CAFE 슬롯:
  [Naver 지역검색] "황리단길 카페" sort=comment display=5
     → 카페 A(리뷰 상위) · 카페 B · 카페 C · 카페 D · 카페 E              → SEEDED
  [Kakao 키워드]   "황리단길 카페" (CE7, 15건 중 상위 10)
     → 카페 B(중복 dedupe) · 카페 F · 카페 G · … (좌표·업종·주소 포함)      → LISTED
  → 병합 목록 13개, 각 항목에 index·이름·카테고리·도로명주소·(LISTED는 kakaoId·좌표) 부여
```
MEAL 슬롯도 동일. ATTRACTION은 카카오 검색만(시더는 MEAL/CAFE 한정, §5-0).

**② CuratorAgent (day 1)** — `area` + `theme` + `slots` + 키워드 + **슬롯별 후보 목록**을 받는다. 다른 day는 모른다
```json
{ "day": 1, "slots": [
  { "slotIndex": 0, "type": "ATTRACTION", "candidates": [
      { "source": "LISTED",    "listIndex": 2,  "placeName": "대릉원" },
      { "source": "LISTED",    "listIndex": 5,  "placeName": "첨성대" },
      { "source": "SUGGESTED", "listIndex": null, "placeName": "경주월정교", "areaHint": "교동" } ] },
  { "slotIndex": 2, "type": "CAFE", "candidates": [
      { "source": "SEEDED",    "listIndex": 0,  "placeName": "카페 A" },
      { "source": "LISTED",    "listIndex": 7,  "placeName": "카페 G" },
      { "source": "SEEDED",    "listIndex": 3,  "placeName": "카페 D" } ] }
] }
```
`SUGGESTED`는 목록에 없지만 모델이 확신하는 유명 장소를 회수하는 경로다. 무인지 지역에서는
비어 있는 게 정상이고, 유명 지역에서는 "황남빵 본점"처럼 로컬 검색 랭킹에 밀린 장소가 여기로 들어온다.

**③ GroundingStage** — `LISTED`는 카카오 응답을 그대로 승계(재검증 없음), `SEEDED`·`SUGGESTED`만
`"황리단길·대릉원 일대 {상호명}"`으로 검색해 점수 ≥ 5 + 업종 정합만 통과
```
대릉원 (LISTED)      → id=8033… x=129.2094 y=35.8347 AT4  (①′에서 확정)  ✅
첨성대 (LISTED)      → id=1174… x=129.2190 y=35.8348 AT4  (①′에서 확정)  ✅
경주월정교 (SUGGESTED)→ 무매치 또는 score=0                              ❌ 탈락
카페 A (SEEDED)      → id=2291… CE7 score=10  (네이버 상호명 → 카카오 공식화)  ✅
```

**④ PlaceSignalStage** — 카카오 공식 상호명으로 네이버 1회 조회
```
"대릉원 경주" → total=48,200, 최신 postdate=2026-07-22
              → popularity=4.68 × 0.2(ATTRACTION) = 0.94
              → traits=[한적함, 도보접근, 야간영업]
              → conceptScore: 힐링(+한적함) 연인(–) 뚜벅이(+도보접근) = +2
```

**⑤ RouteOptimizer** — 실제 좌표가 확보됐으므로 `5! = 120`가지를 전부 계산해
`거리 + 식사 시간창 위반 페널티`가 최소인 배열을 고르고 시각을 산출한다. **V1은 이 결과가 최종
코스다.** 시간 겹침·중복·식사 누락·업종 불일치는 ③~⑤가 이미 구조적으로 보장하므로, 그걸 다시
검토할 단계(Critic)가 없어도 문제되지 않는다.

---

## 5. 각 단계 설계 상세

### 5-0. CandidateRetrievalStage — 후보 풀 3원 구성

**문제를 정확히 자르면 이렇다.** 초안 구조에서 GroundingStage는 정밀도(가짜 상호명 차단)만 풀고,
재현율("이 지역에 좋은 곳이 있는데 후보에 못 올라옴")은 아무도 안 푼다. 그래서 후보를 외부에서
공급하는데, **소스 하나로는 안 된다** — 각 소스가 뚜렷한 맹점을 갖기 때문이다.

| 소스 | 태그 | 확보하는 것 | 맹점 (다른 소스가 보완) | 적용 슬롯 |
|---|---|---|---|---|
| **네이버 지역검색 `sort=comment`** — `"{area} {searchHint}"`, 상위 5건 | `SEEDED` | 리뷰 수 기준 **검증된 인기 장소** | 5건 하드 실링(페이징 불가), 리뷰 많은 곳 편중(신규·숨은 명소 누락) | **MEAL / CAFE** |
| **카카오 키워드 검색** — `"{area} {searchHint}"` + 슬롯 카테고리 코드, ~10건 | `LISTED` | **커버리지** — 무인지 지역에서도 실존 POI 확보, 좌표·업종·주소 즉시 확정 | **품질 무순** — 정렬이 `accuracy`/`distance`뿐이고 인기 신호 필드가 없다. 밀집 지역에서는 수백 개 중 임의 슬라이스 | 전 슬롯 |
| **Curator 파라메트릭 제안** | `SUGGESTED` | 로컬 검색 랭킹 밖의 **전국구 유명 장소** 회수(본점·명소) | 무인지 지역에서 환각 → 그라운딩 검증 필수 | 전 슬롯 |

**셋이 서로의 맹점을 정확히 상쇄한다.** 시더가 없으면 밀집 지역에서 후보 품질이 임의적이고,
카카오가 없으면 무인지·저리뷰 지역에서 풀이 비며, 파라메트릭이 없으면 유명 장소가 로컬 검색
랭킹에 밀려 빠진다. 그리고 **어느 소스가 죽어도 나머지로 코스가 성립하고, 전부 죽으면 정확히
초안 구조(회상 + 그라운딩)로 degrade된다** — 새 hard fail 지점이 하나도 늘지 않는다(§9).

**시더가 필요한 이유 — 카카오에는 인기도 정렬이 없다.** 카카오 Local API의 `sort`는 `accuracy`와
`distance` 둘뿐이고 응답에 평점·리뷰 수 필드도 없다(§1 근본 원인 6과 같은 사실). 황리단길 반경에
카페가 수백 개일 때 상위 10개는 "가까운 순" 또는 "비공개 기준의 정확도순"으로 잘리고, 진짜 좋은
곳이 풀에 아예 안 들어올 확률이 밀집 지역일수록 오른다. **사후 랭킹(3·4층)이 아무리 좋아도 풀에
없는 장소는 못 올린다.** 그래서 풀에 요구되는 건 "완벽한 정렬"이 아니라 **"인기 있는 곳의 포함"**
(seeding)이고 — 정렬은 어차피 PlaceSignalStage가 다시 한다 — 이 구분이 중요한 이유는 풀 전체를
네이버로 랭킹하면 쿼터가 폭발하지만(§14 "풀 전체 사전 랭킹" 참고) 상위 몇 개를 심는 건 슬롯당
호출 1회면 되기 때문이다.

**시더는 MEAL/CAFE에만 적용한다.** 임의 판단이 아니라 설계 내부 논리와의 정합이다 — 밀집·임의
슬라이스 문제는 후보가 수백 개인 카테고리(식당·카페)에서 발생하고, 그 슬롯들이 정확히 §3에서
`popularityWeight 1.0`("진짜 맛집과 동네 가게가 자릿수로 갈린다")을 배정받은 슬롯이다. ATTRACTION은
반대로 POI가 희소해 카카오 검색 + 파라메트릭으로 풀이 충분히 잡히고, 인기도 변별력도 낮다는 것이
§3의 기존 결론(가중치 0.2)이니 시더 비용을 쓸 이유가 없다. SHOPPING/ACTIVITY(0.6)로의 확장은
실측 후 결정한다.

**네이버 지역검색 응답의 실무적 제약 세 가지** (§15 착수 전 실호출로 확정)
- `title`에 검색어 매칭 `<b>` 태그가 섞여 온다 → 카카오 그라운딩 쿼리로 넘기기 전 **태그 스트립 필수**
- `mapx`/`mapy`는 카카오와 체계가 다르다(카텍 → WGS84 × 10⁷ 정수 문자열로 변경 공지) → **좌표는
  쓰지 않고 상호명만 가져와 카카오로 공식화**한다. §3 "네이버 검색은 카카오 공식 상호명으로" 원칙의
  역방향이며 같은 인프라(GroundingStage)로 처리된다. kakaoId 기준 dedupe도 기존 로직 재사용
- `description`·`telephone`은 대부분 빈 값 → 속성 추출 재료로는 못 쓴다. 4층 재료는 여전히 블로그 검색
- `start`가 1로 고정이라 **6위 이하는 어떤 방법으로도 못 받는다.** 풀을 넓히는 유일한 축은 쿼리
  변주(각 5건 → dedupe)이고 변주 1개당 쿼터 1회다. 그 변주 예산을 업종 세분화(`"브런치"`·
  `"디저트"`)가 아니라 **스타일 modifier**(아래)에 쓴다 — 스타일 축이 풀에 없으면 뒤에서 아무리
  랭킹해도 못 살리는 반면, 업종 세분화는 카카오 커버리지 검색이 이미 상당 부분 감당하기 때문이다

**`LISTED` 위조 방어 — LLM 출력을 신뢰하지 않는다.** 선별 과제라도 LLM 출력인 이상 "목록에서
골랐다"고 주장하며 목록에 없는 상호명을 내놓을 수 있다. 그래서 `LISTED`/`SEEDED` 후보는 상호명이
아니라 **`listIndex`(목록 인덱스)로 참조**하게 하고, 코드가 세 가지를 검증한다 — ① `listIndex`가
범위 안인가 ② 그 인덱스의 목록 항목 상호명과 응답 `placeName`이 일치하는가(모델이 인덱스는 맞게
쓰고 이름을 바꿔 쓰는 경우) ③ 슬롯 타입이 목록의 슬롯 타입과 같은가. **하나라도 어긋나면 그
후보를 `SUGGESTED`로 강등해 기존 그라운딩 검증 경로로 보낸다** — 버리지 않고 강등하는 이유는
이름이 실존할 수도 있기 때문이고, 강등 건수를 메트릭으로 세면 Curator 프롬프트가 목록을 얼마나
잘 따르는지의 지표가 된다(§12). "재검증 생략"의 전제는 목록 항목의 카카오 데이터를 **코드가**
승계하는 것이지 LLM이 옮겨 적는 것이 아니다 — 좌표·kakaoId는 응답 스키마에 아예 없다.

**radius 문제는 키워드 검색으로 우회한다.** 카테고리 검색(`category_group_code` + `x,y,radius`)은
Planner의 `area`("황리단길·대릉원 일대" vs "경주 시내")에 따라 적정 반경이 자릿수로 달라 튜닝
불가능한 파라미터가 하나 생긴다. 대신 **텍스트 기반 키워드 검색 `"{area} {searchHint}"`를 기본**으로
쓴다 — 카카오가 지역명 텍스트를 스스로 해석하므로 반경이 필요 없고, 이건 현재 코드가
`findBestPlace`에서 이미 쓰는 방식이라 새 파라미터가 아니다. 응답이 부족할 때(예: 5건 미만)만
키워드 검색 결과 좌표의 bounding box를 반경으로 삼아 카테고리 검색을 **보충**한다 — 반경을
설정값이 아니라 데이터에서 유도한다. 네이버 시더도 텍스트 기반이라 반경 파라미터 자체가 없다.

**스타일 modifier 쿼리 확장 — 풀 자체가 스타일을 알아야 한다.** 3원 풀을 위처럼만 구성하면
후보 공급 쿼리가 `"{area} {searchHint}"`로 **스타일 무관**이라, 사용자 키워드는 Curator 선별과
4층 재정렬에서만 작동한다. 그런데 이 둘은 **풀 안에서만** 움직인다 — 황리단길 카페 300개 중
스타일 무관 쿼리로 13개가 들어왔는데 그 안에 루프탑·야경 카페가 하나도 없으면, `연인·감성` 요청에서
Curator는 평범한 13개 중 "그나마 나은 3개"를 고르고 PlaceProfile은 그 3개의 순서만 바꾼다.
인기도에 대해 위에서 쓴 논리("풀에 없는 장소는 사후 랭킹이 못 올린다")가 스타일에도 그대로
적용되는 것이다. **풀이 천장이고 랭킹은 천장 아래에서만 움직인다.** 스타일 영향력이 실질적으로
`SUGGESTED`(파라메트릭) 하나에 의존하게 되는데, 그건 무인지 지역에서 약해지는 바로 그 경로다.

그래서 **후보 공급 쿼리에 스타일 수식어를 넣는다.** 네이버 지역검색·카카오 키워드 검색은 텍스트
기반이라 `"황리단길 루프탑 카페"`, `"황리단길 조용한 카페"` 같은 쿼리가 자연스럽다. 핵심은 **새
어휘를 만들지 않고 §3의 traits 닫힌 태그 집합과 키워드→traits 사전을 그대로 재사용**하는 것이다:

```
사용자 키워드 ──(§3 키워드→traits 사전, 결정론)──► 가점 traits 상위 1~2개 ──► modifier
  COUPLE       → 야경, 루프탑          → "황리단길 야경 카페", "황리단길 루프탑 카페"
  HEALING      → 조용함, 뷰맛집        → "황리단길 조용한 카페", "황리단길 뷰맛집"
  SENSIBILITY  → 한옥, 레트로          → "황리단길 한옥 카페"
  FAMILY       → 주차가능, 아이동반    → "황리단길 주차 식당", "황리단길 아이동반 식당"
```

풀 = **기본 쿼리 결과 ∪ 스타일 쿼리 결과**(대체가 아니라 합집합). 스타일 쿼리에서 온 후보에는
`matchedModifier: "야경"`을 달아 Curator에게 힌트로 넘기되, 프롬프트에 **"검색이 그렇게 주장했다는
힌트일 뿐 검증된 속성이 아니다"**를 명시한다 — 검증은 4층 몫이다.

이렇게 하면 파이프라인에 **의도된 대칭**이 생긴다:

| | 역할 | 어휘 |
|---|---|---|
| CandidateRetrieval (스타일 쿼리) | **재현율** — "야경 카페"라고 *주장하는* 후보를 넓게 끌어옴 (SEO·협찬 포함) | traits 태그 |
| PlaceProfile (4층) | **정밀도** — 블로그 스니펫에 `야경`이 *사실로* 적혀 있는지 검증해 conceptScore 부여 | **같은** traits 태그 |

같은 어휘로 앞에서 던지고 뒤에서 확인하니, 검색 수식어에 편승한 SEO 가게는 4층에서 걸러진다.
§3의 "평가가 아니라 속성만 뽑는다" 논리가 retrieval 편향까지 막아주는 구조다. 사전은 이미 순수
함수·테스트 대상이라 새로 검증할 것도 없고(원칙 1), LLM 호출은 한 번도 늘지 않는다.

- **적용 범위**: 시더와 같이 MEAL/CAFE 우선. 스타일 쿼리는 주로 네이버 지역검색으로 던진다 —
  카카오 키워드 검색은 상호명·카테고리·주소 위주 매칭이라 `"조용한"` 같은 서술어에 약할 가능성이
  높다(아래 실측 항목). ATTRACTION의 스타일은 여전히 Planner의 권역·테마 설계에 대부분 의존한다
  (§11이 인정한 커버리지 공백). `"경주 야경 명소"` 같은 modifier를 카카오에 던지는 것은 실측 후 결정
- **fail-open**: 스타일 쿼리가 0건이면 기본 쿼리 결과만으로 진행. modifier가 결과를 좁히는 방향
  (`가성비`)이라 빈 결과가 흔할 수 있는데, 합집합이므로 풀이 줄어드는 일은 없다
- **캐시 키**는 `(area, slotType, modifier)`. 히트율은 기본 쿼리보다 낮지만 같은 스타일 조합의
  사용자끼리는 여전히 공유된다
- **호출**: 슬롯당 기본 1 + 스타일 1~2 → 네이버 지역검색 코스당 ~6회 → **~12~18회**. 위 "쿼리 변주는
  스타일 modifier에 쓴다"가 정확히 이 예산이다

**확정 전 실측이 필요한 지점 — 네이버 지역검색이 서술어를 어디에 매칭하는가.** 상호명·카테고리만
매칭하면 스타일 쿼리가 거의 빈 결과를 돌려주고(그러면 이 확장은 fail-open으로 조용히 무력화된다),
네이버 플레이스의 리뷰·태그까지 반영하면 강력한 채널이 된다. 4단계 `NaverLocalClient` 실호출 확정
때 `"황리단길 카페"` vs `"황리단길 루프탑 카페"`의 결과 건수·겹침·`total`을 같이 잰다(§15). 결과가
나쁘면 대안은 아래 "추후 개선"의 Planner 태그가 아니라 — 그건 쿼리 어휘 문제이지 채널 문제가
아니므로 — 스타일 축을 retrieval에서 포기하고 Curator `SUGGESTED` + 4층에 맡기는 초안 상태로 돌아가는
것이며, 그 경우에도 이 절의 나머지(시더·커버리지·위조 방어)는 그대로 유효하다.

> **추후 개선 — Planner가 day별 스타일 태그를 직접 낸다.** 위 사전 방식은 결정론적이고 테스트
> 가능하지만 **문맥을 모른다** — 사용자 키워드가 `힐링`이면 3일 내내 같은 `조용함·뷰맛집` modifier를
> 던지는데, Planner는 이미 day 1을 "한옥 골목 산책과 야경", day 2를 "바다 뷰 드라이브"로 갈라놓았다.
> 사전은 day 1에 `한옥·야경`, day 2에 `뷰맛집·주차가능`을 던져야 한다는 것을 알 수 없다.
> Planner의 `dayPlans[]`에 **traits 닫힌 태그 집합에서만 고른 `styleTags`(스키마 `enum` 배열, 최대
> 3개)**를 추가하면 그 문맥이 modifier로 흐른다 — Planner는 셋 중 유일하게 추론 투자를 정당화한
> 에이전트(§6)라 이 판단을 맡길 자리로도 맞고, 태그가 닫힌 집합이라 4층과의 어휘 대칭도 유지된다.
> **그럼에도 V1은 사전으로 가는 이유**: ① 원칙 1 — 결정론으로 되는 일에 LLM을 먼저 쓰지 않는다
> ② Planner 스키마가 커지면 Planner 실패 시 폴백(§9 결정론적 기본 플랜)에도 `styleTags` 기본값이
> 필요해져 실패 표면이 하나 는다 ③ 사전이 문맥을 못 잡아 부족한지는 **실측으로만 알 수 있다** —
> `ai.candidate.adopted{source}`에서 스타일 쿼리 유래 후보의 채택률이 day별로 크게 갈리면(예: 바다
> 권역 day에서 `조용함` 쿼리 후보가 전혀 채택되지 않음) 그때 켤 근거가 생긴다. 켤 때의 형태는
> "사전을 Planner 태그로 대체"가 아니라 **"사전 태그 ∪ Planner 태그"**의 합집합이다 — Planner가
> 태그를 비우거나 실패해도 사전이 바닥을 깐다.

**호출 수와 지연.** 코스당 네이버 지역검색 ~12~18회(3일 × MEAL/CAFE × (기본 1 + 스타일 1~2)),
카카오 검색 ~10~12회(3일 × 슬롯타입 3~4종). 기본 쿼리 캐시 키는 `(area, slotType)`, 스타일 쿼리는
`(area, slotType, modifier)`라 인기 권역은 사용자 간 공유되므로 **장소 단위 캐시(코스마다 조합이
다름)보다 히트율이 훨씬 높다.** 반면 카카오 사후 검증은 후보 45개 전량 → SEEDED + SUGGESTED만
(~15~20개)으로 **줄어**, 카카오 총 호출은 초안과 비슷하거나 적다(§10·§11 갱신). 지연은 Planner
직후 병렬 1라운드(+0.3~0.6s)가 추가되지만 그라운딩 라운드가 얇아져 상쇄된다.

**Curator 입력 토큰 증가는 감수한다.** 후보 목록 ~13개 × 3~4슬롯 × (이름+카테고리+도로명주소) ≈
day당 +600~900토큰. §11 정정에서 "금액을 지배하는 것은 Curator의 **출력**(62%)"이라고 실측했으므로
입력 증가의 금액 영향은 작고, 선별 과제는 회상 과제보다 출력이 안정적이라 의미 재시도가 줄어들
여지도 있다.

**정직하게 남는 한계 — 시더의 협찬 편향.** 리뷰 수 정렬은 마케팅이 몰린 가게를 상위에 올린다.
다만 §3이 `total`에 대해 정리한 논리가 그대로 적용된다 — "협찬이 많다는 건 최소한 사람들이 가는
곳이라는 신호이고, 이 층에 필요한 건 무명 vs 유명의 변별력뿐이다." 시더가 뽑은 후보도 Curator의
컨셉 선별과 4층 traits(사실 속성)를 통과해야 하므로, 편향은 "풀에 들어오는 것"까지만 영향을 주고
"선택되는 것"은 별도 관문이 있다.

### 5-1. 왜 `SlotType` enum이 체류시간·가중치를 소유하는가

```java
public enum SlotType {
    ATTRACTION(90, "관광명소", 0.2, Set.of("AT4", "CT1")),
    MEAL      (75, "맛집",     1.0, Set.of("FD6")),
    CAFE      (60, "카페",     1.0, Set.of("CE7")),
    ACTIVITY  (120, "체험",    0.6, Set.of("AT4", "CT1")),
    VIEWPOINT (45, "전망대",   0.2, Set.of("AT4")),
    SHOPPING  (60, "쇼핑",     0.6, Set.of("MT1", "CS2")),
    WALK      (60, "산책로",   0.2, Set.of("AT4"));

    private final int defaultStayMinutes;
    private final String searchHint;        // 후보 공급 검색어 "{area} {searchHint}" (§5-0) + Curator 실패 시 폴백용
    private final double popularityWeight;
    private final Set<String> allowedCategoryCodes;
}
```

LLM이 내보내는 필드가 하나 줄면 스키마 위반 가능성도 하나 줄고, 튜닝이 코드 리뷰 대상이 된다.
트레이드오프는 "경복궁은 2시간, 작은 카페는 30분" 같은 장소별 차등을 못 준다는 것인데,
카카오 `category_name`으로 사후 보정하면 충분하다(2차 개선 항목).

### 5-2. RouteOptimizer — 완전탐색을 선택한 이유

**`n ≤ 7`이면 브루트포스.** `7! = 5,040` 순열 × 6회 거리계산 ≈ **1ms 미만**. 3일이면 3ms. 무료다.

nearest-neighbor + 2-opt를 쓰지 **않는** 이유:
- 최적해가 공짜인데 근사할 이유가 없다
- 구현이 더 길고(NN + 2-opt swap 루프 + 수렴 조건), 최적성 보장이 없어 테스트로 "옳음"을 증명하기 어렵다
- **더 복잡한데 더 나쁘다.** 알고리즘 선택은 문제 규모의 함수라는 것이 이 결정의 요지다

방어선으로 `n ≥ 8`이면 NN + 2-opt 폴백. 임계값은 설정으로 노출하고, 이 레포에 이미 있는
`@Tag("benchmark")` / `./gradlew benchmarkTest` 체계로 `n=6,7,8` 소요시간을 실측해 근거를 남긴다.

**순수 TSP가 아니다 — 시간창 제약을 비용 함수에 넣는다.**

```
cost(순열) = Σ 이동거리(km) × DISTANCE_WEIGHT
           + Σ_{MEAL} 시간창위반분 × MEAL_PENALTY_PER_MIN
           + max(0, 종료시각 − dayEndTime)분 × OVERRUN_PENALTY_PER_MIN

점심 윈도우 11:30~13:30,  저녁 윈도우 17:30~19:30
```

초소형 TSPTW(Time Window) 인스턴스이고 `n ≤ 7`에서 완전탐색은 **정확해**를 준다. 결과적으로
"day당 식사를 적절한 시간대에"가 LLM 판단이 아니라 **알고리즘 불변식**이 된다(원칙 1).

**시간 모델**

```
t[0] = dayStartTime                                     (Planner 제공, 기본 09:30)
t[i] = t[i-1] + stayMinutes(type[i-1]) + travelMinutes(p[i-1], p[i])
travelMinutes = ceil(distanceKm / 유효속도 × 60) + 고정오버헤드
```

| travelMode | 유효속도 | 고정 오버헤드 | 근거 |
|---|---|---|---|
| `WALK`(뚜벅이) | 12 km/h | 10분 | 도보+대중교통 혼합, 환승·대기 |
| `CAR`(자차) | 25 km/h | 5분 | 도심 평균 주행 + 주차 |
| 미지정 | 15 km/h | 8분 | 중간값 |

**haversine은 직선거리라 실제 도로 거리보다 짧다**(도심 우회계수 통상 1.2~1.4). 우회계수 파라미터를
따로 두는 대신 **유효속도를 낮춰 흡수**했다 — 파라미터 하나를 아끼고, 두 값이 서로 상쇄되는 튜닝
혼란을 피한다.

- **출력 정규화**: `startTime`은 5분 단위 올림. `09:37`은 사용자가 보는 필드라 어색하다.
- **하루 초과**: 종료가 `dayEndTime`(기본 21:00) 초과 시 ① 체류시간 0.8배 축소 재계산 ② 그래도
  초과면 후순위 슬롯 드롭 ③ day당 최소 3개 아래로는 드롭 중단.
- **거리 계산은 haversine**(반경 6371.0088km). 한국 도시 규모(<50km)에서 유클리드 근사와의 차이는
  0.1% 미만이지만, haversine은 20줄이고 CPU 비용이 무의미하다. **최적화가 필요 없는 곳을
  최적화하지 않고, 근사 오차라는 변수를 아예 없앤다.**

### 5-3. CriticAgent — 블라인드 리뷰로 확증편향을 완화한다

> **V1 범위 밖.** 설계는 남기되 지금은 만들지 않는다 — 근거는 §10 "Critic·Refiner를 V1에서
> 제외한 이유". 아래 내용은 나중에 재검토할 때 참고할 설계다.

같은 모델이 자기가 뽑은 결과를 자기 지식으로 채점하면 확증편향이 그대로 남는다(원칙 2).
새 데이터 없이 독립성을 확보하는 세 가지 장치를 둔다.

1. **블라인드 리뷰** — Critic에게 Curator의 선정 근거를 **주지 않는다.** 최종 코스(장소명 + 카테고리
   + 시각 + `traits`)만 보고 판단하게 한다. 근거를 미리 보여주면 그 프레이밍에 끌려간다.
2. **외부 근거 제공** — `traits`를 함께 넘긴다. **Critic이 처음으로 파라메트릭 지식 밖의 근거를 갖는다.**
3. **모델 분리 + 결정론** — Critic만 다른 모델(설정 한 줄)로 돌리고, `temperature 0` + `seed` 고정으로
   채점을 재현 가능하게 만든다. 재현 가능해야 A/B 비교가 성립한다.

`IssueType`에 `DUPLICATE`·`TIME_OVERLAP`·`MISSING_MEAL`·`WRONG_CATEGORY`가 **없는 것이 핵심**이다.
전부 앞 단계가 구조적으로 보장한다.

```java
public enum IssueType { KEYWORD_MISMATCH, CONCEPT_MISMATCH, LOW_APPEAL }
```

> **미확정**: 위 세 가지 장치는 "무엇을 검사하는지"만 정의할 뿐, **각 `IssueType`을 언제 부여하는지에
> 대한 구체적 판단 기준(rubric)은 아직 없다.** `prompts/critic.md` 작성 시점에 반드시 명문화해야
> 한다 — 그렇지 않으면 "적절히 판단해라"는 느슨한 지시만 남아 재현성도, 설명 가능성도 없어진다.
> 이번 설계 단계에서는 확정하지 않는다.

### 5-4. CandidateRefiner를 LLM이 아니라 결정론으로 두는 이유

> **V1 범위 밖.** Critic이 없으면 이 단계를 트리거할 입력 자체가 없다 — Critic과 함께 제외한다.

Critic이 지목한 `(day, placeIndex)`의 장소를 **같은 슬롯 후보 풀의 차순위로 교체**하고
RouteOptimizer를 재실행한다. LLM을 쓰지 않는다.

1. 후보 풀은 이미 Curator(LLM)가 취향을 반영해 만들었고 인기도·컨셉 점수로 정렬까지 됐다.
   차순위 교체도 여전히 근거 있는 선택이다.
2. LLM 호출 1회와 실패 모드 1개가 통째로 사라진다.
3. 교체 후 동선·시간을 반드시 재계산해야 하는데, LLM에게 맡기면 지그재그 동선이 다시 생긴다.
4. 순수 함수라 단위 테스트가 완전히 결정론적이다.

---

## 6. 벤더 중립 LLM 추상화

**LLM 벤더는 OpenAI로 확정됐다.** 이 절의 초안은 "Gemini는 고정이 아니고 OpenAI로 전환할 가능성이 높다"는 것을 지배적 제약으로 삼았는데, 전환이 확정되면서 그 제약은 **이미 일어난 전환**이 됐다.

그렇다고 포트가 불필요해지지는 않는다. 포트를 두는 근거는 원래 두 가지였고 **두 번째는 그대로 남기 때문**이다 — (1) 향후 벤더 전환 대비, (2) 테스트 가능성(바로 아래). 다만 첫 번째가 소진됐으므로 **어댑터는 OpenAI 하나만 만든다.** Gemini 어댑터를 함께 유지해 A/B를 돌리는 선택지는 채택하지 않는다: 벤더 교체 효과를 분리 측정하는 목적은 §15의 3점 baseline 측정이 이미 달성하고, 그 측정이 끝나면 Gemini 경로는 §13 8단계에서 삭제된다.

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
> 싶은 유혹이 있지만, §4 예시(`{ "day": 1, "slots": [...] }`)처럼 반드시 객체로 감싸야 한다.
> `resources/schemas/*.json`을 쓸 때 전부 이 규칙을 지킨다.

`responseJsonSchema`를 벤더 타입이 아닌 **JSON 문자열**로 받는 것이 벤더 중립의 핵심이다.
벤더마다 이걸 받는 창구가 다르기 때문이다 — OpenAI는 `response_format: json_schema`, Gemini는
`GenerateContentConfig.responseJsonSchema(...)`. 어느 쪽이든 **어댑터가 JSON 문자열을 자기 벤더의
타입으로 옮기면 되고, 포트는 그 차이를 모른다.** 스키마는 `resources/schemas/*.json`에 둔다.

### 포트가 선택이 아니라 필수인 이유 — 테스트

벤더가 확정된 지금, 포트를 정당화하는 근거는 **이것 하나로 충분하다.**

`com.google.genai.Client`는 **`public final class`**이고 `models`도 **`public final` 필드**다.
Mockito로 목킹할 수 없다. 지금 코드에서 LLM 호출부의 단위 테스트가 사실상 불가능한 이유가 정확히
이것이고, [AI-HALLUCINATION-GEMINI.md](hallucination/AI-HALLUCINATION-GEMINI.md)의 측정 하네스가
Spring 컨텍스트 없이 `new GeminiService(...)`를 수동 조립해 **실제 API를 때리는 방식**을 택한 것도
같은 제약 때문이다. 포트를 두면 에이전트(V1: Planner·Curator·PlaceProfile 3개)의 테스트가
**벤더 SDK 타입을 한 개도 import하지 않는다.** "추상화를 위한 추상화"가 아니라 테스트 가능성이라는
구체적 대가를 받는다. 이 근거는 벤더가 무엇으로 확정되든 사라지지 않는다.

### 설정 외부화

```yaml
llm:
  provider: openai            # 확정. @ConditionalOnProperty 로 어댑터 선택 (구조는 유지)
  timeout-ms: 20000
  max-concurrent-calls: 2     # ★ RPM/TPM 티어 방어. 상위 티어 전환 시 이 값만 올린다
  retry: { attempts: 3, initial-delay-seconds: 0.5, max-delay-seconds: 4.0, jitter: 0.3 }
  agents:
    planner:       { model: gpt-5.6-luna, temperature: 0.7 }
    curator:       { model: gpt-5.6-luna, temperature: 0.9 }
    place-profile: { model: gpt-5-nano,   temperature: 0.2 }
    # critic: V1 범위 밖 (§10 참고). 재검토 시 { temperature: 0.0, seed: 42 }
```

모델 배정 근거는 [steps/STEP-0-prerequisites.md](steps/STEP-0-prerequisites.md)에 있다. 요지는
**"추론 난이도"가 아니라 "틀렸을 때의 파급 ÷ 토큰량"으로 골랐다**는 것이다 — Planner는 파급이
가장 큰데(권역이 틀리면 그 아래 Curator와 카카오 검색이 전부 오염된다) 호출 1회에 출력 350토큰이라
가장 싸게 투자할 수 있고, Curator는 환각률에 직결되므로 지식 폭이 넓은 최신 세대를 쓰며,
PlaceProfile은 닫힌 태그 분류라 세대 이점이 작은데 입력 토큰의 76%를 차지해 최저가로 내렸다.

**agent별 temperature를 다르게 두는 근거** — 현재 코드의 단일 `0.3`은 "장소 선정은 다양해야 하고
판정은 일관돼야 한다"는 상충 요구를 하나로 뭉갠 값이다. 특히 **Curator를 0.9로 올리는 건 후보
3개가 서로 비슷하면 대체재로서 의미가 없기 때문**이고, **PlaceProfile을 0.2로 낮추는 건 속성 추출은
창의성이 아니라 충실성이 필요하기 때문**이다.

**agent별 `model`을 다르게 두는 근거도 같은 논리다.** 셋 중 추론 이득이 실제로 있는 건
Planner(컨셉·권역 설계)뿐이고, Curator(지역 상식 회상)와 PlaceProfile(속성 추출)은 추론 이득이
적으면서 **토큰 비중은 가장 크다**(§11). 그래서 Planner만 상위 모델, 나머지는 mini급으로 둔다.
구체 모델 ID와 단가는 착수 시점에 공식 가격표를 확인해 확정한다.

**`max-concurrent-calls` 세마포어가 rate limit 대응의 전부다.** 2로 두면 day별 Curator 3개가
2라운드로 나뉘어 실행된다(+3~6초). 429가 나는 대신 느려질 뿐이고, 티어 상향은 설정값 한 줄이다.
**"Curator를 day별 병렬로 만들지, 1회 통합으로 만들지"를 코드 구조로 결정하지 않는 것**이 요점이다.

초안에 있던 `thinking-budget`은 **Gemini 전용 옵션이라 제거했다.** OpenAI에 대응하는 추론 강도
설정이 있다면 어댑터 내부에서 `model`과 함께 다루고, 없다면 포트에서 사라진 채로 둔다 — 어댑터가
모르는 옵션을 조용히 버리는 건 정상 동작이며, 이것이 포트를 최소 공통분모로 유지하는 대가다.

### 재시도 2계층

| 계층 | 대상 | 구현 |
|---|---|---|
| 전송 | 429 / 5xx | 어댑터가 `llm.retry` 설정으로 지수 백오프 + 지터 |
| 의미 | **200 OK인데 깨진 JSON** | `LlmResponseParser` 실패 시 **1회만** 재시도. temperature를 낮추고 "스키마 위반, 수정하라"를 덧붙임 |

JSON 스키마를 디코딩 레벨에서 강제하므로 파싱 실패율이 near-zero다. 2회 이상 시도는 지연 예산만 태운다.

**resilience4j를 추가하지 않는다.** 전송 재시도는 위로 충분하고, 서킷브레이커를 얹으면 LLM 장애 시
"코스를 아예 못 만드는" 상태가 되는데 아래 폴백 전략이 어차피 에이전트 실패를 개별 흡수한다.

### `LlmClient` 구현체는 직접 짜되, 전송 계층은 Spring AI로

**오케스트레이션(Planner→Curator→Grounding→PlaceSignal→RouteOptimizer)은 어떤 프레임워크를
쓰든 항상 직접 짜야 하는 도메인 로직이다.** Spring AI(`ChatModel`/`ChatClient`)나 LangChain4j(`ChatLanguageModel`)가
실제로 대신해주는 부분은 "벤더 SDK 차이를 가리는 통일 인터페이스"뿐이고, 이건 이미 `LlmClient`로
직접 만들어뒀다. 두 프레임워크의 나머지 기능(툴 자율 호출, RAG용 `VectorStore`, 대화 메모리)은
이 파이프라인에 대응물이 없다 — 툴 자율 호출은 §14에서 명시적으로 기각했고, RAG는 이 파이프라인
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
- `LlmClient` 인터페이스를 유지하는 한, 에이전트 코드(`PlannerAgent`/`CuratorAgent`/`PlaceProfileAgent`)는
  Spring AI의 존재 자체를 모른다. 앞서 확보한 테스트 가능성 근거(`com.google.genai.Client`가 final이라
  Mockito로 못 묶는 문제를 포트로 우회한 것)가 그대로 유지된다.
- 헥사고날 아키텍처에서 "포트는 직접 정의, 어댑터 내부 구현은 서드파티 SDK"는 흔히 권장되는
  형태다 — 프레임워크를 전역에 노출하지 않고 어댑터 하나에 가둬 쓰는 것이다.
- 어댑터 내부 구현이 raw SDK 호출에서 Spring AI 호출로 바뀌어도 `LlmCall`/`LlmResponseParser` 등
  포트 바깥의 코드는 전혀 바뀌지 않는다.

**착수 전 반드시 검증해야 하는 것** — 실패하면 이 절충 자체가 성립하지 않는다.

> 초안에는 검증 항목이 둘이었다. 첫 번째(~~Spring AI의 Gemini 통합이 API 키 방식을 지원하는가,
> Vertex AI 전용인가~~)는 **벤더가 OpenAI로 확정되면서 물음 자체가 사라졌다.**

**Spring AI의 구조화 출력이 스키마를 디코딩 레벨에서 강제하는가**(OpenAI의
`response_format: json_schema`를 그대로 노출하는가), 아니면 프롬프트 지시 기반 JSON 모드로
떨어지는가. 후자면 §6 위쪽에서 확보한 "파싱 실패율 near-zero" 전제가 깨져 의미 재시도 비율이
올라간다 — 그리고 이건 추상적 우려가 아니라 **이미 실측된 문제**다(단일 호출 구조의 JSON 파싱
실패율 28.6%, §10 참고).

**막히면**: Spring AI를 포기하고 **OpenAI 공식 Java SDK(`com.openai:openai-java`)로 어댑터를
구현한다.** 포트는 그대로이므로 어댑터 내부 구현이 무엇이든 `LlmCall`·`LlmResponseParser` 등
포트 바깥 코드는 전혀 바뀌지 않는다 — "프레임워크를 검증 없이 전면 채택하지 않고, 실제 기능
지원 여부를 확인한 뒤 도입했다"는 것 자체가 이 결정의 근거로 남는다.

**LangChain4j가 아니라 Spring AI를 고르는 이유**: 이 레포는 이미 전역에 `@Bean`·
`@ConfigurationProperties`·`application.yml` 기반 Spring 관용구가 깔려 있다(`SecurityConfig`,
`RedisConfig` 등). Spring AI는 같은 관용구라 자연스럽게 붙지만, LangChain4j는 Spring Boot starter가
있어도 별도 생태계에서 이식된 프로젝트라는 이질감이 남는다.

---

## 7. 프롬프트 전략 — 95줄 중 절반은 그냥 사라진다

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

**약 45줄이 사라지고 남는 것은 순수하게 "취향과 컨셉"뿐이다.** 그게 정확히 LLM에게 시켜야 할 일이다.
이것이 프롬프트 분리 전략의 본질이지 단순 3등분이 아니다.

덤으로 현재 프롬프트의 결함도 해소된다:
- 규칙 5는 `placeLocation`을 채우라 하는데 `PlaceDto`에 그 필드가 없고 규칙 12는 스키마 외 필드를
  금지한다 — **실행되지 않는 죽은 지시문**이다.
- 프롬프트 JSON 예시에 **trailing comma**가 있다(54·102·106줄). 유효하지 않은 JSON을 예시로 보여주고
  있어 파싱 실패의 유력한 원인이다. **파싱 실패율 28.6%(§10)의 유력한 원인이 바로 이것이다.**
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

---

## 8. 트랜잭션 경계

```java
// @Transactional 제거
public AICourseCreateResponse createAICourse(AICourseCreateRequest request) {
    Long userId = userService.getCurrentUserId();                     // ① 요청 스레드에서 미리 확보
    CourseBrief brief = CourseBrief.from(request);
    CourseDraft draft = aiCoursePipeline.generate(brief);              // ② 트랜잭션 밖: LLM + 카카오 + 네이버
    Long courseId = aiCoursePersister.persist(brief, draft, userId);   // ③ 짧은 트랜잭션
    return new AICourseCreateResponse(courseId);
}
```

**필수 디테일 3가지**

1. **`AiCoursePersister`는 반드시 별도 빈.** 같은 클래스에 `@Transactional` 메서드를 두고 내부
   호출하면 Spring AOP 프록시를 우회해 트랜잭션이 아예 안 걸린다(self-invocation). 이 레포엔 이미
   `MyCourseDetailReader`라는 동일한 분리 선례가 있다.
2. **`userId`를 미리 확보해 명시적으로 넘긴다.** `getCurrentUserId()`는 `SecurityContextHolder`를
   읽는데, 파이프라인이 다른 스레드에서 돌면 `SecurityContext`가 **전파되지 않아** 인증 정보가 사라진다.
3. **삽입 순서 = 표시 순서.** `DaySchedule.places`에 `@OrderBy("id ASC")`가 걸려 있고 별도 `sequence`
   컬럼이 없다. `OptimizedDay.places` 순서 그대로 `save()`해야 동선 순서가 재현된다.

**부수 효과: 커넥션 점유가 최악 360초 → ~50ms.** 이것만으로도 이 리팩터링은 값을 한다.

> **후속 과제(범위 밖)**: `Place`/`DaySchedule`이 `GenerationType.IDENTITY`라 JDBC 배치 INSERT가
> 원천 불가능하다(Hibernate가 즉시 INSERT 후 생성 키를 받아야 함). ~20건 규모라 지금은 무시해도
> 되지만, 배치가 필요해지면 `SEQUENCE` 전환이 선행돼야 한다.

---

## 9. 부분 실패 전략 — degrade, don't fail

| 실패 지점 | 대응 |
|---|---|
| Planner 실패 | 의미 재시도 1회 → **결정론적 기본 플랜**(`area = location`, 슬롯 `[ATTRACTION, MEAL, CAFE, ATTRACTION, MEAL]`, 09:30 시작) |
| Planner 구조 위반 (day 수 불일치, MEAL 없음) | **코드로 보정** — day 부족분은 마지막 day 복제, MEAL 없으면 3번째에 삽입, 슬롯 3~6개로 clamp. LLM 재호출 없음 |
| **네이버 지역검색(시더) 실패** | 그 슬롯은 `SEEDED` 없이 카카오 목록 + 파라메트릭만으로 진행 (fail-open) |
| **카카오 후보 검색(커버리지) 실패** | 그 슬롯은 목록 없이 진행 — Curator가 파라메트릭 제안(`SUGGESTED`)만 내고 그라운딩이 검증 (= 초안 구조) |
| **후보 공급 전면 실패** | 빈 목록으로 Curator 실행. 초안 구조로 완전 degrade. hard fail 아님 |
| Curator가 `LISTED`/`SEEDED`라 주장하나 목록과 불일치 | **`SUGGESTED`로 강등**해 그라운딩 검증 경로로 (버리지 않는다 — 실존할 수 있다). 강등 건수 메트릭 기록 |
| Curator 일부 day 실패 | 그 day만 **후보 목록에서 결정론적으로 채움** — 슬롯당 `SEEDED` 우선, 없으면 `LISTED` 상위 3개. 목록마저 없으면 카카오 카테고리 검색 폴백(`{area} {slotType.searchHint}`) |
| Curator 전 day 실패 | 전 day에 위 폴백 → 큐레이션 품질↓, 코스는 유효 |
| 카카오 개별 후보 실패/무매치/점수 미달 | 그 후보만 탈락 (슬롯에 다른 후보 존재) |
| **네이버 개별 조회 실패** | 인기도·traits 없이 카카오 점수만으로 랭킹 |
| **네이버 전면 장애** | **3·4층 전체 스킵.** 품질은 이 작업 이전 수준이지만 정상 응답 |
| PlaceProfile LLM 실패 | traits를 비우고 진행. 인기도(3층)는 그대로 유효 |
| 슬롯 후보 전멸 | 슬롯 드롭 → day 장소가 3개 미만이면 카테고리 검색으로 보충 |
| **전 day 장소 0개 (카카오 전면 장애)** | **`AI_GROUNDING_FAILED`(503) — 유일한 hard fail** |
| 데드라인 임박 | PlaceProfile 진입 전 잔여 예산 확인 → 부족하면 PlaceProfile 스킵(traits 없이 진행) |

> Critic·Refiner는 V1 범위 밖이라 이 표에 없다. 설계상 실패 처리(§5-3/§5-4 재검토 시 참고):
> Critic 실패는 draft를 그대로 반환, Refiner는 후보 풀 소진 시 교체 없이 원본 유지.

**카카오 전면 장애만 hard fail인 이유**: 좌표 없는 코스는 이 기능의 핵심 가치(지도 표시·동선)를 잃는다.
**지금 코드가 `0.0/0.0`으로 저장해 성공을 위장하는 것이 정확히 그 실수다.**

**네이버는 절대 hard fail이 아니다.** 인기도와 속성은 "있으면 더 좋은" 보조 신호이지 코스 성립
조건이 아니다. 보조 신호를 필수 의존성으로 승격시키면 외부 장애 표면만 넓어진다. 같은 이유로
네이버 전용 ErrorCode도 만들지 않는다 — 사용자에게 도달하는 실패가 아니기 때문이다.

### 신규 `AiCourseErrorCode`

`ErrorCode` 인터페이스를 구현하는 별도 enum이라 `GlobalExceptionHandler`는 수정하지 않는다.

```java
AI_PLAN_FAILED      ("AI 코스 설계에 실패했습니다. 잠시 후 다시 시도해주세요",      SERVICE_UNAVAILABLE)
AI_RESPONSE_INVALID ("AI 응답 형식이 올바르지 않습니다. 잠시 후 다시 시도해주세요", SERVICE_UNAVAILABLE)
AI_GROUNDING_FAILED ("장소 정보를 확인하지 못했습니다. 잠시 후 다시 시도해주세요",   SERVICE_UNAVAILABLE)
AI_COURSE_TIMEOUT   ("AI 코스 생성이 지연되고 있습니다. 잠시 후 다시 시도해주세요", GATEWAY_TIMEOUT)
AI_COURSE_BUSY      ("요청이 많습니다. 잠시 후 다시 시도해주세요",                TOO_MANY_REQUESTS)
```

---

## 10. 병렬화 · 지연 예산

### 스레드풀 2개 (기존 `AsyncConfig` 스타일 준수)

```java
@Bean("aiAgentExecutor")        // core 4 / max 8  / queue 50  / prefix "ai-agent-"
@Bean("placeGroundingExecutor") // core 8 / max 16 / queue 200 / prefix "place-grounding-"
```

카카오와 네이버는 **같은 풀을 공유**한다. 둘 다 짧은 I/O이고 순차 실행이라 동시에 경합하지 않는다.
풀을 셋으로 나눠도 유휴 스레드만 늘어난다.

**LLM과 벌크헤드로 나누는 이유**: 외부 장소 API가 느려질 때 그 대기가 LLM 슬롯을 잠식하면 안 된다.
LLM은 3~10초짜리 소수, 장소 API는 0.15~0.3초짜리 다수 — 최적 풀 크기가 다르고 포화 시 대응도 달라야 한다.

**`CallerRunsPolicy`의 함정 — 이 레포에는 이미 실측된 선례가 있다.** 현재 남아 있는 executor는
`courseImageCleanupExecutor` 하나뿐이고, 이건 커밋 이후 후처리라 caller-runs가 응답을 막지 않는다.
문제는 **응답 경로 위의 작업**이다 — 과거 `cloudFrontSigningExecutor`가 `CallerRunsPolicy`를 쓰다가
요청 스레드까지 서명 CPU를 떠안으면서 **커넥션 점유가 오히려 악화되는 것이 실측됐고**, 그래서
`AbortPolicy` + 요청 단위 세마포어 게이트로 전환됐다
([callerruns-verification.md](../connection-pool-bottleneck/stage0/production/callerruns-verification.md),
[abortpolicy-gate-verification.md](../connection-pool-bottleneck/stage0/production/abortpolicy-gate-verification.md)).
그 executor 자체는 이후 "코스당 서명 1회"로 fan-out이 사라지면서 존재 이유를 잃어 제거됐다
([run-e-infra-removed.md](../connection-pool-bottleneck/stage1/run-e-infra-removed.md)).

**여기서 가져올 교훈은 정책 이름이 아니라 판단 기준이다: caller-runs가 이득인지 손해인지는 그
작업이 CPU냐 I/O냐, 응답 경로 위냐 아래냐로 갈린다.** 장소 API는 I/O이고 응답 경로 위에 있다 —
**요청 스레드가 그 I/O를 직접 수행하면 사실상 순차 실행으로 퇴화해 지연 예산이 붕괴한다.** 그래서
정책은 `CallerRunsPolicy`로 유지하되(거부보다 느린 성공이 낫다), **파이프라인 전체에 하드 데드라인**을
건다 — `CompletableFuture.allOf(...).get(remainingMs, MILLISECONDS)`. 데드라인이 없으면 caller-runs는
"느린 성공"이 아니라 "무한정 느린 성공"이 된다.

### 외부 API 호출량과 방어

| | 카카오 | 네이버 블로그 (3·4층) | 네이버 지역검색 (시더, §5-0) |
|---|---|---|---|
| 요청당 호출 | 후보 검색 ~10~12회 + `SEEDED`/`SUGGESTED` 검증 ~15~20회 = **~25~32회** (초안 ~45회에서 감소) | ~35회 (카카오 생존 후보만) | **~12~18회** (3일 × MEAL/CAFE × (기본 1 + 스타일 modifier 1~2)) |
| 타임아웃 | connect 2s / response 3s | 1.5s | 1.5s |
| 실패 시 | 후보 검색: 슬롯 목록 없이 진행 / 검증: 후보 개별 탈락 | 신호 없이 진행 (fail-open) | 시드 없이 진행 (fail-open) |
| 캐시 | `kakao:place:{sha1}` TTL 7일 (검증) / `kakao:list:{area}:{slot}` TTL 7일 (후보 검색) | `naver:blog:{sha1}` TTL 7일 | `naver:local:{area}:{slot}[:{modifier}]` TTL 7일 — **`(area, slotType)` 키라 장소 단위 캐시보다 히트율이 훨씬 높다** (스타일 쿼리는 modifier가 키에 붙어 조금 낮음) |

**네이버를 카카오 이후에 배치하는 두 가지 이유**: ① 정확도 — 카카오가 확정한 공식 상호명으로
검색해야 `total`이 정확하다. ② 쿼터 절약 — 카카오에서 탈락한 후보(~20%)에는 아예 호출하지 않는다.
병렬 실행하면 ~1초를 아낄 수 있지만 두 이점을 모두 잃는다. **쿼터가 지연보다 희소한 자원**이라 순차를 택한다.

**`KakaoConfig`의 `WebClient`에 connect 2초 / response 3초 / 커넥션풀을 명시한다.** 현재
`block(20초)`는 후보 45개 fan-out 환경에서는 자살행위다 — 하나가 20초를 잡아먹으면 전체 예산이
날아간다. 후보가 3배로 늘어난 만큼 개별 실패가 저렴해졌으니 타임아웃을 공격적으로 줄이는 것이 맞다.

### 지연 예산 (3일 / 슬롯 5개 per day / 후보 3개, V1 = PlaceProfile까지)

| 단계 | 병렬도 | 구간 | 누적 |
|---|---|---|---|
| Planner | 1 | 2.5~4.0s | 4.0s |
| **CandidateRetrieval — 네이버 ~12~18건(기본+스타일) + 카카오 ~12건 / 풀 12** | ~12 | 0.4~0.8s | 4.8s |
| Curator × 3일 (입력에 후보 목록 포함) | 3 (무료 티어 세마포어 2면 2라운드) | 3.0~6.0s | 10.8s |
| Grounding — 카카오 ~15~20건 (`SEEDED`+`SUGGESTED`만) / 풀 12 | ~12 | 0.3~0.6s | 11.4s |
| 네이버 조회 35건 / 풀 12 (+ 인기도 계산은 순수함수, 0초) | ~12 | 0.6~1.2s | 12.6s |
| PlaceProfile (LLM 배치) | 1~2 | 2.0~4.0s | 16.6s |
| RouteOptimizer | – | <10ms | 16.6s |
| DB 저장 (짧은 트랜잭션, ~20 INSERT) | – | 30~80ms | **16.7s** |

후보 공급 라운드가 추가됐지만 그라운딩 라운드가 얇아져(45건 → 15~20건) 누적 예산은 초안 대비
+0.2s 수준이다.

- **p50 ≈ 12~16초** (무료 티어 세마포어 2면 15~22초)
- **p95 ≈ 22~30초**
- 현재 구조 p50 ≈ 8~11초

Critic·Refiner를 V1에서 제외하면서 지연 예산도 함께 줄었다(원래 설계는 이 위에 Critic 2.0~4.0s +
Refiner <10ms + 재최적화가 더해져 p50 16~20초, p95 30~40초였다).

**60초는 넘지 않는다. 하지만 60초가 문제가 아니다.** 동기 요청으로 15~30초를 잡으면 동시 200건에서
`maxThreads=200`이 통째로 AI 코스 생성에 묶인다. 이 레포는 이미 동시성 200에서
`tomcat_threads_busy`가 maxThreads 경계에 닿는 것을 실측한 이력이 있다
([TASK-PRESIGN-BOTTLENECK.md](../connection-pool-bottleneck/TASK-PRESIGN-BOTTLENECK.md)).

**결정: 동기 API 계약을 유지한 채 먼저 완성해 실측하고, p95가 목표를 넘는 것을 데이터로 확인한 뒤
202 Accepted + 폴링으로 전환한다.** 그래야 전환이 "숫자에 근거한 결정"이 된다. 이 레포는 이미
before/after 실측 문화가 있으므로(`docs/tasks/*`) 그 관례를 따른다.

### Critic·Refiner를 V1에서 제외한 이유

애초 설계는 PlaceProfile과 Critic을 "컨셉 적합성을 다루되 근거가 다른" 두 층으로 놓고 A/B로
비교할 계획이었다(PlaceProfile은 외부 블로그 속성, Critic은 자기 파라메트릭 지식). 하지만 실제로
저울질해보면 Critic 쪽 근거가 생각보다 얇다.

1. **traits 커버리지가 좁다.** PlaceProfile은 비용상 MEAL/CAFE 슬롯에만 적용된다(§11). Critic이
   ATTRACTION/VIEWPOINT/WALK 슬롯을 채점할 때는 참고할 `traits`가 없어, **Curator와 같은
   파라메트릭 지식으로 다시 한번 추측하는 것**에 가깝다 — 원칙 2가 경고한 "지식을 추가하지 않는
   재구성"이 정확히 여기서 재발한다.
2. **판단 기준이 아직 없다.** §5-3에 "미확정"으로 남긴 것처럼, `IssueType`을 언제 부여할지에
   대한 구체적 rubric이 없다. 기준이 안 정해진 채로는 재현성도 설명 가능성도 확보할 수 없다.
3. **딸려오는 서브시스템이 작지 않다.** Critic 하나가 아니라 CandidateRefiner(후보 교체 + 재최적화)
   까지 세트다. 실패 모드·폴백 규칙·테스트가 함께 늘어나는데, 그 대가로 얻는 게 "아마도 약간의
   품질 향상"이다.
4. **효과를 측정할 인프라가 없다.** §15에서 골든 데이터셋 평가를 범위 밖으로 미뤄뒀다. 그 인프라
   없이는 Critic을 만들어도 "진짜 도움이 됐는지"를 확인할 방법이 없다.
5. **더 크고 확실한 문제가 이미 실측됐다.** [AI-HALLUCINATION-GEMINI.md](hallucination/AI-HALLUCINATION-GEMINI.md)에서
   단일 Gemini 호출의 **JSON 파싱 실패율이 28.6%**로 나왔다. 이건 추정이 아니라 실측이고, 사용자가
   AI 코스 생성을 4번 시도하면 1번 이상 503을 받는다는 뜻이다. 불확실하고 작은 문제(Critic이 잡을
   나머지 컨셉 미스매치)보다 확실하고 큰 문제(파싱 실패)에 먼저 투자하는 게 맞다.

**결정: Critic·Refiner는 V1에서 제외한다.** 설계(§5-3, §5-4)는 그대로 남기고, 아래 조건 중 하나가
충족되면 재검토한다.
- 골든 데이터셋/LLM-as-judge 평가 인프라가 생겨 "Critic이 실제로 개선하는가"를 측정할 수 있을 때
- 실제 사용자 피드백에서 컨셉 미스매치 불만이 반복될 때

이번 결정으로 V1은 Planner·Curator·Grounding·PlaceSignal(PopularityScorer+PlaceProfileAgent)·
RouteOptimizer 다섯 단계로 확정된다.

---

## 11. 비용

3일 여행 기준. LLM 호출은 `2 + days`회 (V1 = Planner + Curator×days + PlaceProfile, Critic 제외).

> **아래 토큰 수치는 `gemini-2.5-flash` 기준으로 산출한 것이다.** 벤더가 OpenAI로 확정됐으므로
> (§6) **금액 환산은 착수 시점에 OpenAI 공식 가격표로 다시 계산해야 한다.** 다만 호출 횟수와
> 토큰 규모의 상대적 구조(입력이 9배가 되고 그 대부분이 4층에서 나온다)는 토크나이저 차이로
> 소폭 흔들릴 뿐 벤더에 종속되지 않으므로, 아래의 비용 트레이드오프 논의는 그대로 유효하다.

| | 현재 | 신규 (V1) |
|---|---|---|
| LLM 호출 | 1회 | **5회** (Planner 1 + Curator 3 + PlaceProfile 1) — 후보 공급은 LLM을 쓰지 않으므로 불변 |
| 입력 토큰 | ~1,400 | ~13,800 (PlaceProfile의 스니펫이 대부분 + **Curator 후보 목록 day당 +600~900**, §5-0) |
| 출력 토큰 | ~600 | ~1,700 |
| 카카오 호출 | 15~18회 | ~25~32회 (후보 검색 ~12 + 검증 ~15~20. 캐시 히트 50% 시 ~15회) — **초안 ~45회에서 감소** |
| **네이버 블로그 호출** | **0회** | **~35회** (캐시 히트 50% 시 ~18회) |
| **네이버 지역검색 호출** | **0회** | **~12~18회** (기본 ~6 + 스타일 modifier ~6~12. `(area, slotType[, modifier])` 캐시라 인기 권역 히트율 높음) |

Critic까지 포함하면 6회(`3 + days`)·입력 ~13,000·출력 ~2,000이 되는데, V1에서는 제외했으므로
(§10 "Critic·Refiner를 V1에서 제외한 이유") 위 5회 기준이 실제 비용이다.

> **[정정] 토큰 수 기준과 금액 기준이 다르다.** 아래 "입력 토큰이 9배가 되는 것이 최대 비용"이라는
> 서술은 **토큰 수로는 맞지만 금액으로는 틀렸다.** 0단계에서 실제 단가를 대입해보니 출력 단가가
> 입력의 8배라 두 비중이 어긋난다 — PlaceProfile 입력은 토큰의 76%인데 **금액으로는 19%**이고,
> 금액을 지배하는 것은 **Curator의 출력(62%)** 이다.
> 이는 아래 완화 수단 1번("MEAL/CAFE 슬롯에만 적용")의 절감 효과 추정도 함께 낮춘다 — ATTRACTION
> 슬롯을 PlaceProfile 대상에서 빼서 아낄 수 있는 금액은 §11이 계산한 "약 40%"보다 훨씬 작다.
> 계산 근거는 [steps/STEP-0-prerequisites.md](steps/STEP-0-prerequisites.md) 참고.

**입력 토큰이 9배가 되는 것이 이 설계의 최대 비용이고, 전부 4층(속성 추출)에서 나온다.**
완화 수단은 세 가지다.

1. **MEAL/CAFE 슬롯 후보에만 적용**
2. **2단계 분리** — 값싼 모델로 속성만 압축한 뒤 판정 모델에는 압축 결과만 전달
3. 스니펫을 `title` 위주로 (description은 1~2개만)

**1번의 근거를 정확히 밝혀둔다.** 처음에는 "관광지는 변별력이 낮다"는 한 문장으로 정당화했는데,
이는 §3에서 `popularityWeight`를 0.2로 낮춘 근거(대릉원·첨성대 둘 다 `total`이 수십만 건이라 **인기도**로는
안 갈린다)를 **정성적 속성(`traits`)에도 그대로 확장**한 것이었다. 이 확장은 성립하지 않는다 —
인기도가 포화되는 것과 속성이 변별력을 잃는 것은 다른 문제다. 예를 들어 같은 지역·같은 카테고리(AT4)의
"황리단길 야시장"·"동궁과 월지"·"교촌마을 고택"은 셋 다 `total`이 높아 인기도로는 안 갈리지만,
`traits`로 뽑으면 각각 `시끌벅적` / `야경·뷰맛집` / `한적함·조용함`으로 뚜렷이 갈린다. `힐링`+`감성`
컨셉이면 세 번째가 맞고 `액티비티` 컨셉이면 첫 번째가 맞는데, 이건 인기도로는 못 가르고 속성으로만
가르는 지점이다. **관광지는 인기도로는 안 갈려도 분위기로는 갈린다.**

또한 절감 폭도 재계산이 필요하다. 이 문서의 예시 슬롯 구성(`[ATTRACTION, MEAL, CAFE, ATTRACTION,
MEAL]`)은 day당 MEAL/CAFE가 5개 중 3개(60%)다. MEAL/CAFE에만 적용하면 제거되는 건 ATTRACTION
2개(40%)뿐이라, 실제 절감폭은 "절반 이하"가 아니라 **약 40%**다.

정리하면 진짜 근거는 "관광지엔 필요 없다"가 아니라 **"둘 다 하고 싶지만 지연·토큰 예산 안에서
변별력 기대치가 더 큰 슬롯을 우선한다"는 순수 비용 트레이드오프**다. 그 대가로 ATTRACTION 슬롯은
슬롯 내 재랭킹에 쓸 외부 근거(`traits`)가 없어 카카오 점수 + 인기도만으로 정렬되는데, 이는
"괜찮다"가 아니라 **의도적으로 감수하는 커버리지 공백**으로 취급해야 한다. (V1에서는 Critic도
없으므로 이 공백을 뒤에서 보정할 단계 자체가 없다 — §10 참고.)

**개선안 — 조건부 확장.** 전 슬롯에 적용하면 다시 비용 문제로 돌아가므로, `mood` 키워드가
브리프에 있을 때만 ATTRACTION도 PlaceProfile 대상에 포함한다.

```
if (brief.keywords 에 mood 카테고리 존재)   // 힐링/액티비티/감성 등
    → ATTRACTION 슬롯도 PlaceProfile 대상에 포함
else
    → MEAL/CAFE만 (기존과 동일)
```

`mood` 키워드가 없는 요청은 관광지 선택에서 분위기 판단 자체가 덜 중요하다("경주에서 유명한
곳"이면 충분). `mood`가 있을 때만 분위기 변별이 실제로 값어치를 가지므로, 조건부로 비용을 쓰는
것이 "전부 켜기"와 "전부 끄기"보다 합리적이다. 다만 이 조건이 실제로 얼마나 자주 켜지는지(=
비용이 얼마나 늘어나는지)는 추정이 아니라 실측이 필요하다(§15).

**추론 토큰이 숨은 변수다.** 초안은 이 항목을 `gemini-2.5-flash` 기준으로 썼다 — thinking이 기본
활성이고 thinking 토큰이 **출력 요금으로 과금**되므로, 끄지 않으면 추정이 2~3배 부풀 수 있다는
것이었다. **벤더가 OpenAI로 바뀌어도 이 함정의 구조는 동일하다**: 추론 계열 모델은 추론 토큰이
출력 요금에 포함된다.

대응도 그대로다 — **Curator와 PlaceProfile은 추론을 쓰지 않는다.** 각각 지역 상식 회상과 속성
추출이라 추론 이득이 거의 없는데 **토큰 비중은 셋 중 가장 크기 때문**이다. §6에서 두 에이전트에
mini급 모델을 배정한 것과 같은 근거이며, 실제로 §6의 `model` 분리가 초안의 `thinking-budget: 0`을
대체한다.

**3층(인기도)은 비용 증가가 0이다** — 무료 쿼터 안에서 동작하고 LLM 토큰을 한 톨도 늘리지 않는다.
**이 설계에서 가장 비용 효율이 좋은 품질 개선 수단이다.**

> **[확인 완료] 이관 후에도 무료다.** 검색 API가 NAVER API HUB(네이버 클라우드 플랫폼)로 이관되면서
> **발급처·엔드포인트·인증 헤더는 바뀌었지만 요금 정책은 그대로**다 — 공식 요금표 기준
> **0~775,000건 구간 0원, 일 최대 25,000건 호출 제한**(`775,000 = 25,000 × 31`이므로 실질 제약은
> 일일 한도 하나다). 위 문장은 그대로 유효하다.
>
> 다만 **25,000건/일은 하드 리밋**이라 상한 자체는 설계에 반영해야 한다. 요청당 약 35회이므로
> **하루 약 714 코스 생성**이 상한이고, §13 10단계 캐싱(`naver:blog:{sha1}`, TTL 7일)이 붙어
> 히트율 50%가 되면 약 1,400건까지 늘어난다. **지역검색(§5-0 시더 + 스타일 modifier) ~12~18회가
> 블로그 검색과 쿼터를 합산하는지 별도 산정하는지는 아직 확인하지 못했다** — 합산이면 요청당
> ~47~53회로 상한이 약 470~530 코스로 내려간다(§15 확인 항목). 합산으로 확인되면 스타일 modifier
> 수를 슬롯당 1개로 줄이는 것이 첫 번째 조절 손잡이다. 어느 쪽이든 지역검색은 fail-open이라 상한
> 초과 시 시드·스타일 후보만 빠진다. 상한을 넘겨도 서비스가 죽지는 않는다 — 네이버는
> hard fail이 아니라 fail-open이므로(§9) 인기도·속성 신호만 빠지고 코스 생성은 계속된다.
>
> 바뀐 엔드포인트·인증 헤더·발급 절차는
> [steps/STEP-0-prerequisites.md](steps/STEP-0-prerequisites.md)에 정리했다.

---

## 12. 관측

`micrometer-registry-prometheus`가 이미 있으므로 거의 공짜다.

| 메트릭 | 용도 |
|---|---|
| `ai.llm.call{agent, provider, outcome}` | 에이전트별 지연·실패율 |
| `ai.course.pipeline.duration{stage}` | 단계별 지연 분포 → 202 전환 판단 근거 |
| `ai.grounding.match{result=hit\|below_threshold\|no_result, source=seeded\|listed\|suggested}` | **환각률 간접 지표** (before/after 비교의 핵심). `source` 태그로 나누면 **파라메트릭 제안의 생존율을 지역별로 분리 측정**할 수 있다 — "무인지 지역일수록 파라메트릭이 약하다"는 §2의 미실측 가설을 운영 데이터로 검증하는 지표 |
| `ai.candidate.retrieval{source=naver_local\|kakao, result=hit\|empty\|failed}` | 후보 공급이 실제로 목록을 채우는지. `empty` 비율이 높은 지역이 곧 "외부 데이터도 얇은 지역" |
| `ai.candidate.adopted{source, modifier}` | **최종 코스에 채택된 장소의 출처 분포.** `SUGGESTED` 비중이 지역 인지도와 어떻게 움직이는지가 이 개정의 효과 지표. `modifier` 태그(스타일 쿼리 유래 여부·어느 trait)로 나누면 **스타일 modifier 확장이 실제로 채택되는지**, 그리고 day별로 채택률이 크게 갈리는지(→ Planner `styleTags` 확장의 트리거, §5-0 추후 개선)를 본다 |
| `ai.candidate.demoted` | Curator가 `LISTED`/`SEEDED`라 주장했으나 목록과 불일치해 강등된 건수. 프롬프트 준수도 지표 |
| `ai.popularity.lookup{result=hit\|zero_total\|stale\|failed}` | 무명·폐업 장소가 걸러진 비율 |
| `ai.profile.traits{count}` | 속성 추출이 실제로 신호를 뽑고 있는지 |
| `ai.place.cache{source=kakao\|naver, result}` | 캐시 히트율 → 쿼터 여유 실측 |

---

## 13. 도입 순서

동작 변화가 없는 커밋을 앞에 쌓고, **스위치는 하나로 몰아** 문제 시 그 커밋만 revert할 수 있게 한다.

| 단계 | 내용 | 동작 변화 |
|---|---|---|
| 1 | 기존 결함 수정 (좌표 `Double`, 카카오 점수 하한, 타임아웃, NPE, 트랜잭션 경계) + 회귀 테스트 | **있음(버그)** |
| 2 | `LlmClient` 벤더 중립 추상화 + 설정 외부화 + **OpenAI 단일 호출 baseline 재측정** (기존 `GeminiService` 경로는 그대로 둠) | – |
| 3 | `RouteOptimizer` + `SlotType` + `GeoUtils` + 단위 테스트·벤치마크 | – |
| 4 | `NaverBlogClient` + **`NaverLocalClient`(지역검색 시더)** + `PopularityScorer` + 컨셉 사전 매칭 (순수 함수 우선 검증) | – |
| 5 | **`CandidateRetrievalStage`** + `GroundingStage` + `PlaceSignalStage` (병렬화, 카테고리 하드 제약, dedupe, `LISTED` 승계) | – |
| 6 | `PlannerAgent` / `CuratorAgent`(**후보 목록 입력 + `source`/`listIndex` 출력 + 위조 강등 검증**) | – |
| 7 | `AiCoursePipeline` 오케스트레이터 + 폴백 전체 (컨트롤러 미연결) | – |
| 8 | **AI 코스 생성 경로 교체 + `AiCoursePersister` 분리 + `global/gemini` 삭제** | **있음 — 스위치** |
| 9 | `PlaceProfileAgent` (플래그) | 있음(품질) |
| 10 | 카카오·네이버 Redis 캐싱 | 지연↓ |
| 11 | 실측 결과를 이 문서에 추가 | – |

**1단계를 맨 앞에 두는 이유**: 파이프라인과 **완전히 독립적으로 옳은 수정**이라 리뷰가 쉽고, 작업이
중단돼도 가치가 남는다. (초안의 1단계에 있던 일수 계산 결함은 커밋 `7cdda90`에서 이미 해소되어
범위에서 빠졌다 — §1 참고.)

**2단계에 baseline 재측정이 붙는 이유**: 이번 작업에서 LLM 벤더가 Gemini → OpenAI로 바뀌므로(§6),
§15의 before 값과 파이프라인 도입 후 값을 그대로 비교하면 **"모델 교체"와 "파이프라인 도입" 두
변수가 섞여** 개선폭을 어느 쪽에도 귀속시킬 수 없다. 2단계는 파이프라인 없이 LLM만 교체된 상태라,
여기서 한 번 재면 그 지점이 정확히 두 변수를 가르는 중간점이 된다.

**실행 단위로 분해한 체크리스트와 착수 전 준비(API 키 발급, Spring AI 검증, 테스트 인프라 신설)는
[ROADMAP.md](ROADMAP.md)에 있다.** 이 표는 순서와 그 근거만 담는다.

**4단계를 5단계보다 앞에 두는 이유**: 클라이언트와 점수 계산은 외부 의존이 적고 순수 단위 테스트가
가능하다. 먼저 검증해두면 5단계가 조립에만 집중할 수 있다.

**후보 공급 층을 새 단계 번호로 두지 않는 이유**: 3단계까지 완료된 시점에 끼어든 개정이라 번호를
새로 매기면 ROADMAP과 완료 기록이 흔들린다. `NaverLocalClient`는 4단계의 `NaverBlogClient`와 같은
API HUB 인증·`WebClient` 인프라를 쓰고, `CandidateRetrievalStage`는 5단계의 카카오 클라이언트·
스레드풀·캐시 인프라를 그대로 쓰므로 각 단계의 하위 항목으로 붙이는 게 자연스럽다.
**단, 6단계 Curator는 이 개정으로 계약이 바뀐다** — 입력에 후보 목록, 출력에 `source`·`listIndex`,
그리고 코드 측 위조 강등 검증(§5-0)이 추가된다. 6단계 착수 전에 이 문서 §5-0을 다시 읽는다.

**9단계를 8 이후로 미루는 이유**: 8단계까지만으로도 이미 확실히 낫다(환각 차단 + 실좌표 동선).
순수 부가가치라 플래그로 붙였다 뗄 수 있다.

**`CriticAgent`/`CandidateRefiner`는 이 표에 없다.** V1 범위에서 제외했다 — 근거는 §10
"Critic·Refiner를 V1에서 제외한 이유". 설계(§5-3, §5-4)는 남겨뒀으니 재검토 조건이 충족되면
9단계 뒤에 같은 방식(플래그)으로 추가한다.

---

## 14. 검토했으나 채택하지 않은 대안

**블로그 후기 본문을 LLM에게 그대로 읽히기** — 협찬·체험단 편향으로 **광고비를 많이 쓴 가게가 좋은
곳으로 평가되는 역선택**이 일어난다. 스니펫 100자로는 정보량도 부족하다. **"평가"가 아니라
"속성 추출"로 제한**하는 방식으로 대체했다(§3 4층).

**블로그 본문 크롤링** — 약관·robots.txt·지연·유지보수가 전부 다른 차원의 문제다. 명시적으로 배제한다.

**네이버 공기어 빈도로 컨셉 계량** — `total("장소 데이트") / total("장소")` 비율로 맥락을 재는 방식.
협찬 편향에 강하고 LLM 토큰이 0이라 매력적이지만, 키워드 종류만큼 호출이 곱해진다(35장소 × 3키워드
= 105회). 속성 추출이 같은 목적을 더 직접적으로, 추가 호출 없이 달성하므로 **보류**한다.
속성 추출이 기대에 못 미치면 재검토 대상이다.

**네이버 지역검색 API로 카카오 대체** — `display` 최대 5건이라 후보 스코어링에 쓰기엔 표본이 부족하다.
**좌표·주소·업종은 카카오, 인기도·속성은 네이버**로 역할을 나누는 현재 구성이 각 API의 강점에 맞는다.

> **[재채택 — 역할을 바꿔서]** 위 기각은 "카카오 **대체** + 후보 **스코어링용**"이라는 역할에
> 대한 것이고, 그 판단은 유지된다(5건으로 스코어링은 못 하고, 좌표는 여전히 카카오가 확정한다).
> 그러나 카카오에 인기도 정렬이 없다는 사실이 후보 공급 단계의 결정적 공백으로 드러나면서,
> 이 API의 `sort=comment`(리뷰 수 순)가 **"인기 후보를 풀에 심는 시더"** 역할에는 5건이
> 부족한 게 아니라 딱 적당하다는 것이 확인됐다 — 슬롯당 최종 후보가 3개인 파이프라인에서
> "이 권역에서 리뷰가 가장 많은 카페 5곳"은 충분히 강한 신호다. 상세는 §5-0.
> **인기도 정렬을 제공하는 외부 API를 조사한 결과**(아래 항목)에서도, 무료·저장 무제약·한국
> 로컬 최상 커버리지를 모두 만족하는 것은 이 API뿐이었다.

**인기도 정렬을 제공하는 다른 외부 API** — 시더 소스를 정하기 전에 조사한 결과다. "인기도 정렬이
있는가"만으로는 답이 갈리지 않았고, **이 파이프라인의 제약(무료·장소 영구 저장·한국 지방 로컬
커버리지)**을 같이 놓으면 하나만 남았다.

| API | 인기도 신호 | 무료 | 저장 가능 | 한국 로컬 밀도 | 판정 |
|---|---|---|---|---|---|
| 카카오 Local | **없음** (`accuracy`/`distance`뿐, 평점·리뷰 수 필드 없음) | ✓ | ✓ | 최상 | 커버리지 소스로만 |
| **네이버 지역검색** | ✓ `sort=comment` | ✓ | ✓ | **최상** | **시더 채택 — 유일하게 전 축 통과** |
| Google Places (New) | ✓ `rankPreference=POPULARITY` + `rating`·`userRatingCount` 필드 | ✗ (Nearby Search Pro $32~40/1k, 필드 기반 과금) | **✗ — place ID 외 콘텐츠 저장·캐싱이 약관으로 제한**. 이 서비스는 장소를 좌표와 함께 DB에 영구 저장하므로(§8) 결격 | 중 — 외국인 관광 유명지 편중, 무인지 지역에서 약함 | 부적합 |
| Foursquare Places | ✓ `sort=POPULARITY`/`RATING` + `popularity`·`rating`·`stats` | 부분 | 제약 있음 | 낮음 — 한국 POI 갱신 밀도·로컬 리뷰 희소 | 부적합 |
| TripAdvisor Content | △ Popularity Index(리뷰 질·양·최신성 종합) — 검색 정렬이 아니라 **Location Details 필드**라 후보당 상세 조회 1회 추가 | 부분 (월 5,000건) | 제약 있음 (표시 의무 + 저장 제한) | 부분 — 유명 관광지·호텔 위주, 리뷰 영어 위주 | **보류** (아래) |
| Yelp Fusion | ✓ | – | – | **불가** (한국 미서비스) | 부적합 |
| 한국관광공사 TourAPI | **없음** (`arrange`는 제목/수정일/등록일순) | ✓ | ✓ | 관광지 커버리지 최상 | 인기도 축 없음. ATTRACTION 후보 소스로 2차 검토 대상 |

**TripAdvisor를 보류하는 이유** — 신호 자체는 진짜 품질 신호라 매력적이지만 네 가지가 겹친다.
① **신호가 있는 곳과 필요한 곳이 역상관이다**: TripAdvisor의 한국 데이터는 외국인 관광객이 가는
서울·부산·경주 유명지에 집중돼 있어, 파라메트릭 지식이 강한 곳에서만 강하고 이 개정이 겨냥한
무인지 지역에서는 항목 자체가 없거나 리뷰 0~2건이다 — 추가 정보량이 거의 없다. 네이버 지역검색은
정확히 반대다. ② 호출 구조가 비싸다: 후보 10개면 검색 1 + 상세 10 = 11호출, 월 5,000건이면
**월 160여 코스**에서 바닥난다. ③ 약관이 구글과 같은 구조다. ④ 명칭이 영문/로마자 위주라 카카오
그라운딩 매칭이 취약하고, Popularity Index는 **외국인 여행자의 취향 분포**라 한국인 사용자와
모집단이 어긋난다. **재검토 조건**: 서비스가 외국인 사용자로 확장될 때(④가 단점에서 장점으로
뒤집힌다), 또는 유명 관광도시 ATTRACTION 슬롯 품질이 실측에서 부족하고 TourAPI로도 안 메워질 때.

**풀 전체 사전 랭킹 (네이버 인기도를 후보 목록에 미리 붙여 Curator에게 주기)** — 선별 품질은
오르겠지만 네이버 블로그 호출이 "생존 후보 35회"에서 "목록 전체 100회+"로 폭증한다. §10이 밝혔듯
쿼터가 지연보다 희소한 자원이고, 일 25,000건 하드 리밋 안에서 코스 상한을 3분의 1로 깎는 대가라
성립하지 않는다. 인기도 정렬은 지금처럼 사후에, 풀에는 시더로 상위 5건만 심는다(§5-0).

**적응형 트리거 (그라운딩 생존율이 낮을 때만 후보 공급을 켜기)** — Curator 재호출 루프가 생겨
지연 예산이 비결정적이 된다. Tool use를 기각한 근거(아래)와 동일하다. 항상 3원 풀로 도는 게
단순하고, "언제 무엇을 검색해야 하는지 우리가 이미 안다"는 논지에도 맞다.

**Function calling / Tool use** — LLM에게 `searchPlace` 툴을 주고 스스로 검색하며 계획하게 하는 방식.
환각이 원천 차단되지만 기각한다: ① 툴 루프 횟수가 예측 불가라 **지연 예산을 세울 수 없다**
② 루프마다 전체 컨텍스트가 재전송되어 토큰이 기하급수적으로 는다 ③ 같은 입력에 호출 패턴이 매번
달라져 테스트·디버깅이 어렵다 ④ **애초에 "언제 무엇을 검색해야 하는지"를 우리가 이미 정확히 알고
있다.** 자율성이 필요한 문제와 파이프라인으로 충분한 문제를 구분하는 것이 핵심이다.

**웹 검색 그라운딩 (Gemini Google Search / OpenAI web search)** — 컨셉 판별에서는 가장 강력하지만
**벤더 종속**이 결정적이다. 검색 그라운딩은 벤더마다 API 모양·과금·결과 형식이 전부 달라
`LlmClient` 포트의 최소 공통분모에 넣기 어렵고, 벤더 중립 포트를 유지하려는 이 설계와 정면 충돌한다.
지연 +5~15초, 별도 과금, 비결정적 결과라는 부담도 있다. **네이버 3·4층으로 같은 목적을 벤더
중립적·결정론적·무료로 달성**하므로 우선순위를 내린다. 그래도 부족하면 그때 재검토한다.

> **[보강] 후보 공급 층을 검토하면서 다시 저울질했고, 기각을 유지한다.** 이유를 이 파이프라인의
> 맥락에서 풀어 적어둔다 — 단점이 있어서가 아니라 **이 설계가 지키려는 속성 전부와 충돌하는데
> 정작 얻는 데이터가 열등하기 때문**이다. ① 방금 Gemini → OpenAI 전환 비용을 실제로 치르는
> 중인데(§6·§13 3점 baseline), 가장 벤더 종속적인 기능에 품질을 의존시키는 건 같은 실수를 더 깊게
> 반복하는 것이다. ② **한국 로컬 정보에서는 데이터 질 자체가 나쁘다** — 검색 그라운딩이 가져오는
> 건 Google/Bing 인덱스인데 한국 지역 상권 정보의 실질 원천(네이버 블로그·플레이스)은 외부
> 인덱싱이 제한적이라, 무인지 지역일수록 정보가 더 없다. 반면 3·4층은 그 원천에 구조화된
> 필드(`total`·`postdate`)로 직접 접근한다. 즉 같은 정보의 열화된 부분집합을 더 비싸게 받는 셈이다.
> ③ 검색이 LLM 호출 안에 융합되면 단계별 메트릭(§12)·캐싱(§10)·동일 입력 재측정(§15)이 전부
> 불가능해진다 — 실측 문화가 정체성인 이 레포에서 부수적 단점이 아니라 본질적 충돌이다.
> ④ 검색 왕복 횟수를 모델이 정하므로 지연 예산이 비결정적이고, 검색 실패와 LLM 실패를 분리해
> fail-open할 수 없다(§9). 재검토 조건: 3원 풀 + 3·4층을 다 붙이고도 실측에서 재현율 부족이
> 지역 티어별로 확인될 때, 그것도 전면 도입이 아니라 `SUGGESTED` 후보의 사후 교차 검증처럼
> 단계 하나로 격리해서.

**카카오 카테고리 검색을 Curator 입력으로 (retrieve-then-rank)** — 환각률 0%지만, 카카오는 품질
신호를 주지 않아 검색 결과가 사실상 무작위 나열이 된다. 3·4층이 랭킹 문제를 해결하므로 우선순위가
내려간다. **단, Curator 실패 시 폴백으로는 채택**했다 — "품질은 낮지만 유효한" 안전망으로 최적이다.

> **[재채택 — 기각 전제가 소멸했다]** 위 기각 근거를 다시 읽으면 자기모순에 가깝다: "무작위
> 나열" 문제를 해결하는 3·4층이 **이미 파이프라인에 들어왔기 때문에**, 기각의 전제가 사라졌다.
> 카카오 검색 결과가 품질 무순이어도 뒤의 PlaceSignalStage가 어차피 인기도·속성으로 재정렬한다.
> 기각 당시에는 없던 인프라가 지금은 설계에 포함돼 있는 것이다. 다만 순수 대체(retrieve-then-**rank**)가
> 아니라 **retrieve-then-select 하이브리드**로 채택한다 — 유명 지역에서는 파라메트릭 지식이 실제로
> 강하고 카카오 정렬이 "꼭 가야 할 곳"을 상위에 못 올릴 수 있으므로, 목록 밖 제안(`SUGGESTED`)을
> 함께 허용한다. 또한 카카오 단독으로는 밀집 지역에서 임의 슬라이스가 되므로 네이버 지역검색
> 시더를 붙인다. 상세는 §5-0. **부수 효과**: Curator 실패 시 폴백이 "카테고리 검색"에서 "이미
> 확보된 후보 목록의 결정론적 채움"으로 좋아진다(§9) — 시드가 있으면 폴백조차 인기 상위 후보다.

**최종 코스 캐싱** — 같은 조건으로 재생성했는데 똑같은 코스가 나오면 사용자가 버그로 인식한다.
대신 **Planner 출력**(도시+일수+키워드 → 권역 배분은 결정적)과 **장소 조회 결과**(정적)만 캐싱한다.

---

## 15. 남는 한계와 후속 과제

### 이 설계로도 답하지 못하는 것

**"왜 이 장소가 좋은가"에 대한 신뢰할 만한 평가.** 속성(`traits`)은 얻지만 품질 판단은 여전히
인기도(`total`)와 LLM 파라메트릭 지식에 의존한다. **협찬 편향을 우회한 대가로 평가 정보를 통째로
버리는 것이니, 이건 해결이 아니라 트레이드오프다.**

**속성 추출 단계 자체가 새로운 LLM 왜곡 지점이다.** 닫힌 태그 집합과 "원문에 없으면 비워라"는
스키마 강제로 완화하지만 제거되지는 않는다.

### 가장 유망한 후속 — 사용자 피드백 루프

**생성된 코스에서 사용자가 삭제한 장소와 남긴 장소가 곧 정답 라벨이다.**
`DELETE .../places/{placeId}` API가 이미 있고, `upload_course`의 fork 수·좋아요까지 합치면
**우리 서비스만의 컨셉 적합도 데이터셋**이 된다.

- "연인 키워드로 생성된 코스에서 자주 삭제되는 장소" = 컨셉 미스매치의 실측 신호
- 외부 API가 아니라 **우리가 축적한 고유 자산**이고, 협찬 편향이 없다
- `place` 테이블에는 이미 실제 장소가 좌표·URL과 함께 쌓이고 있다

지금은 데이터가 없어 쓸 수 없지만, **삭제 이벤트 기록은 지금부터 시작할 가치가 있다** — 나중에
소급할 수 없는 데이터다. 별도의 작은 작업으로 분리하는 것을 제안한다.

설계에는 확장 지점만 남긴다: `CandidateRetrievalStage`(§5-0)가 후보 소스를 `CandidateSource`
인터페이스 목록으로 갖게 만들어(V1: `NaverLocalSeedSource`, `KakaoKeywordSource`), 나중에
`InternalPlaceRepositorySource`를 **네 번째 소스**로 꽂을 수 있게 한다 — "연인 키워드 코스에서
살아남은 장소"가 `SEEDED`와 같은 자격으로 풀에 들어오는 것이다. 초안이 `CuratorAgent` 안에
두려던 `PlaceSuggestionSource` 확장 지점이 이 개정으로 별도 단계로 승격된 셈이고, 데이터가 적을 땐
외부 소스만, 쌓이면 내부 소스를 섞는 점진적 전환이 그대로 가능하다.

### 품질을 정량 측정하는 방법

지금은 "좋아졌다"를 증명할 방법이 없다. 두 가지를 후속으로 제안한다.

1. **환각률 프록시** — `ai.grounding.match{result=below_threshold}` 비율의 before/after 비교.
   지금 당장 측정 가능하고, 이 작업의 **1차 정량 지표**로 삼는다.
   → **before 값은 구현 착수 전에 이미 측정했다: [AI-HALLUCINATION-GEMINI.md](hallucination/AI-HALLUCINATION-GEMINI.md).**
   그 문서에 측정 하네스·방법론·자동 프록시의 한계(거짓 양성/거짓 음성)와 after 측정 재현 절차가
   정리돼 있으므로, 파이프라인 도입 후 **동일한 입력 세트와 동일한 `score()` 로직으로** 재측정한다.

   **단, 이번 작업은 벤더 교체(Gemini → OpenAI, §6)를 동반하므로 측정점이 둘이 아니라 셋이다.**
   2점만 재면 개선폭을 모델과 구조 중 어느 쪽에도 귀속시킬 수 없다.

   | 측정점 | 시점 | 분리되는 변수 |
   |---|---|---|
   | Gemini 단일 호출 | 완료 (환각률 25.6%) | — |
   | **OpenAI 단일 호출** | §13 2단계 직후 | 모델 교체 효과 |
   | OpenAI 파이프라인 | §13 8단계 직후 | 파이프라인 구조 효과 |

   세 측정점이 비교 가능하려면 **입력 세트(지역 10곳 × 스타일 3조합, 3일 고정)와 판정 `score()`
   로직이 동일해야 한다.** 1단계에서 카카오 점수 하한선을 도입하므로, 하네스의 판정 기준은 하한선
   도입 전 값으로 고정한 채 재측정한다 — 그러지 않으면 "하한선을 올려서 환각률이 내려간" 자기충족적
   결과가 나온다.
2. **골든 데이터셋** — 입력 20세트에 대해 생성 결과를 사람이 채점하거나 LLM-as-judge로 평가.
   파이프라인이 안정된 뒤 착수하는 것이 맞다. **이 인프라가 생기는 것이 §10에서 제외한
   `CriticAgent`를 재검토하는 조건 중 하나다** — Critic이 실제로 품질을 개선하는지 측정할
   방법이 없는 채로는 만들어도 그 값어치를 확인할 수 없다.

### 착수 전 확인 필요

- **네이버 검색 API 키 발급** — 아직 확보하지 않았다. 3·4층 전체가 여기에 의존하므로 `NaverBlogClient`
  착수의 선행 조건이다. 일일 쿼터와 다중 키워드 매칭 방식(AND / 구문 검색 지원 여부)도 함께 확인한다
- **네이버 지역검색 API 실호출 확정** (§5-0 시더의 선행 조건) — ① 블로그 검색처럼 API HUB로
  이관됐는지, 이관 후에도 `sort=comment`·`display≤5`·`start=1` 제약이 동일한지 ② `mapx`/`mapy`가
  실제로 WGS84×10⁷ 정수로 오는지(공지 기반이라 문서와 실응답이 다를 수 있다 — 어차피 좌표는
  안 쓰지만 반경 사전 필터에 쓸 여지가 있어 확인) ③ **블로그 검색과 일일 쿼터가 합산인지 별도인지**
  (§11 상한 계산에 직결) ④ `title`의 `<b>` 태그 실제 형태 ⑤ **서술어 매칭 범위** — `"황리단길 카페"`
  vs `"황리단길 루프탑 카페"`·`"황리단길 조용한 카페"`의 건수·겹침·`total`. 상호명·카테고리만
  매칭하면 §5-0 스타일 modifier 확장이 fail-open으로 조용히 무력화되므로, 이 결과가 스타일 축을
  retrieval에 둘지 초안처럼 Curator·4층에만 맡길지를 가른다
- **카카오 키워드 검색 `accuracy` 정렬의 실제 품질** — 기준이 비공개라 설계 근거로는 못 쓰지만,
  환각 측정 하네스와 같은 방식으로 "카카오 accuracy 상위 10 vs 네이버 comment 상위 5"의 겹침률을
  지역 티어별로 재보면 `LISTED` 소스의 검색 방식(키워드 vs 카테고리+반경)과 건수를 데이터로 정할
  수 있다. 5단계 착수 시 실측
- **지역 티어별 환각률 소급 집계** — [AI-HALLUCINATION-OPENAI.md](hallucination/AI-HALLUCINATION-OPENAI.md)
  아티팩트를 유명 지역 / 안 알려진 지역(순천·영주·공주·통영·삼척) 그룹으로 나눠 다시 집계한다.
  이 개정의 출발 가설("무인지 지역일수록 파라메트릭이 약하다")을 코드 한 줄 짜기 전에 기존
  데이터로 확인할 수 있는, 가장 싼 검증이다
- **Spring AI의 구조화 출력이 스키마를 디코딩 레벨에서 강제하는지** (§6) — 프롬프트 지시 기반 JSON
  모드로 떨어진다면 파싱 실패율 near-zero 전제가 깨지고, OpenAI 공식 SDK로 폴백해야 한다
- **OpenAI 모델 ID와 단가** — §6의 agent별 `model` 배정(Planner 상위 / Curator·PlaceProfile mini급)을
  확정하고, §11의 비용표를 OpenAI 기준으로 재계산하는 근거가 된다
- **OpenAI RPM/TPM 티어** — 호출이 5배가 되면 낮은 티어에서는 동시 사용자 2~3명만으로도 429가 난다.
  `llm.max-concurrent-calls` 초기값을 결정하는 근거가 된다
- 카카오 Local API 일일 쿼터 (요청당 45회 기준 상한 계산)
- **실제 요청 중 `mood` 키워드 포함 비율** — §11의 PlaceProfile 조건부 확장(ATTRACTION 포함 여부)이
  얼마나 자주 켜질지, 즉 실제 토큰 비용 증가폭을 결정한다. 파이프라인 배포 후 실측 필요
