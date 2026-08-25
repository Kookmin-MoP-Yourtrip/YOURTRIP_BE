# 환각률 측정 원본 산출물

[AI-HALLUCINATION-GEMINI.md](../AI-HALLUCINATION-GEMINI.md)와 [BASELINE-ARTIFACT-ANALYSIS.md](../BASELINE-ARTIFACT-ANALYSIS.md)의 **모든 수치가 근거로 삼는 원본**이다. 문서의 표를 의심하면 여기서 직접 재집계할 수 있다.

## 왜 커밋하는가

측정 하네스는 결과를 레포 루트 `results/`에 쓰는데, 그 디렉터리는 [.gitignore:63](../../../../../.gitignore)으로 제외된다(k6 덤프 같은 대용량 부하테스트 산출물과 같은 규칙). **그 규칙 자체는 유지하되, 환각률 측정분만 예외로 승격한 것이 이 디렉터리다.**

이유는 실제 사고다. **OpenAI(luna/nano) 재측정의 산출물은 이 규칙 때문에 소실됐다** — 113개 브랜치 전체 히스토리·모든 worktree·파일시스템에서 찾지 못했고, 그래서 그 측정 문서는 검증 불가능해져 삭제됐다([BASELINE-ARTIFACT-ANALYSIS.md](../BASELINE-ARTIFACT-ANALYSIS.md) 판정 5). Gemini 산출물도 한동안 다른 worktree의 작업 디렉터리에만 남아 있었고, 그 브랜치를 지웠다면 똑같이 사라졌을 것이다.

`results/`는 하네스의 작업 디렉터리로 계속 두고, **정본은 여기다.**

## 파일

### Gemini 원 측정 (2026-08-11 ~ 08-12) — 재생 불가

| 파일 | 규모 | 근거가 되는 것 |
|---|---|---|
| `merged3-places.csv` | 389행 | **LLM이 생성한 장소명의 유일한 보존처.** 옛 로직(이름 게이트 이전)의 점수·밴드·매칭 결과를 함께 담고 있어 게이트 도입 효과 대조의 한쪽 축이다. `sourceBatch` 컬럼으로 배치별 분해가 된다 |
| `merged3-requests.csv` | 30행 | 파싱 실패율 16.7%, `parseError` 원문, 부가 지표(일수·중복·`startTime` 순서) |
| `manual-verification-20260811-174326.csv` | 37행 | \ |
| `manual-verification-20260812-153453.csv` | 27행 | **사람이 내린 판정 104건.** 지어냄률·세탁 통과율 전부가 여기서 나온다 |
| `manual-verification-20260812-184600.csv` | 40행 | / |

`merged3-*`은 배치 3개를 [merge3.py](merge3.py)로 병합한 최종본이다(우선순위: 최신 배치가 그 `requestId`의 canonical). 배치별 원본은 이 병합본이 대체하므로 승격하지 않았다.

### 재채점 (2026-08-25) — 시간이 지나면 재현 불가

| 파일 | 규모 | 근거가 되는 것 |
|---|---|---|
| `hallucination-baseline-rescore-20260825-110536.csv` | 389행 | **현행 모든 수치의 근거.** 프로덕션 `lookupBestPlace()`(이름 게이트 포함)로 다시 매긴 결과. `NAME_MISMATCH` 결과값과 `rejectedCandidateName`(게이트가 걸러낸 후보)이 여기에만 있다 |
| `manual-verification-rescore-20260825-110536.csv` | 50행 | 새 층 기준 층화 워크시트. **verdict 미기입** — 채우면 표본 대표성이 정식으로 회복된다 |

카카오만 다시 검색한 것이라 LLM 재호출은 없었다. **다시 돌리면 같은 값이 안 나온다** — 카카오 DB가 시간에 따라 변하기 때문이다(이번엔 2주 차이라 `NO_RESULT` 드리프트가 0건이었다).

### LLM 원본 응답 — `raw-*/` 17건

응답 절단(JSON 파싱 실패)의 **유일한 물증**이다. `raw-20260812-184600/response-30-삼척-C.json`이 386바이트에서 키 이름 중간에 잘린 그 파일이고, 정상 응답 1.4~1.7KB와 나란히 놓여야 비교가 성립한다.

**요청 15~30만 커버한다** — 1~14의 원본은 raw 덤프 기능이 추가되기 전에 측정돼 남아 있지 않다. 배치 디렉터리를 그대로 둔 것은 `response-19-영주-A.json`이 두 배치에 모두 있고, 어느 배치 산출인지가 `merge3.py`의 우선순위와 직결되기 때문이다.

### 스크립트

| 파일 | 역할 |
|---|---|
| [merge3.py](merge3.py) | 배치 3개 → `merged3-*` 병합. 병합본의 유래 명세 |
| [fix_shifted.py](fix_shifted.py) | 수동 검증 CSV의 컬럼 밀림 교정. 일회성 도구지만 **판정 데이터에 그런 사고가 있었다는 이력**으로 남긴다 |

## 재집계 방법

집계 규칙은 [AI-HALLUCINATION-GEMINI.md](../AI-HALLUCINATION-GEMINI.md)의 "지표의 정의"에 있다. 주의할 점 셋:

1. **`#`으로 시작하는 주석 행을 제외**한다(수동 검증 CSV의 머리말 5줄)
2. **판정과 재채점 결과는 장소 키 `(requestId, day, aiPlaceName)`로 조인**한다. 수동 검증 CSV의 `scoreBand` 컬럼은 **재채점 전 옛 밴드**라 그대로 쓰면 안 된다
3. **판정 104건 중 5건(영주 #19)은 재채점 대상에 없다** — 초기 배치 재시도로 요청 19의 장소 목록이 바뀌었기 때문이다. 유효 표본은 99건

하네스로 다시 돌리려면:

```bash
# 새로 측정 (LLM 실호출 — 비용 발생)
./gradlew benchmarkTest --tests '*AiHallucinationBaselineTest*' --rerun

# 기존 장소명으로 카카오만 재채점 (LLM 호출 없음)
BASELINE_RESCORE_FROM=results/merged3-places.csv \
  ./gradlew benchmarkTest --tests '*AiHallucinationBaselineTest*' --rerun
```

재현이 어디까지 되고 무엇이 안 되는지는 [AI-HALLUCINATION-GEMINI.md](../AI-HALLUCINATION-GEMINI.md)의 "재측정 재현 조건"에 정리돼 있다.
