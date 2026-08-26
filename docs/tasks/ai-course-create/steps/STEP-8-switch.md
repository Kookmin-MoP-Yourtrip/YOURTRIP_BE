# STEP-8. AI 코스 생성 경로 교체 (스위치) — 실행 계획

> [ROADMAP.md](../ROADMAP.md) 8단계의 상세 기록. 이 단계가 **유일한 스위치**다 — 0~7단계가
> 쌓아둔 파이프라인(`AiCoursePipeline`)을 `POST /api/my-courses/ai`에 연결하고, 문제가 생기면
> 스위치 커밋 하나만 revert하면 기존 Gemini 경로로 돌아간다.
>
> **동작 변화 있음.** 이 계획서는 착수 시점에 작성했고, 판정은 실행하며 추가한다.

## 착수 시점에 확정한 것

착수 전 사용자 확인으로 네 가지가 결정됐다.

1. **8-7(사용자 삭제 로그)은 범위에서 제외한다.** 이 저장소는 포트폴리오 목적이라 실제 서비스를
   운영해 사용자 피드백을 받을 계획이 없다 — 삭제 이벤트가 발생할 사용자가 없으므로, 삭제 로그는
   "소급할 수 없는 데이터"이기 이전에 **쌓일 일이 없는 데이터**다. 파급이 둘 있다:
   - `Place` 엔티티에 `source`/`modifier` 컬럼을 영속화할 필요가 사라져 **이번 단계의 DB 스키마
     변경이 0건**이 된다 (`GroundedPlace`의 source·modifier는 `ai.candidate.adopted` 메트릭
     집계까지만 쓰이고 저장 시점에 버려진다).
   - **9단계(PlaceSignal)의 착수 조건 네 개 중 둘(삭제율 비교 계열)의 데이터 소스가 사라진다.**
     남은 조건 둘(4-2의 서술어 매칭 실패 — 이미 통과로 판정, 골든 데이터셋 평가 — 인프라 없음)도
     현재 충족 전망이 없으므로 9단계는 사실상 동결이다. ROADMAP에 명기했다.
2. **환각률 하네스의 Gemini 의존은 테스트 소스로 이관해 보존한다.** `global/gemini` 삭제(8-4) 시
   `AiHallucinationBaselineTest`가 참조하는 `GeminiService.buildPrompt`(95줄 프롬프트 원문의
   유일한 소유자)와 `GeminiCourseDto`를 벤치마크 테스트 패키지로 옮긴다. 25.6% baseline의 재현
   절차가 보존되어, 3점 비교의 첫 측정점을 언제든 재검증할 수 있다.
3. **8-6(파이프라인 환각률 측정)은 구현 + 30요청 실행까지 이번 범위다.** 약 120 LLM 호출 +
   네이버·카카오·TourAPI 쿼터 소모는 사전 확인을 받았다(적용 원칙).
4. **`placeUrl == null`의 FE 처리는 확인 완료** — 미해결 항목("8단계 전 확인")이 닫혔다.

## 실행 순서

로드맵의 항목 번호는 식별자이지 실행 순서가 아니다. revert 안전성 기준으로 재배열하면 이렇다.

```
[문서]   STEP-8 계획서 작성 + ROADMAP 갱신 (8-7 제외 반영)
[준비]   8-2 AiCoursePersister 시그니처에서 GeminiCourseDto 제거 (동작 중립)
[스위치] 8-1 AiCourseDraftMapper 신설 + createAICourse 교체 + 8-3 순서 보장  ← revert 단위
[검증]   8-5 E2E (좌표·시간·순서·placeUrl null·커넥션 점유·메트릭 발화)
[삭제]   8-4 global/gemini 삭제 + 하네스 이관 + 설정·terraform 정리
[측정]   8-6 파이프라인 환각률 측정 모드 신설 + 30요청 실행 → 3점 비교 완성
```

**8-4를 로드맵 나열 순서(8-5보다 앞)와 달리 E2E 뒤로 미룬다.** 근거는 revert 산술이다 —
스위치 커밋을 revert하면 `GeminiService`/`GeminiCourseDto` 호출부가 되살아나는데, 삭제 커밋이
먼저 들어가 있으면 revert 결과가 컴파일되지 않아 "이 커밋만 revert하면 된다"(로드맵 8단계 헤더)는
성질이 깨진다. E2E가 통과한 뒤에 지우면, 문제가 드러나는 구간 내내 Gemini 경로가 코드에 온전히
남아 있다.

