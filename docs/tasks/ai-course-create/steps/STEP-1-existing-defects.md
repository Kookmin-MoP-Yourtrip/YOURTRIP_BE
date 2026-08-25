# STEP-1. 기존 결함 수정 — 실행 기록

> [ROADMAP.md](../ROADMAP.md) 1단계의 실행 기록이다. 이 단계는 파이프라인과 **완전히 독립적으로 옳은 수정**이라 맨 앞에 있다 — 작업이 중단돼도 가치가 남고, 리뷰 대상이 좁아 검증이 쉽다.
>
> 결론부터: **로드맵이 지시한 1-2를 그대로 구현하면 안 됐다.** 산출물을 집계해보니 "점수 하한선"이라는 처방 자체가 데이터와 반대였고, 진짜 결함은 다른 곳에 있었다. 나머지 네 항목은 계획대로 진행됐다.
>
> 진행 상황: **1단계 코드 작업 완료.** 테스트 56 → 73개, 전부 통과. E2E 검증은 아래 "남은 작업" 참고.

## 왜 1-2만 계획이 바뀌었나

로드맵 1-2는 이렇게 적혀 있었다.

> `KakaoLocalClient.score()`에 점수 하한선 도입 — 미달 후보는 매칭 실패로 처리. **임계값은 BASELINE 측정의 점수 밴드 분포를 근거로 정한다**

그 근거가 될 산출물(`results/*.csv`)은 `.gitignore` 대상이라 레포에 없었고, 다른 워크트리의 작업 디렉터리에서 찾아냈다. 집계 결과는 [BASELINE-ARTIFACT-ANALYSIS.md](../hallucination/BASELINE-ARTIFACT-ANALYSIS.md)에 문서로 고정했고, **원본도 이후 [hallucination/artifacts/](../hallucination/artifacts/)로 승격했다**(같은 위험이 OpenAI 재측정에서 실제 소실로 현실화됐다).

**그 데이터가 처방을 반박했다.**

| 밴드 | 점수 | 전체 비중 | CORRECT | 불량(FABRICATED+WRONG_MATCH) |
|---|---|---|---|---|
| `NO_RESULT` | -1 | 11.6% | 40% | 40% |
| `S1_4` | 3 | 8.2% | **100%** | **0%** |
| `S5_7` | 5, 7 | 14.1% | 65% | **31%** |
| `S8_10` | 8, 10 | 66.1% | 93% | 7% |

`≥5` 하한선은 **100% 정답인 `S1_4`를 버리고 31% 불량인 `S5_7`을 남긴다.** 정확히 거꾸로 된 필터다.

> **[갱신]** 위 표는 이 판정 당시(재판정 전)의 verdict 기록이다. 이후 수동 검증 재판정에서
> `S1_4` 정답이 100% → 94%(17/18)로 조정됐으나 비단조성이라는 결론은 그대로다. 현행 수치는
> [BASELINE-ARTIFACT-ANALYSIS.md](../hallucination/BASELINE-ARTIFACT-ANALYSIS.md) 판정 5.

---

## 판정 1 — 점수가 정확도와 단조 관계가 아니다 ★

원인은 `score()`의 가점 구조에 있다. 검색 키워드가 `"지역명 + 장소명"`이라 **주소 일치(+3)가 거의 자동으로 붙고**, 음식점·카페면 카테고리(+2)도 자동이다. 그래서 **이름이 하나도 안 맞아도 5점이 나온다.**

실제 `S5_7` 사례:

```
해물가 (통영)      → 통영해물가 창원본점  @경남 창원시   ← 다른 도시
해운대 시장        → 개미집 국제시장본점직영점
고마나루돌쌈밥      → 고마나루1999
순천아랫장국밥거리  → 웃장 국밥골목                    ← FABRICATED
```

반대로 `S1_4`(3점, 주소만 일치)의 정체는 환각이 아니라 **표기 차이**였다.

