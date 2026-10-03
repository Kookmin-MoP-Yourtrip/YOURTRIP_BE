package backend.yourtrip.global.ai.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.LlmCall;
import backend.yourtrip.global.ai.LlmResponseParser;
import backend.yourtrip.global.ai.LlmRetryExecutor;
import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.config.AiLlmProperties.Agent;
import backend.yourtrip.global.ai.config.AiLlmProperties.OpenAi;
import backend.yourtrip.global.ai.config.AiLlmProperties.Retry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>학습 테스트</b> — 마감 뒤 남는 LLM 호출을 "어떻게 멈출 수 있는가"의 사실 확인 (이슈 #176 조사).
 *
 * <p>Planner·Curator는 {@code future.get(remainingMs)}로 기다리다 마감되면 그냥 돌아선다. 남은
 * 호출을 멈추려면 취소 수단이 실제로 무엇을 끊는지 알아야 방식을 고를 수 있다. 라이브러리 문서의
 * 문장이 아니라 <b>이 저장소의 실제 배선</b>(reactor-netty 요청 팩토리 + 세마포어 + 재시도)으로
 * 확인한다 — 이 어댑터는 이미 "문서상 기본값과 실제 HTTP 스택이 달랐던" 이력이 있다
 * ({@link OpenAiLlmClient#buildChatModel} 주석).
 *
 * <p>모든 시나리오에서 WireMock 은 응답을 {@link #SLOW_MS} 늦게 준다. 마감을 흉내 내는 취소·인터럽트는
 * {@link #CANCEL_AFTER_MS}에 건다. <b>슬롯({@code in_use})이 언제 비는가</b>가 판정 기준이다 — 남은
 * 호출이 해로운 이유가 슬롯 점유이기 때문이다.
 */
@DisplayName("LLM 호출 취소 학습 테스트 (이슈 #176 조사)")
class LlmCallCancellationLearningTest {

    private static final String PATH = "/v1/chat/completions";
    private static final String AGENT = "curator";
    private static final long SLOW_MS = 3_000;
    private static final long CANCEL_AFTER_MS = 300;
    /** 취소가 "즉시" 먹혔다고 볼 상한. 늦은 응답(3초)과 확실히 갈린다. */
    private static final long PROMPT_MS = 1_000;

    record Plan(String title) {}

    private WireMockServer wireMock;
    private SimpleMeterRegistry registry;

    /**
     * 마지막으로 만든 클라이언트를 붙잡아 둔다. Micrometer 게이지는 상태 객체(세마포어)를 <b>약한
     * 참조</b>로 들고 있어, 테스트 본문이 클라이언트를 더 쓰지 않으면 호출이 끝난 뒤 GC 가 세마포어를
     * 거둬 게이지가 NaN 이 된다 — Q1 이 첫 실행에서 "10초 안에 0 이 되지 않았다"로 실패한 원인으로
     * 본다. 운영에서는 클라이언트가 싱글턴 빈이라 해당 없다.
     */
    private OpenAiLlmClient current;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("Q0. 기준 — 취소하지 않으면 늦은 응답 시각에 끝난다")
    void baselineWithoutCancel() {
        stubSlowSuccess();
        OpenAiLlmClient client = client(1, 10_000, new Retry(1, 1, 0.01, 0.02, 0.0));
        long startedAt = System.nanoTime();
        Throwable thrown = null;
        try {
            client.generate(call());
        } catch (Throwable t) {
            thrown = t;
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        System.out.printf("[Q0] %dms, 예외: %s%n", ms, chain(thrown));
    }

    @Test
    @DisplayName("Q1. generateAsync 의 future 를 cancel(true) 해도 호출은 끝까지 돌고 슬롯도 늦은 응답까지 쥔다")
    void cancellingCompletableFutureDoesNotStopTheCall() throws Exception {
        stubSlowSuccess();
        OpenAiLlmClient client = client(1, 10_000, new Retry(1, 1, 0.01, 0.02, 0.0));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Plan> future = client.generateAsync(call(), executor);
            awaitInUse(1);
            Thread.sleep(CANCEL_AFTER_MS);

            long cancelledAt = System.nanoTime();
            assertThat(future.cancel(true)).isTrue();
            long releasedMs = awaitInUse(0) - TimeUnit.NANOSECONDS.toMillis(cancelledAt);

            // CompletableFuture 는 실행 중인 스레드에 인터럽트를 보내지 않는다(javadoc 의
            // "mayInterruptIfRunning 은 이 구현에서 효과가 없다"). 취소는 future 의 표시만 바꾼다.
            assertThat(releasedMs)
                .as("cancel(true) 뒤 슬롯이 빌 때까지 — 늦은 응답(%dms)을 기다려야 비면 취소가 안 먹힌 것", SLOW_MS)
                .isGreaterThan(SLOW_MS - CANCEL_AFTER_MS - 500);
            // 슬롯 반납(finally 첫 줄) 직후에 결말이 기록되므로 잠깐 기다린다.
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (llmCalls(AiCourseMetrics.LLM_OUTCOME_SUCCESS) == 0 && System.nanoTime() < limit) {
                Thread.sleep(10);
            }
            assertThat(llmCalls(AiCourseMetrics.LLM_OUTCOME_SUCCESS))
                .as("버려진 future 뒤에서 호출은 성공으로 끝났다 — STEP-2 의 '폴백인데 실패 0' 과 같은 모양")
                .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Q2. HTTP 응답을 기다리는 스레드를 인터럽트하면 요청이 즉시 끝나고 재시도 없이 슬롯이 빈다")
    void interruptingHttpWaitAbortsTheRequest() throws Exception {
        stubSlowSuccess();
        // 전송 재시도 3회 — 인터럽트가 "재시도 대상"으로 오분류되면 요청이 더 나간다.
        OpenAiLlmClient client = client(1, 10_000, new Retry(3, 1, 0.01, 0.02, 0.0));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                client.generate(call());
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        awaitInUse(1);
        Thread.sleep(CANCEL_AFTER_MS);

        long interruptedAt = System.nanoTime();
        worker.interrupt();
        worker.join(SLOW_MS * 2);
        long endedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - interruptedAt);

        assertThat(endedMs).as("인터럽트 뒤 호출이 끝나기까지").isLessThan(PROMPT_MS);
        assertThat(gauge(AiCourseMetrics.LLM_PERMITS_IN_USE)).isZero();
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
        System.out.printf("[Q2] 인터럽트 → %dms 에 종료, 예외 체인: %s%n", endedMs, chain(thrown.get()));
    }

    @Test
    @DisplayName("Q2-1. 인터럽트된 HTTP 호출은 IOException 으로 번역돼 나오고 인터럽트 표시는 다시 켜진다")
    void interruptedHttpCallSurfacesAsIoException() throws Exception {
        stubSlowSuccess();
        var chatModel = OpenAiLlmClient.buildChatModel(wireMock.baseUrl(), "test-api-key", 10_000);
        var prompt = new org.springframework.ai.chat.prompt.Prompt("user",
            org.springframework.ai.openai.OpenAiChatOptions.builder().model("gpt-test").build());
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                chatModel.call(prompt);
            } catch (Throwable t) {
                thrown.set(t);
                flag.set(Thread.currentThread().isInterrupted());
            }
        });
        awaitRequests(1);
        Thread.sleep(CANCEL_AFTER_MS);
        worker.interrupt();
        worker.join(SLOW_MS * 2);

        System.out.printf("[Q2-1] 예외 체인: %s, 인터럽트 표시: %s%n", chain(thrown.get()), flag.get());
        assertThat(hasCause(thrown.get(), java.io.IOException.class))
            .as("번역 형태를 고정한다 — isRetriable 이 IOException 규칙보다 인터럽트를 먼저 걸러야 하는 이유다")
            .isTrue();
        assertThat(flag.get()).as("인터럽트 표시가 살아 있어야 다음 백오프 sleep 이 즉시 깨진다").isTrue();
    }

    @Test
    @DisplayName("Q3. 슬롯을 기다리는 스레드를 인터럽트하면 대기가 즉시 끝나고 interrupted 로 기록된다")
    void interruptingPermitWaitEndsTheWait() throws Exception {
        stubSlowSuccess();
        OpenAiLlmClient client = client(1, 10_000, new Retry(1, 1, 0.01, 0.02, 0.0));
        Thread holder = Thread.ofPlatform().start(() -> client.generate(call()));
        awaitInUse(1);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                client.generate(call());
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        awaitWaiting(1);
        Thread.sleep(CANCEL_AFTER_MS);

        long interruptedAt = System.nanoTime();
        waiter.interrupt();
        waiter.join(SLOW_MS * 2);
        long endedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - interruptedAt);

        assertThat(endedMs).isLessThan(PROMPT_MS);
        assertThat(registry.find(AiCourseMetrics.LLM_PERMIT_WAIT)
            .tags("agent", AGENT, "result", AiCourseMetrics.PERMIT_INTERRUPTED).timer().count())
            .isEqualTo(1);
        System.out.printf("[Q3] 인터럽트 → %dms 에 종료, 예외 체인: %s%n", endedMs, chain(thrown.get()));
        holder.join();
    }

    @Test
    @DisplayName("Q4. 재시도 백오프 중 인터럽트하면 대기가 즉시 끝나고 transport_error 로 섞여 기록된다")
    void interruptingBackoffEndsTheSleep() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        // 백오프 5초 — 인터럽트가 없으면 두 번째 시도까지 5초를 잔다.
        OpenAiLlmClient client = client(1, 10_000, new Retry(3, 1, 5.0, 5.0, 0.0));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                client.generate(call());
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        awaitRequests(1);
        Thread.sleep(CANCEL_AFTER_MS);

        long interruptedAt = System.nanoTime();
        worker.interrupt();
        worker.join(10_000);
        long endedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - interruptedAt);

        assertThat(endedMs).isLessThan(PROMPT_MS);
        assertThat(gauge(AiCourseMetrics.LLM_PERMITS_IN_USE)).isZero();
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
        System.out.printf("[Q4] 인터럽트 → %dms 에 종료, 예외 체인: %s%n", endedMs, chain(thrown.get()));
    }

    // ── 배선·도우미 ───────────────────────────────────────────────────────────

    private OpenAiLlmClient client(int maxConcurrentCalls, int timeoutMs, Retry retry) {
        AiLlmProperties properties = new AiLlmProperties("openai", timeoutMs, maxConcurrentCalls, retry,
            Map.of(AGENT, new Agent("gpt-test", null, 256, null)),
            new OpenAi("test-api-key", wireMock.baseUrl()));
        registry = new SimpleMeterRegistry();
        current = new OpenAiLlmClient(properties, new LlmResponseParser(new ObjectMapper()),
            new LlmRetryExecutor(properties), new AiCourseMetrics(registry),
            OpenAiLlmClient.buildChatModel(wireMock.baseUrl(), "test-api-key", timeoutMs));
        return current;
    }

    private static LlmCall<Plan> call() {
        return new LlmCall<>(AGENT, "system", "user", Plan.class, null);
    }

    private void stubSlowSuccess() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withFixedDelay((int) SLOW_MS)
            .withBody("""
                {"id":"x","object":"chat.completion","created":0,"model":"gpt-test",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"{\\"title\\":\\"t\\"}"},
                 "finish_reason":"stop"}]}""")));
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private long llmCalls(String outcome) {
        var timer = registry.find(AiCourseMetrics.LLM_CALL).tags("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    /** 슬롯 점유가 {@code expected}가 될 때까지 기다리고, 그 시각(ms, nanoTime 기준)을 돌려준다. */
    private long awaitInUse(int expected) {
        return awaitGauge(AiCourseMetrics.LLM_PERMITS_IN_USE, expected);
    }

    private void awaitWaiting(int expected) {
        awaitGauge(AiCourseMetrics.LLM_PERMITS_WAITING, expected);
    }

    private long awaitGauge(String name, int expected) {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (gauge(name) != expected) {
            if (System.nanoTime() > limit) {
                throw new AssertionError(name + " 이 10초 안에 " + expected + " 이 되지 않았다 (현재 "
                    + gauge(name) + ", 성공 기록 " + llmCalls(AiCourseMetrics.LLM_OUTCOME_SUCCESS) + ")");
            }
            Thread.onSpinWait();
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private void awaitRequests(int expected) throws InterruptedException {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (wireMock.getAllServeEvents().size() < expected) {
            if (System.nanoTime() > limit) {
                throw new AssertionError("요청이 10초 안에 " + expected + "건 들어오지 않았다");
            }
            Thread.sleep(10);
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            sb.append(sb.isEmpty() ? "" : " ← ").append(c.getClass().getSimpleName());
        }
        return sb.isEmpty() ? "(예외 없음)" : sb.toString();
    }
}
