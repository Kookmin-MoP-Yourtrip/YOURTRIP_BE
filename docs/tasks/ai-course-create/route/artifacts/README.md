# 동선 측정 산출물 (ROADMAP 3-7 · 3-8)

[STEP-3-route-optimizer.md](../../steps/STEP-3-route-optimizer.md)의 "동선 개선 효과 실측"·"식사 벌점 계수 스윕" 절과 판정 8이 근거로 삼는 원본이다. 문서의 표를 의심하면 여기서 직접 재집계할 수 있다.

## 왜 커밋하는가

측정 하네스는 결과를 레포 루트 `results/`에 쓰는데, 그 디렉터리는 [.gitignore:63](../../../../../.gitignore)으로 제외된다. **그 규칙 때문에 OpenAI 재측정 산출물이 실제로 소실된 전례가 있다** — 113개 브랜치 전체 히스토리와 모든 worktree에서 찾지 못해 그 측정 문서는 검증 불가능해져 삭제됐다([hallucination/artifacts/README.md](../../hallucination/artifacts/README.md)). `results/`는 하네스의 작업 디렉터리로 계속 두고, **정본은 여기다.**

## 파일

| 파일 | 규모 | 무엇인가 |
|---|---|---|
| `route-pipeline-places-20260826.csv` | 523행 | **입력.** 파이프라인 30요청을 실제로 태우고 `RouteOptimizer`에 들어가기 직전의 입력을 캡처한 것 |
| `route-optimization-effect-pipeline-20260826.csv` | 90행 | **3-7 결과.** day 하나가 한 행이고 세 팔의 거리·교차·역행이 나란히 있다 |
| `route-meal-penalty-sweep-20260827.csv` | 1,080행 | **3-8 결과.** 같은 90 day를 식사 벌점 계수 12가지로 돌린 것 — `day × λ` 하나가 한 행이다 |

## 재현

```bash
# ① 채집 (LLM 약 120회 · 네이버 540~900회 · TourAPI ≤270회 · 카카오 600~1,000회, 약 21분)
./gradlew benchmarkTest --tests '*AiCourseRouteInputProbeTest*' --rerun

# ② 계산 (외부 호출 0회 — 같은 CSV 면 몇 번을 돌려도 바이트 단위로 같다)
ROUTE_EFFECT_FROM=docs/tasks/ai-course-create/route/artifacts/route-pipeline-places-20260826.csv \
  ./gradlew benchmarkTest --tests '*RouteOptimizationEffectTest*' --rerun

# ③ 식사 벌점 계수 스윕 (외부 호출 0회 — 같은 입력을 계수만 바꿔 12번 돌린다)
./gradlew benchmarkTest --tests '*RouteMealPenaltySweepTest*' --rerun
```

**둘로 나눈 것이 이 산출물 구조의 요지다.** 계산에 외부 호출이 없어 계수를 바꿔 다시 재는 데 비용이 들지 않고, 캡처한 입력이 남아 있는 한 언제든 같은 값이 재현된다. 반대로 ①은 LLM 이 비결정적이라 다시 돌리면 다른 코스가 나온다 — **그래서 ①의 산출물을 여기 커밋하는 것이 재현의 전제다.**

**3-8이 그 구조가 실제로 값을 치른 자리다.** 같은 CSV 를 계수만 바꿔 12번 다시 돌렸는데 LLM 비용이 0이었다. 채집과 계산을 갈라 두지 않았다면 계수 하나를 확인하는 데 21분과 LLM 120회가 들었을 것이고, 그러면 애초에 재지 않았을 것이다.

**채집 당시 상태** — 30요청 전건 성공, day 90개, 장소 523건(day 당 5.81개). 출처는 `SEEDED` 416 · `LISTED` 97 · `SUGGESTED` 10. `ai.curation.slot{result=fallback}`이 **0건**이라 7-3 폴백이 채운 장소는 하나도 섞이지 않았다(섞였다면 후보 목록 상위 3이 그대로 실려 동선 분포가 달라진다 — STEP-7 판정 13).

## `route-pipeline-places-20260826.csv` — 열의 뜻