```
동궁과 월지             → 동궁과월지
주문진 수산시장          → 주문진수산시장
허균·허난설헌 기념공원    → 허균허난설헌기념공원
SEA LIFE 부산 아쿠아리움  → 씨라이프 부산아쿠아리움
```

즉 결함은 "하한선이 없다"가 아니라 **① 이름 비교가 공백·문장부호에서 거짓 음성을 낸다 ② 이름 불일치를 주소·카테고리 가점이 상쇄한다** 두 가지였다.

### 결정 — 정규화 + 이름 일치 필수 게이트

사용자 확인을 거쳐 방향을 바꿨다.

- `normalize()` — 공백·`·`·문장부호 제거 후 소문자화(`Locale.ROOT`)
- `nameMatches()` — 정규화한 두 이름의 양방향 `contains`
- `findBestPlace()`에서 **필터를 `max()`보다 앞에 배치**. 순서가 반대면 이름이 안 맞는 후보가 가점만으로 1등이 되어 그대로 선택된다

**`score()`는 한 글자도 건드리지 않았다.** 벤치마크 하네스가 `ReflectionTestUtils`로 이 private 메서드를 직접 호출하고, [ROADMAP.md](../ROADMAP.md) 성공 기준이 "판정 로직은 변경 전 기준 고정"을 요구한다. 여기를 바꾸면 2-6·8-6 재측정의 비교 가능성이 깨진다.

### 효과와 한계 (실측 389건 기준)

| 지표 | 값 |
|---|---|
| 정규화로 새로 매칭 성립 | 32건 (검색 성공 344건의 9.3%) |
| 게이트 통과 | 295건 |
| 추가 탈락 | 49건 (14.2%) |
| **좌표 확보율** | **88.4% → 75.8%** |

**이 게이트가 못 잡는 것이 있다.** AI가 준 이름이 다른 업소 상호명의 부분 문자열이면 통과한다.

```
해물가 → 통영해물가 창원본점    통과 (여전히 오매칭)
대릉원 → 스타벅스 경주대릉원점   통과 (여전히 오매칭)
```

BASELINE 문서 발견 3이 지적한 `WRONG_MATCH` 유형이고, 1-2로는 해결되지 않는다. **로드맵 5-3의 슬롯별 카테고리 하드 제약**(ATTRACTION 슬롯에서 `CE7` 배제)이 스타벅스 케이스를 잡도록 설계돼 있다.

---

## 판정 2 — 0.0/0.0 저장의 범인은 빌더 파라미터 타입이었다

`Place`의 필드는 `Double`인데 `@Builder` 생성자 파라미터가 원시 `double`이었다. 그래서 좌표를 지정하지 않으면 `null`이 아니라 **기본값 `0.0`이 오토박싱되어 저장**됐다. 카카오 매칭에 실패한 장소가 정확히 이 경로를 탔다 — 적도 앞바다 좌표가 실제 값인 것처럼 저장됐다.

`Double`로 승격하면 파급이 따라온다. **게터를 읽는 쪽이 전부 새 언박싱 NPE 후보가 된다.**

| 파일 | 조치 |
|---|---|
| `PlaceResponse` / `PlaceCreateResponse` / `PlaceUpdateResponse` | `Double`로 승격 |
| `PlaceCacheItem` | `Double`로 승격 (Redis 캐시 JSON과 호환) |
| `PlaceMapper.toCopyEntity` | 빌더 타입 변경으로 NPE 자동 해소 |
| `PlaceCreateRequest` / `PlaceUpdateRequest` | **그대로 `double`** — 사용자가 만든 장소는 좌표가 항상 있다 |

**API 계약이 바뀐다.** 응답의 `latitude`/`longitude`가 `null`일 수 있으므로 Android 클라이언트가 이를 처리해야 한다.

컴파일 에러는 정확히 한 곳에서만 났다 — `MyCourseServiceImplTest`의 `.latitude(0)`. `int → Double`은 widening+boxing 조합이라 Java가 허용하지 않는다.

---