## 계획 상세

### 준비 커밋 — Persister가 쓰는 것을 시그니처가 드러내게 한다 (8-2)

`AiCoursePersister.save(request, GeminiCourseDto, resolvedDays, userId)`가 `GeminiCourseDto`에서
실제로 쓰는 값은 `title` 하나다(`TravelCourseMapper.toAICourseEntity`). 파라미터를 `String title`로
바꾸면 Persister·Mapper가 Gemini DTO 의존을 잃고, 스위치 커밋의 diff가 "경로 교체 그 자체"로
좁아진다. 동작 변화 없음.

### 스위치 커밋 — 파이프라인 연결 (8-1 · 8-3)

- **`AiCourseDraftMapper` 신설** (`domain.mycourse.mapper`, static 유틸): `AiCourseDraft` →
  `List<ResolvedDay>`. 파이프라인은 `domain`을 모른다는 경계(7-1)를 유지하기 위해 변환은
  domain 쪽이 소유한다. `AiCoursePlace` → `ResolvedPlace(name, startTime, lat, lng, placeUrl,
  address)` — 파이프라인 출력은 전부 그라운딩 완료(좌표 보장)라 `unverified()` 경로가 없다.
- **8-3(삽입 순서 = 표시 순서)은 이 변환기가 `AiCourseDay.places` 리스트 순서를 그대로 보존하는
  것으로 성립한다.** 나머지 절반("리스트 순서대로 save하면 `@OrderBy("id ASC")`로 재현")은
  1-5의 `AiCoursePersister`가 이미 보장·주석화해 뒀다.
- **`draft.concept()`은 폐기한다.** `TravelCourse`에 받을 필드가 없고, 스위치 커밋에서 엔티티
  컬럼을 늘리지 않는다. 컨셉을 응답에 실을지는 FE 요구가 생기면 별도 작업으로 뗀다.
- `createAICourse`는 무-`@Transactional`을 유지하고, `userId`를 요청 스레드에서 선확보하는 기존
  코드를 그대로 둔다(8-1의 요구가 이미 충족돼 있다). `resolveDays`/`resolvePlace`/
  `parseCoordinate`와 `geminiService`·`kakaoLocalClient`·`objectMapper` 필드는 삭제된다.
- `MyCourseControllerSpec`의 AI 생성 Swagger 문구는 **이 커밋에서** `AI_GROUNDING_FAILED`(503) /
  `AI_COURSE_TIMEOUT`(504) 기준으로 갱신한다 — 로드맵상 8-4 소속이지만 에러 동작이 바뀌는 시점이
  여기이고, revert 시 문서가 코드와 함께 되돌아간다.

### 삭제 커밋 체크리스트 (8-4)

- [x] `GeminiService.buildPrompt` + `GeminiCourseDto` → `src/test/.../benchmark/`로 이관
  (`LegacyGeminiPrompt`, `LegacyGeminiCourseDto`), 하네스 import 교체. **이관 전 일회성
  동일성 테스트로 프롬프트가 원본과 바이트 단위로 같음을 확인했다**(원본 삭제와 함께 테스트도 삭제)
- [x] `global/gemini` 3파일 삭제, `build.gradle`의 `google-genai` 제거
- [x] `application.yml`의 `gemini:` 블록, `.env.example`의 Gemini 블록 제거
- [x] `MyCourseErrorCode.JSON_TRANSFORMATION_FAILED` 삭제 — 스위치 후 호출자 0
  (7-2의 "발화하지 않는 상수를 두지 않는다" 원칙)
- [x] `LlmPortIsolationTest`의 `"com.google.genai"` 항목 제거 — 의존성이 클래스패스에서
  사라져 import가 컴파일 오류라 검사 자체가 무의미해졌다
- [x] terraform 3곳: `variables.tf`의 `gemini_api_key` 변수, `ec2_app.tf`의 전달,
  `templates/app-user-data.sh.tpl`의 주입 라인 — **로드맵 체크리스트에 없던 참조**
