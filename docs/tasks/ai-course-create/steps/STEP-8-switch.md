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

- [ ] `GeminiService.buildPrompt` + `GeminiCourseDto` → `src/test/.../benchmark/`로 이관
  (`LegacyGeminiPrompt`, `LegacyGeminiCourseDto`), 하네스 import 교체
- [ ] `global/gemini` 3파일 삭제, `build.gradle`의 `google-genai` 제거
- [ ] `application.yml`의 `gemini:` 블록, `.env.example`의 Gemini 블록 제거
- [ ] `MyCourseErrorCode.JSON_TRANSFORMATION_FAILED` 삭제 — 스위치 후 호출자 0
  (7-2의 "발화하지 않는 상수를 두지 않는다" 원칙)
- [ ] `LlmPortIsolationTest`의 `"com.google.genai"` 항목 제거(주석이 예고한 대로)
- [ ] terraform 3곳: `variables.tf`의 `gemini_api_key` 변수, `ec2_app.tf`의 전달,
  `templates/app-user-data.sh.tpl`의 주입 라인 — **로드맵 체크리스트에 없던 참조**
- [ ] **gitignore 파일 수동 갱신**: `terraform.tfvars`의 GEMINI 라인을 이 worktree와 메인
  워킹트리 사본 **양쪽에서** 제거(worktree 규칙 — 훅은 단방향이고 덮어쓰지 않는다). `.env`의
  `GEMINI_API_KEY`도 같은 방식. **커밋 diff에 안 잡히므로 이 체크리스트가 유일한 방어선이다**
- [ ] 검증: `src/main`에서 gemini 참조 grep 0건 + `GEMINI_API_KEY` 없이 기동 성공

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