## 판정 3 — 타임아웃이 없는 것보다 "잘못된 타임아웃"이 더 나빴다

`KakaoConfig`의 `WebClient`에 `HttpClient` 설정이 전혀 없어 응답을 무제한 기다렸다. 유일한 방어이던 `.block(Duration.ofSeconds(20))`은 **초과 시 `IllegalStateException`을 던지는데**, `catch`가 `WebClientResponseException` 하나뿐이라 이를 빠져나가 원시 500이 됐다.

```java
// 변경 전 — 타임아웃이 걸려도 BusinessException으로 변환되지 않는다
.block(Duration.ofSeconds(20));
...
} catch (WebClientResponseException e) { ... }
```

**타임아웃 설정과 catch 확장은 한 세트라야 의미가 있다.** 하나만 해도 원시 500은 그대로 남는다.

| 설정 | 값 | 근거 |
|---|---|---|
| connect timeout | 2초 | 국내 리전이라 정상 연결은 수십 ms |
| response timeout | 3초 | 설계 문서의 목표치 |
| maxConnections | 50 | 기본값이 `max(가용 프로세서, 8)`이라 머신마다 달라진다 |
| pendingAcquireTimeout | 5초 | 기본 45초는 응답 제한(3초)에 비해 과도 |
| maxIdleTime / maxLifeTime | 30초 / 5분 | 상대가 조용히 끊은 커넥션 재사용 방지 |

호출당 최악 지연이 20초에서 5초로 줄어, 장소 18개 기준 **360초가 90초**가 된다.

**하네스가 설정을 따라오게 했다.** `AiHallucinationBaselineTest`가 `WebClient`를 직접 조립하고 있어 프로덕션만 고치면 측정이 실제 동작을 반영하지 못한다. `KakaoConfig.buildKakaoWebClient` 정적 팩토리로 조립을 모으고 하네스가 이를 호출하게 했다.

---

## 판정 4 — 트랜잭션 분리의 진짜 걸림돌은 더티체킹이었다

`createAICourse`는 메서드 전체가 `@Transactional`이라 LLM 호출 1회와 카카오 호출 N회(최대 18회)가 전부 트랜잭션 안에 있었다. `open-in-view: false`라 그 시간 내내 HikariCP 커넥션이 묶였다.

단순히 어노테이션을 떼면 되는 게 아니었다. 기존 구조가 **Place를 먼저 저장한 뒤 `place.updateKakaoPlace(...)`로 좌표를 채우는 더티체킹에 의존**했기 때문이다. 이 구조에서는 카카오 호출이 트랜잭션 안에 있어야만 한다.

그래서 순서를 뒤집었다.

```
[요청 스레드 · 트랜잭션 밖]
  userId 확보          ← SecurityContextHolder를 여기서만 읽는다
  Gemini 호출 → JSON 파싱
  카카오 검증 N회      → ResolvedPlace/ResolvedDay 중간 표현으로 조립
[짧은 트랜잭션 · AiCoursePersister]
  User 조회 → TravelCourse/DaySchedule/Place save
```

`AiCoursePersister`는 [MyCourseDetailReader](../../../../src/main/java/backend/yourtrip/domain/mycourse/service/MyCourseDetailReader.java)의 선례를 그대로 따랐다 — **별도 빈이어야 한다.** 같은 클래스 안의 메서드로 두면 self-invocation이라 프록시를 우회해 트랜잭션이 **아예 걸리지 않는다.** 조용히 깨지는 종류의 실수라 회귀 테스트로 구조를 고정했다.

부수적으로 `PlaceMapper.toEntityFromGeminiDto`(좌표를 세팅하지 않던 팩토리)가 사라지면서 **`PlaceMapper`의 Gemini DTO 의존도 끊겼다** — 8단계의 `global/gemini` 삭제가 조금 쉬워진다.

### 선례가 경고한 함정

[transaction-separation.md](../../connection-pool-bottleneck/stage0/local/transaction-separation.md)가 기록한 두 가지를 확인했다.