- [x] **gitignore 파일 수동 갱신 완료**: `terraform.tfvars`·`.env`의 GEMINI 라인을 이 worktree와
  메인 워킹트리 사본 **양쪽에서** 제거했다(worktree 규칙 — 훅은 단방향이고 덮어쓰지 않는다)
- [x] `CLAUDE.md`의 저장소 구조·기술 스택에서 gemini를 지우고 `global/ai`를 등재했다
- [x] 검증: `src/main`에서 gemini 참조 grep 0건(남은 것은 javadoc의 근거 기록뿐, 현재형
  서술 세 곳은 과거형으로 정정) + 전체 테스트 green + **`GEMINI_API_KEY` 없이 기동 성공**

### 측정 (8-6)

- 신설 `AiPipelineHallucinationBenchmarkTest`(`@Tag("benchmark")`) — 기존 980줄 하네스에 모드를
  덧대지 않고 새 클래스로. 공유 자산(입력 세트 지역 10 × 키워드 3, 카카오 검색 +
  `PlaceMatchScorer.score` 밴드 판정, CSV/report)은 헬퍼로 추출해 양쪽이 쓴다.
- 조립은 `@SpringBootTest(classes = ...)` 부분 컨텍스트 우선(프로덕션 배선 공유 — 수동 조립은
  `AiConfig`가 바뀔 때 벤치마크만 조용히 구식이 된다). 무관 빈이 끌려오면 수동 조립으로 폴백.
- **측정 정의 고정**: 파이프라인이 자체 그라운딩을 했더라도 출력 장소 전건에 baseline과 동일한
  카카오 검색 + score 판정(1-2 변경 전 밴드)을 적용한다 — 절차가 같아야 3점 비교가 성립한다.
- **오염 분리**: `ai.curation.slot{result=fallback}` 카운트를 리포트에 병기한다 — 폴백이 채운
  장소는 환각률이 구조적으로 0에 가까워, 측정 중 Curator가 죽어 있으면 결과가 좋아 보이는
  방향으로 오염된다(STEP-7 판정 13).

---

## 판정 (실행하며 추가)

## 판정 1 — E2E 검증 통과 (8-5) ★★

로컬에서 실제 요청으로 확인했다(앱·DB·Redis 모두 localhost, 시딩 유저 + JWT — STEP-1과 같은 절차).
시나리오는 3일 × 이동수단 두 가지다.

| 검증 | 경주 3일 WALK | 강릉 3일 CAR |
|---|---|---|
| 응답 | **201**, 22.6초 | **201**, 28.1초 |
| 장소 수 | 12 (day당 4) | 15 (day당 5) |
| `0.0/0.0` 저장 | **0건** | **0건** |
| 좌표 `null` | **0건** ★ (구 경로는 매칭 실패분이 null이었다) | **0건** |
| day 내 `startTime` 단조 증가 | 3/3 day | 3/3 day |
| `placeUrl` null 직렬화 | 4건, 정상 | 6건, 정상 |
| 요청 중 ERROR 로그 | 0건 | 0건 |

**순서 보장(8-3)은 `startTime` 단조 증가가 실증한다** — 응답 순서는 `@OrderBy("id ASC")`(=삽입
순서)이고 `startTime`은 파이프라인이 동선 순서로 계산한 값이므로, 삽입 순서가 동선과 어긋났다면
시각이 뒤섞여 보였을 것이다. 6개 day 전부 단조 증가였다.

**커넥션 점유(1-6이 미룬 측정)** — 요청 진행 내내 0.5초 간격으로 `hikaricp.connections.active`를
폴링(38회)했는데 **전부 0**이었다. 저장 트랜잭션이 폴링 간격보다 짧다는 뜻으로, before(최악
~360초 점유)와 대비된다. 부하 없는 단건 관측이라 정밀값은 아니지만 "외부 I/O가 트랜잭션 밖"임은
이걸로 충분히 보인다.

