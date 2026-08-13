package backend.yourtrip.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.config.AiLlmProperties.Agent;
import backend.yourtrip.global.ai.config.AiLlmProperties.OpenAi;
import backend.yourtrip.global.ai.config.AiLlmProperties.Retry;
import backend.yourtrip.global.ai.exception.LlmTransportException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code LlmRetryExecutor} 단위 테스트 (ROADMAP 2-4, 전송 계층).
 *
 * <p>실제로 자지 않는다 — {@code Sleeper}를 갈아끼워 대기 시간을 <b>기록만</b> 하고 넘어가므로,
 * 백오프가 4초까지 늘어나는 시나리오도 밀리초 안에 끝난다.
 */
@DisplayName("LlmRetryExecutor — 전송 계층 재시도 (ROADMAP 2-4)")
class LlmRetryExecutorTest {

    private static final String AGENT = "curator";

    /** 재시도 대상으로 취급할 가짜 전송 오류. 실제 벤더 예외 타입은 어댑터가 판정한다. */
    private static class FakeRateLimitException extends RuntimeException {
        FakeRateLimitException() {
            super("429 Too Many Requests");
        }
    }

    private final List<Long> sleeps = new ArrayList<>();

    private LlmRetryExecutor executorWith(Retry retry) {
        AiLlmProperties properties = new AiLlmProperties(
            "openai", 20_000, 2, retry,
            Map.of("curator", new Agent("gpt-5.6-luna", 0.9, 4096)),
            new OpenAi("", "https://api.openai.com"));
        return new LlmRetryExecutor(properties, sleeps::add);
    }

    @Nested
    @DisplayName("재시도 루프")
    class RetryLoop {

        @Test
        @DisplayName("두 번 실패 후 성공하면 결과를 돌려주고 재시도는 2회만 대기한다")
        void succeedsAfterTransientFailures() {
            LlmRetryExecutor executor = executorWith(new Retry(3, 2, 0.5, 4.0, 0.0));
            AtomicInteger calls = new AtomicInteger();

            String result = executor.execute(AGENT, () -> {
                if (calls.incrementAndGet() < 3) {
                    throw new FakeRateLimitException();
                }
                return "ok";
            }, e -> e instanceof FakeRateLimitException);

            assertThat(result).isEqualTo("ok");
            assertThat(calls).hasValue(3);
            assertThat(sleeps).as("마지막 시도 뒤에는 기다리지 않는다").hasSize(2);
        }

        @Test
        @DisplayName("계속 실패하면 시도 횟수를 담은 LlmTransportException 이 나온다")
        void exhaustsAttempts() {
            LlmRetryExecutor executor = executorWith(new Retry(3, 2, 0.5, 4.0, 0.0));
            AtomicInteger calls = new AtomicInteger();

            assertThatThrownBy(() -> executor.execute(AGENT, () -> {
                calls.incrementAndGet();
                throw new FakeRateLimitException();
            }, e -> e instanceof FakeRateLimitException))
                .isInstanceOf(LlmTransportException.class)
                .satisfies(thrown -> {
                    LlmTransportException e = (LlmTransportException) thrown;
                    // max-concurrent-calls 초기값이 적절한지 판단할 유일한 신호다.
                    assertThat(e.getAttempts()).isEqualTo(3);
                    assertThat(e.getAgentName()).isEqualTo(AGENT);
                    assertThat(e.getCause()).isInstanceOf(FakeRateLimitException.class);
                });
            assertThat(calls).hasValue(3);
        }

        @Test
        @DisplayName("재시도 대상이 아닌 예외는 즉시 통과시킨다 — 백오프를 태우지 않는다")
        void doesNotRetryNonRetriable() {
            LlmRetryExecutor executor = executorWith(new Retry(3, 2, 0.5, 4.0, 0.0));
            AtomicInteger calls = new AtomicInteger();

            // 스키마가 잘못돼 400이 오는 상황: 다시 보내도 같은 결과다.
            assertThatThrownBy(() -> executor.execute(AGENT, () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("400 invalid schema");
            }, e -> e instanceof FakeRateLimitException))
                .isInstanceOf(IllegalStateException.class);

            assertThat(calls).as("재시도하지 않아야 한다").hasValue(1);
            assertThat(sleeps).isEmpty();
        }
    }

    @Nested
    @DisplayName("백오프 계산 (순수 함수)")
    class Backoff {

        private final Retry retry = new Retry(5, 2, 0.5, 4.0, 0.0);

        @Test
        @DisplayName("지터가 0이면 initial 에서 2배씩 늘어난다")
        void doublesEachAttempt() {
            assertThat(LlmRetryExecutor.backoffMillis(retry, 1, 0.5)).isEqualTo(500);
            assertThat(LlmRetryExecutor.backoffMillis(retry, 2, 0.5)).isEqualTo(1_000);
            assertThat(LlmRetryExecutor.backoffMillis(retry, 3, 0.5)).isEqualTo(2_000);
        }

        @Test
        @DisplayName("max-delay-seconds 에서 잘린다")
        void capsAtMaxDelay() {
            assertThat(LlmRetryExecutor.backoffMillis(retry, 4, 0.5)).isEqualTo(4_000);
            assertThat(LlmRetryExecutor.backoffMillis(retry, 9, 0.5))
                .as("지수가 아무리 커져도 상한을 넘지 않는다")
                .isEqualTo(4_000);
        }

        @Test
        @DisplayName("지터 0.3 이면 계산값의 70%~130% 사이로 흩어진다")
        void appliesJitterBand() {
            Retry jittered = new Retry(5, 2, 1.0, 10.0, 0.3);

            assertThat(LlmRetryExecutor.backoffMillis(jittered, 1, 0.0)).isEqualTo(700);
            assertThat(LlmRetryExecutor.backoffMillis(jittered, 1, 0.5)).isEqualTo(1_000);
            // nextDouble()은 1.0을 포함하지 않으므로 상단은 열린 구간이다.
            assertThat(LlmRetryExecutor.backoffMillis(jittered, 1, 0.999)).isLessThan(1_300);
        }
    }
}
