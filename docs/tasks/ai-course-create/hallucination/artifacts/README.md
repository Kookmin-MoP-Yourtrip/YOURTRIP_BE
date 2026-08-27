# 환각률 측정 원본 산출물

[AI-HALLUCINATION-GEMINI.md](../AI-HALLUCINATION-GEMINI.md)와 [BASELINE-ARTIFACT-ANALYSIS.md](../BASELINE-ARTIFACT-ANALYSIS.md)의 **모든 수치가 근거로 삼는 원본**이다. 문서의 표를 의심하면 여기서 직접 재집계할 수 있다.

## 왜 커밋하는가

측정 하네스는 결과를 레포 루트 `results/`에 쓰는데, 그 디렉터리는 [.gitignore:63](../../../../../.gitignore)으로 제외된다(k6 덤프 같은 대용량 부하테스트 산출물과 같은 규칙). **그 규칙 자체는 유지하되, 환각률 측정분만 예외로 승격한 것이 이 디렉터리다.**

이유는 실제 사고다. **OpenAI(luna/nano) 재측정의 산출물은 이 규칙 때문에 소실됐다** — 113개 브랜치 전체 히스토리·모든 worktree·파일시스템에서 찾지 못했고, 그래서 그 측정 문서는 검증 불가능해져 삭제됐다([BASELINE-ARTIFACT-ANALYSIS.md](../BASELINE-ARTIFACT-ANALYSIS.md) 판정 5). Gemini 산출물도 한동안 다른 worktree의 작업 디렉터리에만 남아 있었고, 그 브랜치를 지웠다면 똑같이 사라졌을 것이다.

`results/`는 하네스의 작업 디렉터리로 계속 두고, **정본은 여기다.**

## 구조 — 측정 1회 = 디렉터리 1개

```
artifacts/
├── gemini-20260811/          ① Gemini 단일 호출 원 측정 (재생 불가)
│   ├── places.csv            389행 · requests.csv 30행
│   ├── verdicts/             옛 판정 104건 (batch1~3)
│   ├── batches/              배치별 요청 지표 3개
│   └── raw/153453/ raw/184600/   LLM 응답 원문 17건
├── gemini-rescore-20260825/  ② 현행 검증 로직으로 재채점
│   ├── places.csv            389행 — 현행 before 수치의 근거
│   └── verdicts.csv          50행 — 정식 층화 판정
├── pipeline-20260827/        ③ 파이프라인 측정 (ROADMAP 8-6)
│   ├── places.csv  requests.csv  verdicts.csv
│   └── raw/                  초안 원본 30건
└── scripts/                  merge3.py · fix_shifted.py
```

**디렉터리명이 측정을 식별하고, 파일명은 그 안에서 역할만 말한다**(`places` / `requests` / `verdicts` / `raw`). 세 측정이 같은 위치에 같은 이름을 가져 나란히 비교할 수 있다. 새 측정이 생기면 `<측정이름>-<날짜>/`를 하나 더 만든다.

> **옛 이름 → 새 경로** (2026-08-27 재구조화). 이전 커밋 메시지와 문서가 아래 왼쪽 이름으로 산출물을 인용하고 있어 대조표를 남긴다.
>
> | 옛 이름 | 새 경로 |
> |---|---|
> | `merged3-places.csv` | `gemini-20260811/places.csv` |
> | `merged3-requests.csv` | `gemini-20260811/requests.csv` |
> | `manual-verification-20260811-174326.csv` | `gemini-20260811/verdicts/batch1-174326.csv` |
> | `manual-verification-20260812-153453.csv` | `gemini-20260811/verdicts/batch2-153453.csv` |
> | `manual-verification-20260812-184600.csv` | `gemini-20260811/verdicts/batch3-184600.csv` |
> | `raw-20260812-153453/` · `raw-20260812-184600/` | `gemini-20260811/raw/153453/` · `raw/184600/` |
> | `hallucination-baseline-rescore-20260825-110536.csv` | `gemini-rescore-20260825/places.csv` |
> | `manual-verification-rescore-20260825-110536.csv` | `gemini-rescore-20260825/verdicts.csv` |
> | `hallucination-pipeline-20260827.csv` | `pipeline-20260827/places.csv` |
> | `hallucination-pipeline-20260827-requests.csv` | `pipeline-20260827/requests.csv` |
> | `manual-verification-pipeline-20260827.csv` | `pipeline-20260827/verdicts.csv` |
> | `merge3.py` · `fix_shifted.py` | `scripts/` 아래로 |
>
> **스크립트 안의 경로는 고치지 않았다** — `results/merged3-places.csv` 같은 표기는 그 스크립트를 **실제로 돌렸을 때의 기록**이라, 현재 구조에 맞춰 바꾸면 이력이 훼손된다. 다시 돌릴 도구가 아니라 유래 명세로 남긴 것이다.