1. **지연 로딩** — 트랜잭션을 좁히자 `LazyInitializationException`이 터진 사례가 있었다. 이번 경로는 새로 만든 엔티티만 다루고 반환값이 ID 하나라 해당 없음.
2. **유량 제한 소실** — 트랜잭션이 우연히 수행하던 유량 제한이 사라져 지표가 오히려 악화된 사례가 있었다. **1단계에는 동시성 제어를 넣지 않았다**(로드맵 2-3의 `max-concurrent-calls` 범위). 8단계 실측에서 확인할 위험으로 남긴다.

---

## 판정 5 — 뚫려 있던 건 잘못된 키워드가 아니라 누락이었다

잘못된 키워드 *문자열*은 이미 `GlobalExceptionHandler`가 400으로 막고 있었다. `keywords` 필드를 아예 생략한 경우만 `buildKeywordsJson`까지 도달해 `new HashSet<>(null)`에서 NPE를 냈고, 핸들러가 없어 원시 500이 됐다.

- `AICourseCreateRequest.keywords`에 `@NotEmpty` — 빈 리스트도 막는다(`{}`가 되어 취향이 반영되지 않은 코스가 나온다)
- `buildKeywordsJson`에 null 가드 — DTO 검증을 우회하는 호출부(벤치마크 하네스)가 있어 2중 방어
- 인접 오류 정리: `location`의 검증 메시지가 `"코스 제목은 필수"`로 잘못돼 있던 것과 로그 오타 `"ekeywords"`

---

## 회귀 테스트

**커넥션 점유 시간 측정은 하지 않았다** — 8단계 E2E로 미뤘다(사용자 결정). AI 코스 생성은 요청마다 LLM을 호출해 부하 테스트 자체가 부적합하고, 스텁으로 대체하면 측정의 의미가 옅어진다.

| 테스트 | 확인 내용 |
|---|---|
| `KakaoLocalClientTest` | 정규화 통과·이름 불일치 탈락·필터 순서·타임아웃·5xx 변환 |
| `PlaceMapperTest` | 좌표 null 복사 시 NPE 없음, 미지정 시 0.0이 아닌 null |
| `KeywordTypeTest` | `buildKeywordsJson(null)` |
| `AiCourseTransactionBoundaryTest` | 경계가 무너지는 회귀를 구조로 차단 |

**Spring 컨텍스트를 띄우지 않았다.** `application-test.yml`이 DB·Redis를 실제 인스턴스로 전제해 컨텍스트를 띄우면 인프라가 필요해지는데, 확인하려는 것에 비해 비용이 크다. 대신 `WebClient`는 `KakaoConfig.buildKakaoWebClient`로 조립해 **프로덕션과 같은 타임아웃 설정 위에서** 검증한다. (그래서 계획에 있던 `@ActiveProfiles("test")` 배선은 만들지 않았다 — 5단계 통합 테스트에서 다시 판단한다.)

`AiCourseTransactionBoundaryTest`는 실제 프록시 동작이 아니라 **구조**를 검증한다. 리플렉션으로 `createAICourse`에 `@Transactional`이 없고 `AiCoursePersister`가 별도 `@Service`인지 확인한다 — self-invocation 회귀는 조용히 깨지므로 값싸게라도 막아두는 편이 낫다.

---

## 검증 기록

| 항목 | 명령 | 결과 |
|---|---|---|
| 전체 테스트 | `./gradlew test` | **73개 통과** (변경 전 56개) |
| 타임아웃 실제 적용 | 위와 동일 | 지연 스텁 6초에 대해 "장애 처리" 2건이 **3.96초**에 종료 — `responseTimeout(3초)`이 동작한다 |
| 빈 배선 | `YourtripApplicationTests` | 컨텍스트 로드 성공 (`AiCoursePersister` 주입 포함) |
| 게이트 동작 | 실측 389건에 정규화 규칙 적용 | 32건 구제, 49건 추가 탈락, 좌표 확보율 88.4% → 75.8% |

### E2E 검증 (로컬)

