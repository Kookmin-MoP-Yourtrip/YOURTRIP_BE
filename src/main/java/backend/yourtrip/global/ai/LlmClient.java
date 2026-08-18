package backend.yourtrip.global.ai;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * LLM 호출의 벤더 중립 포트.
 *
 * <p><b>이 인터페이스와 {@link LlmCall}에는 벤더 SDK 타입이 한 개도 등장하지 않는다.</b> 그것이
 * 이 포트의 존재 이유다. 벤더는 OpenAI로 확정됐으므로 "향후 교체 대비"는 이미 소진된 근거이고,
 * 남은 근거는 <b>테스트 가능성</b> 하나다 — 기존 {@code com.google.genai.Client}는
 * {@code public final class}라 Mockito로 묶을 수 없어 LLM 호출부의 단위 테스트가 원천적으로
 * 불가능했다. 3~7단계에서 만들 에이전트들이 이 포트에만 의존하면 그 제약을 물려받지 않는다.
 *
 * <p>구현체는 {@code backend.yourtrip.global.ai.openai.OpenAiLlmClient} 하나이며, Spring AI는
 * 그 어댑터 안에만 갇힌다.
 *
 * <p>설계 근거: {@code docs/tasks/ai-course-create/design/LLM-연동.md} "벤더 중립 LLM 추상화"
 *
 * <h2>던지는 예외</h2>
 * 전부 비검사 예외이며, {@code backend.yourtrip.global.ai.exception} 패키지의 계층이
 * <b>재시도 2계층 구조를 타입으로 표현</b>한다.
 * <ul>
 *   <li>{@code LlmTransportException} — 429/5xx/타임아웃. 어댑터가 지수 백오프로 재시도한 뒤
 *       그래도 실패했을 때 나온다(= 호출자가 다시 시도해봐야 소용없다)</li>
 *   <li>{@code LlmResponseException} — 200 OK인데 쓸 수 없는 응답(절단/파싱 실패). 어댑터가
 *       보정 지시를 붙여 1회 재시도한 뒤에도 실패했을 때 나온다</li>
 * </ul>
 */
public interface LlmClient {

    /**
     * LLM을 동기 호출하고 응답을 {@code call.responseType()}으로 역직렬화해 돌려준다.
     *
     * <p>전송 재시도와 의미 재시도는 구현체가 내부적으로 수행한다 — 이 메서드가 예외를 던졌다면
     * 재시도가 이미 소진된 상태다.
     */
    <T> T generate(LlmCall<T> call);

    /**
     * {@link #generate(LlmCall)}를 주어진 executor에서 수행한다.
     *
     * <p>executor를 인자로 받는 이유는 <b>벌크헤드 분리를 호출자가 결정하게 하기 위해서</b>다.
     * LLM은 3~10초짜리 소수 호출이고 장소 API는 0.15~0.3초짜리 다수 호출이라 최적 풀 크기가
     * 다르며, 어느 풀에서 돌릴지는 파이프라인의 판단이지 어댑터의 판단이 아니다.
     * 실제 {@code aiAgentExecutor} 빈은 5단계에서 생긴다.
     */
    <T> CompletableFuture<T> generateAsync(LlmCall<T> call, Executor executor);
}