## 파일

### Gemini 원 측정 (2026-08-11 ~ 08-12) — 재생 불가

| 파일 | 규모 | 근거가 되는 것 |
|---|---|---|
| `places.csv` | 389행 | **LLM이 생성한 장소명의 유일한 보존처.** 옛 로직(이름 게이트 이전)의 점수·밴드·매칭 결과를 함께 담고 있어 게이트 도입 효과 대조의 한쪽 축이다. `sourceBatch` 컬럼으로 배치별 분해가 된다 |
| `requests.csv` | 30행 | 파싱 실패율 16.7%, `parseError` 원문, 부가 지표(일수·중복·`startTime` 순서) |
| `verdicts/batch1-174326.csv` | 37행 | \ |
| `verdicts/batch2-153453.csv` | 27행 | **옛 판정 104건.** 옛 밴드 기준 표본이라 현행 수치의 근거가 아니다 — 아래 "두 워크시트를 섞지 않는다" 참고 |
| `verdicts/batch3-184600.csv` | 40행 | / |

`places.csv`·`requests.csv`는 배치 3개를 [merge3.py](scripts/merge3.py)로 병합한 최종본이다(우선순위: 최신 배치가 그 `requestId`의 canonical).

### 배치별 요청 지표 — `batches/`

| 파일 | 근거가 되는 것 |
|---|---|
| `hallucination-baseline-20260811-174326-requests.csv` | batch1 — **파싱 실패율 28.6%(4/14)의 유일한 근거** |
| `hallucination-baseline-20260812-153453-requests.csv` | batch2 — 0.0%(0/4) |
| `hallucination-baseline-20260812-184600-requests.csv` | batch3 — 7.7%(1/13) |

**병합본 `requests.csv`만으로는 배치별 분해가 안 된다.** 병합이 `requestId`당 승자 배치만 남기므로 패자 배치의 행이 사라지기 때문이다(병합본의 배치 분포는 batch1 14 · batch2 3 · batch3 13). 그래서 [BASELINE-ARTIFACT-ANALYSIS.md](../BASELINE-ARTIFACT-ANALYSIS.md) 판정 3의 *"28.6%는 호출이 14건만 성공한 초기 배치의 값이고 전체 기준은 16.7%"* 를 검산하려면 이 셋이 필요하다.

배치별 **장소별** CSV는 승격하지 않았다 — 파싱 실패는 요청 단위 지표라 `-requests.csv`만으로 재현되고, 장소 데이터는 `places.csv`가 `sourceBatch` 컬럼과 함께 보존한다.

### 재채점 (2026-08-25) — 시간이 지나면 재현 불가

| 파일 | 규모 | 근거가 되는 것 |
|---|---|---|
| `places.csv` | 389행 | **현행 모든 수치의 근거.** 프로덕션 `lookupBestPlace()`(이름 게이트 포함)로 다시 매긴 결과. `NAME_MISMATCH` 결과값과 `rejectedCandidateName`(게이트가 걸러낸 후보)이 여기에만 있다 |
| `verdicts.csv` | 50행 | 새 층 기준 층화 워크시트(층당 10건, 시드 42). **2026-08-27 판정 완료** — 이 파일이 현행 지어냄률 9.5%·세탁 통과율 0.6%의 근거다. 판정자는 Claude 세션(웹 검색으로 실존 확인, 근거는 `note` 열) + 사용자 검토 |