로컬에서 실제 요청으로 확인했다. 운영 환경에는 요청하지 않았다 — 앱·DB·Redis 모두 `localhost`다.

**인증 조달**: 시드 계정의 평문 비밀번호가 없어 로컬 DB에 테스트 유저를 직접 시딩했다(`e2e-local@yourtrip.test`). `DB_DDL_AUTO=create`라 재기동하면 스키마째 사라지므로 정리가 따로 필요 없다.

| 검증 | 결과 |
|---|---|
| 코스 생성 (`순천`, 3일, WALK/FOOD/HEALING) | **201**, 14.4초, 장소 12개 |
| **`0.0/0.0` 저장** | **0건** ★ |
| 매칭 실패 장소 | 2건 — 좌표 `NULL`로 저장 |
| API 응답 계약 | 매칭 실패 장소가 `"latitude": null`로 응답됨 |
| `keywords` 생략 | **400** `keywords: 여행 스타일 키워드는 최소 1개 이상...` |
| `keywords` 빈 배열 | **400** (동일) |
| 잘못된 키워드 문자열 | **400** `유효하지 않은 키워드 코드가...` (기존 동작 유지) |
| 요청 중 ERROR/Exception | **0건** |

**정규화가 실제로 구제한 사례가 1건 관측됐다.**

```
AI 원안: "순천 문화의 거리"  →  카카오: "순천문화의거리"
  정규화 전 contains  실패  (띄어쓰기 때문에 양방향 모두 불포함)
  정규화 후 contains  성공  → 좌표 확보
```

**게이트의 한계도 실물로 확인됐다.** 부분 문자열 오매칭이 그대로 통과한다.

```
AI 원안: "낙안읍성민속마을"  →  카카오: "낙안읍성민속마을 김소아가옥"
```

AI는 민속마을 전체를 의도했는데 카카오는 그 안의 특정 가옥을 돌려줬다. `대릉원 → 스타벅스 경주대릉원점`과 같은 유형이며, 5-3의 카테고리 하드 제약이 다룰 몫이다.

> **주의** — 매칭에 실패하면 AI 원안 이름을 그대로 저장하므로, 저장된 이름만 보고 매칭 성공 여부를 판단하면 안 된다. 판단 기준은 **좌표가 `NULL`인지**다.

### 커밋

```
7673e04 fix: 좌표를 Double로 승격해 0.0/0.0 저장과 언박싱 NPE 차단
3ab3bf5 fix: 카카오 매칭에 이름 정규화와 이름 일치 필수 게이트 도입
329fc62 fix: 카카오 WebClient에 타임아웃·커넥션 풀 명시하고 예외 처리 확장
a1d8172 fix: keywords 누락 시 발생하던 NPE 차단
8dfda0e refactor: AI 코스 생성의 트랜잭션 경계를 분리해 커넥션 점유 시간 단축
f7fee1f test: 1단계에서 고친 결함들의 회귀 테스트 추가
```

## 남은 작업

- **FE 공유** — 응답의 `latitude`/`longitude`가 nullable이 됐다. Android 클라이언트가 `null`을 처리해야 한다
- **좌표 확보율 하락을 5단계가 메워야 한다** — 실측 기준 88.4% → 75.8%. 후보 3배 확보(5-2)와 카테고리 하드 제약(5-3)이 각각 "탈락한 정답"과 "통과한 오매칭"을 담당한다

## 참고 문서

- [ROADMAP.md](../ROADMAP.md) — 이 단계가 속한 실행 로드맵
- [BASELINE-ARTIFACT-ANALYSIS.md](../hallucination/BASELINE-ARTIFACT-ANALYSIS.md) — 1-2 설계 변경의 근거가 된 산출물 집계
- [STEP-0-prerequisites.md](STEP-0-prerequisites.md) — 앞 단계
- [transaction-separation.md](../../connection-pool-bottleneck/stage0/local/transaction-separation.md) — 트랜잭션 분리 선례와 그 함정