**메트릭 발화** — `ai.candidate.adopted{source,modifier}` 27건(seeded 21 · listed 6),
`ai.curation.slot{result=curator}` 27/27(**폴백 0건** — 8-6 측정 오염 없음),
`ai.grounding.match` hit 78 / name_mismatch 1 / no_result 1 / category_mismatch 1.

**주의 신호 하나 — 지연이 설계 추정보다 높다.** 요청 전체가 22.6·28.1초로 예산 30초 바로 아래다.
스테이지 합(2요청 누적): Curator 23.9초 > Planner 14.7초 > 후보공급 8.7초 > URL 보강 2.7초 >
그라운딩 0.4초 > 경로 0.005초. 표본 2건(콜드 스타트 포함)이라 결론은 못 내리며, **8-6의 30요청
분포가 11-2(202 전환 판단)의 실제 근거가 된다.**

> 사소한 함정 하나: Git Bash에서 curl `-d` 인라인으로 한글 본문을 보내면 인코딩이 깨져 400
> (`INVALID_REQUEST_FIELD`)이 난다. UTF-8로 저장한 파일을 `--data-binary`로 보내면 정상 —
> 첫 400은 앱 결함이 아니었다.

---

## 판정 2 — `dev-ai-course` 병합으로 #134·#135 수정분과 합쳐 재검증했다 ★★★