| 열 | 뜻 |
|---|---|
| `day` · `placeIndex` | **`placeIndex` 오름차순이 최적화 전 순서**(Planner 슬롯 순서)다 |
| `placeName` · `latitude` · `longitude` | `RoutePlace` 그대로. 좌표는 소수점 7자리 고정 |
| `slotType` | **Planner 가 정한 값.** 추론이 아니다 — 슬롯은 식사 시간창 벌점을 통해 최적 순열 선택에 개입하므로, 사후에 추론한 값으로는 이 측정을 할 수 없다 |
| `source` | `SEEDED`(네이버) · `LISTED`(TourAPI) · `SUGGESTED`(카카오). 민감도 축의 재료 |
| `dayStartTime` · `travelMode` | Planner 와 키워드가 정한 실제 최적화 입력. 시작이 밀리면 식사 벌점이 달라져 최적 순열이 바뀐다 |

## `route-optimization-effect-pipeline-20260826.csv` — 열의 뜻

| 열 | 뜻 |
|---|---|
| `beforeKm` | 완전탐색을 끈 순서(`optimize(request, 1)`)의 총 이동거리 |
| `afterKm` | 프로덕션 `optimize(request)` |
| `shortestKm` | **거리만** 최소화했을 때의 참조값. 하네스가 직접 순열을 돈다 |
| `concessionKm` | `afterKm − shortestKm`. 식사 시간창·하루 초과를 맞추느라 양보한 몫 |
| `beforeKmPerLeg` · `afterKmPerLeg` | 구간당 평균. day 마다 장소가 4~7개로 흔들려 총합만으로는 장소 많은 day 가 지배한다 |
| `mealCount` | day 안의 MEAL 슬롯 수. **판정 8의 축이다** |
| `beforeCrossings` · `afterCrossings` | 경로 폴리라인의 자기교차(진짜 교차만) |
| `beforeBacktracks` · `afterBacktracks` | 연속 3점에서 진행 방향과 반대 성분이 생기는 꼭짓점 수 |

### 재집계 시 주의

1. **`n < 3`인 day 는 결과 파일에 없다.** 점이 둘이면 순서를 바꿔도 거리가 같아 측정 대상이 아니다. 이번 표본에서는 해당 day 가 0건이었다(Planner 가 슬롯을 5~7로 clamp 한다)
2. **`source`가 빈 행은 하루 초과로 드롭된 장소다.** 이번 표본에는 없다 — 기본 `dayEndTime`이 23:59라 축소·드롭 절차가 발동하지 않는다
3. **감소율은 전체 합과 day 별 중앙값이 다르다**(32.5% 대 33.1%). 어느 쪽을 쓰는지 반드시 밝힌다 — 합은 거리가 긴 day 가, 중앙값은 짧은 day 가 지배한다

## `route-meal-penalty-sweep-20260827.csv` — 열의 뜻

| 열 | 뜻 |
|---|---|
| `lambda` | 그 행을 낸 `MEAL_PENALTY_PER_MIN`. **현행 프로덕션은 2.00**이다 |
| `beforeKm` · `shortestKm` | **λ와 무관하다.** before는 탐색을 끄므로 비용 함수를 부르지 않고, shortest는 애초에 거리만 본다. 행마다 되풀이되므로 재집계 시 중복을 주의한다 |
| `afterKm` | 그 계수로 프로덕션 최적화기가 고른 순열의 총 이동거리 |
| `mealViolationMinutes` | 식사 시간창 위반 합(분). **표시 시각이 아니라 시간 모델을 되짚어 복원한 내부 분**이다 |
| `crossings` · `backtracks` | 3-7과 같은 기하 지표. `λ=0` 행이 **판정 8의 인과 검증**이다 |

### 재집계 시 주의

1. **모든 day가 12개 λ를 전부 가진다**(90 × 12 = 1,080행). 한 팔에서라도 드롭이 생긴 day는 통째로 뺐기 때문이고, 이번 표본에서는 그런 day가 0건이었다
2. **`λ=0` 행의 `afterKm`은 `shortestKm`과 같다.** 우연이 아니라 하네스가 단언하는 것이다 — 비용에 거리 항만 남으므로 같아야 하고, 다르면 둘 중 하나가 틀린 것이다
3. **`λ ≥ 2.00` 구간은 전부 같다.** 뜻없이 복사된 것이 아니라, 그 구간의 계수들이 **같은 순열을 고르기 때문**이다(포화)
