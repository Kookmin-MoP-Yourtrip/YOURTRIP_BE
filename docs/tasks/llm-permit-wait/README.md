# LLM 동시 호출 슬롯 대기 계측 — "모델이 느린가, 슬롯이 좁은가"를 가른다

> [#173](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/173)의 설계·진행 기록이다. [#108](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/108)(`llm.max-concurrent-calls` 재실측)의 전제 조건이며, #108 자체는 이 작업의 범위가 아니다.
>
> **왜 하는가**: AI 코스 생성의 실측 지연(p50 22.3초 · p95 29.4초, [STEP-8](../ai-course-create/steps/STEP-8-switch.md))이 설계 추정(p95 17~24초)을 크게 넘고 30초 예산에 붙어 있다. 원인 후보 1순위는 서버 전체가 공유하는 LLM 동시 호출 슬롯 2개(`llm.max-concurrent-calls: 2`)다. 그런데 **이 가설을 확인할 지표가 없었다.**

## 무엇이 비어 있었나

`OpenAiLlmClient.generate()`는 세마포어 대기를 `ai.llm.call` 지연에서 **일부러 빼고** 잰다. 섞으면 "모델이 느려졌다"와 "슬롯이 좁다"를 구분할 수 없기 때문이다. 결정 자체는 옳았지만, 그 대가로 **대기 시간은 어떤 지표에도 잡히지 않았다.**

그 결과 Curator 단계(`ai.course.pipeline.duration{stage="curator"}`)가 느려도 원인을 둘 중 하나로 좁힐 수 없었다.

- 모델 응답이 느리다 → `ai.llm.call`이 함께 오른다
- 슬롯이 좁다 → `ai.llm.call`은 그대로인데 단계 시간만 오른다 — **이 경우를 직접 보여 주는 값이 없었다**

슬롯이 좁을 거라고 의심할 근거는 구조에 있다. 3일 코스면 Curator 호출 3개가 슬롯 2개를 두고 두 번에 나눠 돌고(설계 문서의 "+3~6초"), 세마포어가 서버 전체 공용이라 동시 요청끼리도 같은 슬롯을 다툰다.

## 추가한 지표

| 이름 | 종류 | 태그 | 읽는 법 |
|---|---|---|---|
| `ai.llm.permit.wait` | Timer (히스토그램) | `agent`, `result`=`acquired`·`timeout`·`interrupted` | 슬롯을 얻기까지 기다린 시간. `timeout`은 `llm.timeout-ms` 안에 못 얻어 **호출을 시작조차 못 한** 사건 |
| `ai.llm.permits.in_use` | Gauge | — | 지금 쥐고 있는 슬롯 수. `max-concurrent-calls`에 붙어 있는 시간이 길수록 포화 |
| `ai.llm.permits.waiting` | Gauge | — | 슬롯을 기다리는 스레드 수 (`Semaphore.getQueueLength()`, 근사값) |

설계 판단은 기존 `AiCourseMetrics`의 원칙을 그대로 따랐다.

- **히스토그램으로 낸다** — p95를 `histogram_quantile`로 측정이 끝난 뒤에 잘라 보기 위해서다. 상한은 요청 예산과 같은 30초다. 예산보다 오래 기다리는 대기는 의미가 없다.
- **`result`를 나눈다** — 정상 대기 분포와 "끝내 못 얻은 사건"이 섞이면 꼬리가 무엇 때문인지 못 가른다. `ai.llm.call`이 `outcome`을 나누는 것과 같은 논리다. `interrupted`는 용량 신호가 아니라서 `timeout`과 섞지 않는다.
- **0으로 미리 등록한다** — `ai.llm.call`은 `agent` 태그가 설정에서 와서 0 등록에서 빠졌지만, 어댑터는 생성 시점에 agent 목록을 알기 때문에 그 자리에서 등록할 수 있다. #108에서 "타임아웃 0건"과 "시계열 없음"을 구분해야 한다.
- **게이지를 따로 둔다** — 타이머는 대기가 **끝난 뒤에야** 기록된다. 20초짜리 대기는 20초 뒤에야 보인다. 게이지는 "지금 막혀 있는가"를 바로 보여 준다.

### 함께 정정한 것

`AiCourseMetrics.LLM_LATENCY_MAX`의 주석이 "세마포어 대기와 호출이 겹치는 최악(20 + 20초)"이라고 적혀 있었다. 실제 `ai.llm.call`은 대기를 포함하지 않으므로, 이 주석을 실제 동작에 맞게 바로잡았다.

## 분석 쿼리 (PromQL)

에이전트별 대기 p95:

```promql
histogram_quantile(0.95, sum by (le, agent) (rate(ai_llm_permit_wait_seconds_bucket[5m])))
```

슬롯을 끝내 못 얻은 비율:

```promql
sum(rate(ai_llm_permit_wait_seconds_count{result="timeout"}[5m]))
  / sum(rate(ai_llm_permit_wait_seconds_count[5m]))
```

Curator 단계 시간 대비 슬롯 대기 시간 (배치 측정 구간 전체 합 기준):

```promql
sum(increase(ai_llm_permit_wait_seconds_sum{agent="curator"}[1h]))
  / sum(increase(ai_course_pipeline_duration_seconds_sum{stage="curator"}[1h]))
```

> 이 비율은 **1을 넘을 수 있다.** Curator는 day별로 병렬 실행되므로 day별 대기 시간의 합이 단계의 실제 경과 시간보다 클 수 있다. "단계 시간의 몇 %가 대기였다"로 읽지 말고, 슬롯 조정 전후를 같은 조건에서 비교하는 용도로 쓴다.

포화 상태 관찰 (Grafana 시계열):

```promql
ai_llm_permits_in_use
ai_llm_permits_waiting
```

## 테스트로 고정한 것

`OpenAiLlmClientTest$PermitWaitMetrics`:

- 호출이 없어도 설정된 agent의 `acquired`·`timeout`·`interrupted` 시계열이 0으로 존재한다
- 슬롯 1개에 300ms 응답 2건을 동시에 보내면, 뒤 호출의 `acquired` 대기가 250ms 이상으로 기록된다
- **재시도 중인 호출이 슬롯을 쥐고 있으면 뒤 호출이 `timeout`으로 실패한다** — 429 + 백오프로 슬롯 점유 시간을 만든다. `llm.timeout-ms`가 HTTP 읽기 타임아웃과 공유되므로 응답 지연으로는 이 상황을 만들 수 없다. 운영에서 429가 슬롯을 묶는 모습이 바로 이것이다
- 실패한 호출도 슬롯을 반납해 `in_use` 게이지가 0으로 돌아온다

`AiCourseMetricsTest`: Prometheus 스크레이프에 `ai_llm_permit_wait_seconds_bucket`이 실린다. `SimpleMeterRegistry`로는 버킷 유무를 확인할 수 없어 실제 Prometheus 레지스트리로 확인한다.

## 로컬 E2E 확인 — 세 번째 Curator가 8.1초를 기다렸다

로컬(앱·PostgreSQL·Redis 모두 `localhost`, 실제 OpenAI 호출)에서 3일 코스를 1회 생성했다. 요청 중 0.5초 간격으로 게이지를 함께 폴링했다. 인증은 이전 E2E와 같은 방식으로, 로컬 DB에 임시 유저를 시딩해 받았다(`DB_DDL_AUTO=create`라 재기동하면 사라진다).

- 요청: `경주`, 3일, `WALK`·`FRIENDS`·`FOOD` → **201**, 22.4초, Curator 선택 18/18 슬롯(폴백 0)
- 기동 직후 `planner`·`curator`·`place-profile` × 3결과 = **9개 시계열이 0으로 존재**했다 (0 등록 확인)

| 지표 | 값 |
|---|---|
| 요청 전체 (`ai.course.request.duration`) | 22.19초 |
| Planner 단계 / 호출 | 7.45초 / 7.44초 (대기 0.00002초) |
| **Curator 단계** | **13.34초** |
| Curator 호출 3건 (`ai.llm.call`) | 합 21.58초, 최대 8.25초 |
| **Curator 슬롯 대기** (`ai.llm.permit.wait`) | **3건 중 합 8.09초, 최대 8.09초** — 두 건은 즉시 얻고 한 건이 8.1초 기다렸다 |

게이지 폴링(0.5초 간격, 35회)에서도 같은 모습이 보였다.

```
in_use=1 waiting=0   × 12   ← Planner 호출 중
in_use=0 waiting=0   ×  2   ← 후보 공급 (LLM 미사용)
in_use=2 waiting=1   × 13   ← Curator 2건 실행 + 1건 대기
in_use=1 waiting=0   ×  8   ← 세 번째 Curator 단독 실행
```

**해석.** 설계가 추정한 "슬롯 2개면 Curator 3개가 두 번에 나뉘어 +3~6초"가 실제로 일어났고, 이번 표본에서는 그 대가가 **8.1초**였다. Curator 단계 13.3초는 "가장 느린 호출 8.3초"가 아니라 "먼저 끝난 호출(≈8.1초) + 세 번째 호출(≈5.3초)"의 직렬 합에 가깝다. 세 호출이 동시에 나갔다면 단계 시간은 가장 느린 호출 근처(≈8.3초)까지 줄어들 여지가 있다 — **요청 전체(22.2초)의 약 1/4이다.**

**한계.** 표본 1건이고 단일 요청이라 다른 요청과의 슬롯 경합은 없다(경합이 있으면 대기는 더 길어진다). 슬롯을 늘렸을 때 429가 나는지는 이 측정으로 알 수 없다 — 그 판단이 #108의 몫이다. 게이지 폴링은 스크레이프 자체에 시간이 걸려 0.5초보다 성기게 찍혔으므로, 대기 길이는 타이머 값(8.09초)을 기준으로 삼는다.

## 다음 단계

이 지표가 쌓이면 다음 작업의 전후 비교 근거가 된다. 각각 별도 이슈로 진행한다.

1. #108 — 같은 E2E 세트로 `max-concurrent-calls`를 2 → 3 → 4로 올려 가며 대기·429·Curator 지연을 비교한다 (상한은 OpenAI 티어 ÷ 최대 인스턴스 수)
2. 예산이 끝난 뒤에도 남아 슬롯을 쥐고 있는 LLM 호출 정리 — 재시도 전 남은 예산 확인, 진행 중인 호출 취소
3. AI 요청 동시 입장 제한 — 슬롯 용량을 넘는 요청이 Tomcat 스레드(운영 32개)를 30초씩 쥐지 않게 한다