E2E(판정 1)가 드러낸 결함 둘이 이슈로 떠서 각각 PR로 해소됐고([#143](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/pull/143) ·
[#141](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/pull/141)), 그것을 이 브랜치로 병합해 같은 조건으로 다시 쟀다.

**병합 전에 확인해 둘 것이 있었다 — `dev-ai-course`에는 스위치가 없다.** 그쪽 컨트롤러는 여전히
Gemini 경로를 타고 `global/gemini`도 살아 있었다. 즉 두 PR의 수정은 파이프라인 **내부**에만 들어갔고,
**그 파이프라인이 실제 사용자 요청을 받는 상태는 이 병합으로 처음 만들어졌다.** 충돌은 없었고
(7개 파일이 양쪽에서 수정됐으나 자동 병합), 테스트 890개가 전부 통과했다.

### 전후 대비 (3일 코스 6건 — 부산·서울·제주 / 순천·영주·통영)

| | 병합 전 | 병합 후 |
|---|---|---|
| 장소 총계 | 83개 | **104개** |
| day당 장소 | 4~5곳 | 5~7곳 |
| 마지막 장소 시작(중앙) | 13~14시대 | **17:30** (18개 day 중 14개가 17시 이후) |
| 도심 45km 초과 | 4건(최대 177.8km) | **0건**(최대 28.8km) |
| 요청 지연(최대) | 22.6초 | **28.0초** (예산 30초) |

좌표 null·`0.0/0.0`·중복 이름·`startTime` 역행은 두 번 모두 0건이고, 폴백 슬롯도 0이라 측정 오염은
없다. **지연이 예산 여유 2초까지 좁혀진 것이 이번 병합의 유일한 경고 신호**이고, #135가 완료 조건으로
건 "지연 재측정 + `ai.course.budget-ms` 재검토"가 이제 선택이 아니라 필수다.

> #135의 처방은 이 문서가 제안한 "식사 시간창 앞 대기"가 아니라 **탄력 체류**(체류를 상한까지 늘려
> 시간창을 맞춤)로 구현됐다. 일정이 연속으로 유지되므로 "빈 시간과 긴 체류를 FE가 구분 못 한다"는
> 우려가 발생하지 않고, 그 대응으로 제안했던 체류시간 컬럼 추가도 불필요해졌다.

### 빈 슬롯 5개의 원인 분해 — 결핍은 어떤 지표에도 잡히지 않는다

병합 후 슬롯 109개 중 **5개가 장소를 채우지 못했다**(제주 1 · 순천 1 · 영주 2 · 통영 1). 이 값은
메트릭에 없어서 역산으로 얻었다 — 그라운딩 집계의 검증 후보 327건을 슬롯당 3개로 나눈 109가
`ai.curation.slot{result=curator}`와 정확히 일치하고, 저장된 장소는 104개다.

원인은 둘로 갈린다.

- **전 day 중복 제거 4개** — [`GroundingStage`](../../../../src/main/java/backend/yourtrip/global/ai/grounding/GroundingStage.java)가
  `placed.add(...)`로 걸러내는데, **버려진 후보는 집계상 `HIT`으로 남는다.** 영주는 그라운딩 실패가
  0건인데 슬롯 2개가 비었다 — 소거법으로 이 경로가 유일하다. 식당 후보 풀이 좁은 소도시에서 day마다
  같은 곳을 고르면 앞 day가 선점한 뒤 조용히 사라진다(영주 day1만 저녁이 남고 day2·3이 잃었다)
- **그라운딩 3연속 실패 1개** — 제주 day3의 MEAL 슬롯. 후보 3개가 각각 이름 불일치·업종 불일치·무결과로 죽었다

**빈 슬롯은 [`AiCoursePipeline`](../../../../src/main/java/backend/yourtrip/global/ai/pipeline/AiCoursePipeline.java)에서
`continue`로 건너뛰며 로그도 메트릭도 남기지 않는다.** `ai.curation.slot`은 Curator 응답 기준이라
채워진 것으로 세고, 그라운딩 집계는 중복 제거분을 성공으로 센다. 그래서 "저녁 없는 하루"가 나가도
운영 지표는 전부 정상으로 보인다.

### `NO_RESULT` 4건의 정체 — 셋은 환각이 아니라 우리 인코딩 결함이다

`NO_RESULT`는 이름을 로그로 남기지 않는 유일한 실패 경로라(`NameMismatch`·`Failed`는 남긴다),
요청별 집계 + 강등 로그 + 카카오 재조회를 교차해 복원했다. 셋 다 재현 시 문서 0건이 나왔다.

| 코스 | 이름 | 건수 | 성격 |
|---|---|---|---|
| 부산 | 호텔 아쿠아펠리스 스카이 전망대&스카이 워크 | 1 | 인코딩 결함 |
| 제주 | 쉼팡마씸 24시 무인카페 & 4가지 식당 | 2 | 인코딩 결함 |
| 순천 | 순천만 화포포구 | 1 | 진짜 위조(`목록=미강서원`) |

네이버 원문이 `<b>쉼팡마씸</b> 24시 무인카페 &amp; 4가지 식당`인데 `NaverPlaceMapper.stripBoldTags`가
**태그만 걷어내고 HTML 엔티티는 그대로 둔다.** 그래서 후보 목록에 `&amp;`가 실리고, 모델이 `&`로
고쳐 답하면 위조로 강등돼 카카오 검증으로 넘어가며, 그 표기는 카카오에 없어 탈락한다 —
**좌표를 이미 확보한 `SEEDED` 후보를 잃는 셈이다.**

**파급은 지표 체계 재정립(#138) 이후 기준으로 나눠 봐야 한다.**

- **1차 지표(지어냄률)는 거의 영향받지 않는다.** 수동 검증 기반이고 정의상 "게이트 거짓 양성이
  섞이지 않는다" — 쉼팡마씸은 실존하므로 `FABRICATED`로 분류되지 않는다
- **장소 미확보율(2차)은 부풀려진다.** 확보할 수 있었던 장소를 놓친 것은 사실이지만, 원인이
  LLM이 아니라 우리 인코딩 결함이라는 점이 값에는 드러나지 않는다
- **운영 프록시가 오염된다.** 5-6은 `no_result`를 *순수 환각*으로 규정하고 그래서 인프라 실패와
  굳이 갈라 놓았는데, 지금은 이 결함이 그 칸에 쌓여 규정 자체가 사실과 어긋난다

셋 중 8-6에 직접 걸리는 것은 두 번째다 — **측정 전에 고치는 편이 낫다.** 이 건과 이름 게이트·업종
불일치를 묶어 [#147](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/147)로 분리했다.

### 남은 것

**8-6(파이프라인 환각률 측정)만 남았다.** 다만 위 순서 문제 때문에 #147의 엔티티 디코딩을 먼저
반영하는 것이 맞다. 그리고 **"이 커밋만 revert하면 된다"는 8단계의 전제는 이 병합으로 끝났다** —
스위치 커밋 위에 99커밋이 쌓였으므로, 되돌려야 할 상황이 오면 revert가 아니라 별도 판단이 필요하다.