카카오만 다시 검색한 것이라 LLM 재호출은 없었다. **다시 돌리면 같은 값이 안 나온다** — 카카오 DB가 시간에 따라 변하기 때문이다(이번엔 2주 차이라 `NO_RESULT` 드리프트가 0건이었다).

### 파이프라인 측정 (2026-08-27) — `pipeline-20260827/` · ROADMAP 8-6

3점 비교의 **마지막 측정점**이다. 같은 입력 세트(`BaselineInputSet` 30요청)를 `AiCoursePipeline`에 태우고, 출력 장소 전건을 **baseline과 같은 채점기**(`HallucinationScoring`)로 판정했다.

| 파일 | 규모 | 근거가 되는 것 |
|---|---|---|
| `places.csv` | 525행 | 장소별 채점 + 파이프라인 고유 축. **앞 17열이 baseline CSV와 동일**해 `BASELINE_RESCORE_FROM`으로 되먹일 수 있고, 뒤에 `source`·`modifier`·`slotType`·좌표·`placeUrl`이 붙는다 |
| `requests.csv` | 30행 | 요청별 `elapsedMs`(지연 재측정의 근거) · `curationCurator/Fallback/Unfilled` · `droppedAfterCuration` |
| `verdicts.csv` | 50행 | 층화 워크시트(층당 10건, 시드 42). **판정 완료 — 지어냄률 0.0%의 근거.** 판정자는 Claude 세션 + 사용자 검토 |
| `raw/draft-*.json` | 30건 | `AiCourseDraft` 원본. 파이프라인은 요청당 LLM을 네 번 부르므로 단일 응답 원문이 없고, 초안이 그 자리를 대신한다 |

**채집 당시 상태** — 재현이 안 되므로 조건을 함께 남긴다.

