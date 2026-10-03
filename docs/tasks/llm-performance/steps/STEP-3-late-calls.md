# 마감 뒤 남는 LLM 호출 정리

> [LLM 호출 경로 성능·안정성 로드맵](../README.md)의 3단계이며, [#176](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/176)의 설계·진행 기록이다.

## 왜 필요한가

[STEP-2](STEP-2-baseline.md) 동시 3·5명 시나리오에서 Curator 슬롯의 42%·74%가 폴백으로 채워졌는데 LLM 호출 실패는 0건이었다. 마감으로 버려진 day 의 호출이 **응답이 나간 뒤에도 끝까지 돌아 성공**했다는 뜻이다. 그 호출은 결과가 버려지는데도 슬롯과 토큰을 쓰고, 그 사이 뒤 요청의 호출은 슬롯을 기다린다. 이 점유가 남아 있으면 4단계(#108)에서 동시 호출 수를 바꿔 잰 값에 섞여 효과를 귀속할 수 없다.

## 1. 조사 — 취소 수단이 실제로 무엇을 끊는가

Planner·Curator는 `llmClient.generateAsync(call, aiAgentExecutor)`로 받은 future 를 `get(remainingMs)`로 기다리다, 마감되면 아무것도 하지 않고 돌아선다. 남은 호출을 멈추는 방식을 고르려면 각 단계(슬롯 대기 → HTTP → 재시도 백오프)가 무엇에 반응하는지 알아야 했다. 라이브러리 문서가 아니라 **이 저장소의 실제 배선**(reactor-netty 요청 팩토리 + 세마포어 + `LlmRetryExecutor`)으로 확인했다 — 이 어댑터는 문서상 기본값과 실제 HTTP 스택이 달랐던 이력이 있다(`OpenAiLlmClient.buildChatModel` 주석).

학습 테스트: `LlmCallCancellationLearningTest` (WireMock 이 응답을 3초 늦게 주고, 300ms 뒤 취소·인터럽트)

| 질문 | 결과 |
|---|---|
| Q0. 취소하지 않으면 | 3.0초에 정상 종료 (기준) |
| **Q1. `generateAsync` future 를 `cancel(true)`** | **아무것도 멈추지 않는다.** 슬롯은 늦은 응답(3초)까지 점유되고 호출은 `success`로 기록된다 — STEP-2 의 "폴백인데 실패 0" 과 같은 모양 |
| Q2. HTTP 응답을 기다리는 스레드를 인터럽트 | **2ms 안에 끝나고** 슬롯이 빈다. 추가 요청 없음 |
| Q2-1. 그때 나오는 예외 | `ResourceAccessException ← IOException ← InterruptedException`, 인터럽트 표시는 다시 켜져 있다 |
| Q3. 슬롯을 기다리는 스레드를 인터럽트 | 즉시 끝나고 `permit.wait{result=interrupted}`로 기록된다 |
| Q4. 재시도 백오프 중 인터럽트 | 즉시 끝난다. 다만 `IllegalStateException`을 거쳐 `transport_error`로 기록된다 |

### 판정

- **현재 구조에서는 취소가 불가능하다.** `CompletableFuture.supplyAsync`가 만든 future 는 실행 중인 스레드와 연결이 없다 — JDK javadoc 대로 `mayInterruptIfRunning`은 이 구현에서 효과가 없다. 에이전트가 `cancel`을 불러도 바뀌는 것은 future 의 표시뿐이다
- **인터럽트는 세 단계 모두를 즉시 끊는다.** 슬롯 대기(`Semaphore.tryAcquire`), HTTP(reactor-netty 가 구독을 해지하고 `IOException`으로 번역), 백오프(`Thread.sleep`). 즉 "실행 중인 스레드를 인터럽트할 수 있는 future"만 있으면 남은 호출을 실제로 멈출 수 있다
- **결함 하나 — 인터럽트가 재시도 대상으로 오분류된다.** `isRetriable`은 원인 체인의 `IOException`을 재시도 대상으로 본다(연결 실패·읽기 타임아웃용 규칙). Q2 에서 두 번째 요청이 안 나간 것은 **인터럽트 표시가 살아 있어 백오프 sleep 이 바로 깨졌기 때문**이지, 분류가 맞아서가 아니다. 백오프가 0 이거나 sleep 을 거치지 않는 경로가 생기면 취소된 호출이 재시도를 다시 보낸다. 인터럽트는 명시적으로 재시도 대상에서 빼야 한다
- **결말 분류도 필요하다.** 취소된 호출은 지금 `transport_error`로 섞인다. 취소를 따로 세지 않으면 4단계 측정에서 "429·네트워크 실패가 늘었다"와 "마감으로 취소했다"를 가를 수 없다
- 참고 — `aiAgentExecutor`는 `CallerRunsPolicy`라 큐가 차면 작업이 **요청 스레드에서 동기로** 돈다. 그 경우 `get(remainingMs)`에 오기 전에 호출이 이미 끝나 있어 취소 대상이 아니다(마감도 걸리지 않는다). 인터럽트를 보낼 대상이 요청 스레드가 되는 일은 없지만, 이 경로의 마감 부재는 별개 한계로 남는다

### 측정 도구 쪽 발견

Q1 은 첫 실행에서 "슬롯 게이지가 10초 안에 0 이 되지 않았다"로 한 번 실패하고 재현되지 않았다. Micrometer 게이지는 상태 객체(세마포어)를 **약한 참조**로 들고 있어, 테스트 본문이 클라이언트를 더 쓰지 않으면 호출이 끝난 뒤 GC 가 세마포어를 거둬 게이지가 NaN 이 될 수 있다. 테스트가 클라이언트를 필드로 붙잡게 고쳤다. 운영에서는 클라이언트가 싱글턴 빈이라 해당 없다.

## 다음 — 방식 결정

조사 결과로 체크리스트 3·4번의 방식을 정한다(별도 검토 후 구현).
