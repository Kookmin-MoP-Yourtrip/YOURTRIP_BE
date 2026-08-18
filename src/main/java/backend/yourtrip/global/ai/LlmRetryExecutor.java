package backend.yourtrip.global.ai;

import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.config.AiLlmProperties.Retry;
import backend.yourtrip.global.ai.exception.LlmTransportException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 전송 계층 재시도 — 429/5xx를 지수 백오프 + 지터로 다시 시도한다.
 *
 * <p>재시도 2계층 중 <b>아래쪽 절반</b>이다(LLM 포트 설계). 위쪽인 의미 계층(200 OK인데 깨진 응답)은
 * 프롬프트를 재조립해야 해서 어댑터가 맡는다.
 *
 * <p><b>어댑터에서 떼어낸 이유는 테스트다.</b> 백오프 간격과 지터 폭이 맞는지를 어댑터 안에 두면
 * WireMock 응답 타이밍으로 간접 확인할 수밖에 없는데, 그건 느리고 불안정한 테스트가 된다.
 * 여기서는 {@link #backoffMillis}가 순수 함수라 값으로 직접 검증되고, 재시도 루프는
 * {@link Sleeper}를 갈아끼워 실제로 자지 않고 확인한다.
 *
 * <p>벤더 SDK 타입이 등장하지 않는다 — "무엇이 재시도 대상인가"는 {@code retriable} 술어로
 * 호출자(어댑터)가 결정하고, 이 클래스는 "얼마나 기다렸다 몇 번 더"만 안다.
 */
@Component
public class LlmRetryExecutor {

    /** 테스트에서 실제 대기를 건너뛰기 위한 이음매. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final AiLlmProperties properties;
    private final Sleeper sleeper;

    /**
     * 생성자가 둘이라 어느 쪽으로 주입할지 명시해야 한다. 표시하지 않으면 Spring이 기본 생성자를
     * 찾다가 {@code NoSuchMethodException}으로 컨텍스트 자체가 깨진다.
     */
    @Autowired
    public LlmRetryExecutor(AiLlmProperties properties) {
        this(properties, Thread::sleep);
    }

    public LlmRetryExecutor(AiLlmProperties properties, Sleeper sleeper) {
        this.properties = properties;
        this.sleeper = sleeper;
    }

    /**
     * {@code action}을 최대 {@code llm.retry.attempts}회 시도한다.
     *
     * <p>재시도 대상이 아닌 예외는 <b>그대로 통과시킨다.</b> 예를 들어 스키마가 잘못돼 400이 오는
     * 것은 재시도해도 같은 결과이고, 백오프를 태우면 오류를 늦게 볼 뿐이다.
     *
     * @param retriable 다시 시도할 가치가 있는 예외인지 판정. 벤더별 예외 모양을 아는 것은
     *                  어댑터뿐이므로 판정을 주입받는다
     * @throws LlmTransportException 재시도를 전부 소진했을 때
     */
    public <T> T execute(String agentName, Supplier<T> action, Predicate<RuntimeException> retriable) {
        Retry retry = properties.retry();
        RuntimeException last = null;

        for (int attempt = 1; attempt <= retry.attempts(); attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!retriable.test(e)) {
                    throw e;
                }
                last = e;
                if (attempt < retry.attempts()) {
                    sleepQuietly(backoffMillis(retry, attempt, ThreadLocalRandom.current().nextDouble()));
                }
            }
        }

        throw new LlmTransportException(agentName, retry.attempts(),
            "LLM 전송 재시도 %d회를 모두 소진했다".formatted(retry.attempts()), last);
    }

    /**
     * {@code attempt}번째 시도가 실패한 뒤 기다릴 시간.
     *
     * <p>{@code initial × 2^(attempt-1)}을 {@code max}로 자른 뒤 지터를 적용한다. 지터가 필요한
     * 이유는 <b>여러 요청이 rate limit에 동시에 걸렸을 때</b>다 — 같은 백오프를 쓰면 재시도도
     * 동시에 몰려 429를 다시 맞고, 그게 반복되면 재시도가 문제를 해결하는 게 아니라 키운다.
     *
     * @param jitterRoll {@code [0,1)} 난수. 순수 함수로 유지해 테스트가 값을 직접 넣을 수 있게 한다
     */
    static long backoffMillis(Retry retry, int attempt, double jitterRoll) {
        double delaySeconds = retry.initialDelaySeconds() * Math.pow(2, attempt - 1);
        delaySeconds = Math.min(delaySeconds, retry.maxDelaySeconds());

        // jitter 0.3 이면 계산값의 70% ~ 130% 사이로 흩는다.
        double factor = 1.0 + retry.jitter() * (2 * jitterRoll - 1);
        return Math.max(0L, Math.round(delaySeconds * factor * 1000));
    }

    private void sleepQuietly(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 재시도 대기 중 인터럽트됐다", e);
        }
    }
}