- 30요청 **전건 성공**, 장소 525개, 16분 소요
- 출처 분해: `SEEDED` 409 · `LISTED` 108 · **`SUGGESTED` 8(1.5%)**
- **`ai.curation.slot{result=fallback}` 1 / 545 슬롯(0.2%, #07 제주A)** — 폴백이 채운 장소는 환각률이 구조적으로 0에 가까워 결과를 좋아 보이게 하므로 반드시 병기한다(STEP-7 판정 13). `unfilled`는 0
- **네이버 rate limit(429) 12회 발생** — 일일 쿼터가 아니라 순간 제한(`errorCode 420`)이고 요청 #05·#07·#19·#27에서 났다. fail-open이라 요청은 살았고, 오히려 그 요청들의 VIEWPOINT 슬롯 확보량이 평균보다 많았다(2.25 대 1.77 — TourAPI가 덮었다). 재측정하지 않은 근거는 [STEP-8](../../steps/STEP-8-switch.md) 판정 4
- 예산은 운영값(30초)이 아니라 **180초**로 두고 쟀다 — 최대 지연 28.0초가 예산에 붙어 있어 운영값으로 재면 일부 요청이 504로 잘리고 그 장소가 환각률 분모에서 통째로 빠진다. 대신 `elapsedMs`로 역산한다(p50 22.3초 · p95 29.4초 · max 30.1초 · 30초 초과 1건)

**읽을 때 주의** — `NO_RESULT` 밴드의 뜻이 baseline과 다르다. `SEEDED`·`LISTED`는 `GroundingStage`의 승계 분기에서 카카오를 **한 번도 부르지 않고** 통과하므로, 카카오에 없어도 네이버·TourAPI 좌표와 함께 코스에 실린다. baseline에서 `NO_RESULT`는 "좌표를 못 얻음"이었지만 여기서는 "카카오 링크만 없음"이다. **baseline과 직접 비교할 수 있는 축은 `SUGGESTED` 8건뿐이고, 전수 판정 결과 8건 모두 실존했다.**

### LLM 원본 응답 — `gemini-20260811/raw/` 17건

응답 절단(JSON 파싱 실패)의 **유일한 물증**이다. `gemini-20260811/raw/184600/response-30-삼척-C.json`이 키 이름 중간에서 잘린 그 파일이고(386자·458바이트), 정상 응답 16건(1,401~1,658자·1,557~1,862바이트)과 나란히 놓여야 비교가 성립한다.

> 문서가 인용하는 길이는 **문자 수**다 — 한글이 UTF-8에서 3바이트라 파일 크기와 다르다.

**요청 15~30만 커버한다** — 1~14의 원본은 raw 덤프 기능이 추가되기 전에 측정돼 남아 있지 않다. 배치를 `raw/153453/`·`raw/184600/`으로 갈라 둔 것은 `response-19-영주-A.json`이 두 배치에 모두 있고, 어느 배치 산출인지가 `merge3.py`의 우선순위와 직결되기 때문이다.

### 스크립트

| 파일 | 역할 |
|---|---|
| [merge3.py](scripts/merge3.py) | 배치 3개 → `gemini-20260811/places.csv`·`requests.csv` 병합. 병합본의 유래 명세 |
| [fix_shifted.py](scripts/fix_shifted.py) | 수동 검증 CSV의 컬럼 밀림 교정. 일회성 도구지만 **판정 데이터에 그런 사고가 있었다는 이력**으로 남긴다 |

## 재집계 방법

집계 규칙은 [AI-HALLUCINATION-GEMINI.md](../AI-HALLUCINATION-GEMINI.md)의 "지표의 정의"에 있다. 주의할 점 셋:

1. **`#`으로 시작하는 주석 행을 제외**한다(수동 검증 CSV의 머리말 6줄)
2. **어느 워크시트를 쓰는지 먼저 정한다.** 현행 수치는 `gemini-rescore-20260825/verdicts.csv`(50행) 하나에서 나온다. 옛 `gemini-20260811/verdicts/*.csv`(104건)는 **옛 밴드 기준 표본**이라 `scoreBand` 컬럼을 그대로 쓰면 안 되고, 재채점 결과와 장소 키 `(requestId, day, aiPlaceName)`로 조인해 층을 다시 매겨야 한다
3. **옛 판정 104건 중 5건(영주 #19)은 재채점 대상에 없다** — 초기 배치 재시도로 요청 19의 장소 목록이 바뀌었기 때문이다. 그 표본의 유효 건수는 99건이다(현행 50건 워크시트에는 해당 없음)
4. **두 워크시트의 값을 섞지 않는다.** 같은 지표를 다른 표본에서 잰 값이라 지어냄률이 9.5%(50건)와 10.1%(99건)로 갈린다. 경위는 [AI-HALLUCINATION-GEMINI.md](../AI-HALLUCINATION-GEMINI.md)의 `[정정]` 블록에 있다

하네스로 다시 돌리려면:

```bash
# 단일 호출 baseline 새로 측정 (LLM 실호출 — 비용 발생)
./gradlew benchmarkTest --tests '*AiHallucinationBaselineTest*' --rerun

# 기존 장소명으로 카카오만 재채점 (LLM 호출 없음)
BASELINE_RESCORE_FROM=docs/tasks/ai-course-create/hallucination/artifacts/gemini-20260811/places.csv \
  ./gradlew benchmarkTest --tests '*AiHallucinationBaselineTest*' --rerun

# 파이프라인 측정 (8-6) — LLM 약 120회 · 네이버 540~900 · TourAPI ≤270 · 카카오 1,050~1,550, 약 16분
./gradlew benchmarkTest --tests '*AiPipelineHallucinationBenchmarkTest*' --rerun

# 스모크 2요청만
PIPELINE_HALLUCINATION_REQUEST_LIMIT=2 \
  ./gradlew benchmarkTest --tests '*AiPipelineHallucinationBenchmarkTest*' --rerun
```

**TourAPI 일 1,000건이 가장 빡빡하다** — 파이프라인 30요청이 ≤270회를 쓰므로 하루 3회가 한계다.
파이프라인 장소 CSV도 앞 17열이 baseline과 같아 `BASELINE_RESCORE_FROM`으로 되먹일 수 있다
(채점 로직만 바뀌었을 때 LLM 없이 재채점하는 용도).

재현이 어디까지 되고 무엇이 안 되는지는 [AI-HALLUCINATION-GEMINI.md](../AI-HALLUCINATION-GEMINI.md)의 "재측정 재현 조건"에 정리돼 있다.
