package backend.yourtrip.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * <b>학습 테스트</b> — LLM 호출이 <b>어디서 줄을 서는가</b>의 사실 확인 (이슈 #177 조사).
 *
 * <p>#108 측정에서 세마포어를 4로 올리자 슬롯 대기는 0인데 {@code aiAgentExecutor} 큐가 11까지
 * 쌓였다. 풀 크기를 고르기 전에 {@link ThreadPoolExecutor}가 실제로 언제 스레드를 늘리고, 언제 큐에
 * 넣고, 언제 {@code CallerRunsPolicy}로 넘기는지를 운영 풀 설정 그대로 확인한다.
 *
 * <p>작업은 {@code OpenAiLlmClient.generate}의 슬롯 처리만 흉내 낸다 — {@code tryAcquire(대기 상한)}
 * → 호출 시간만큼 점유 → {@code finally}에서 반납. 질문이 "어디서 기다리는가"라 HTTP 는 필요 없다.
 * 상황은 STEP-4 의 동시 5명(3일 코스 → Curator 15개, 슬롯 4)이고, 시간만 줄였다
 * (호출 {@link #HOLD_MS}, 대기 상한 {@link #PERMIT_TIMEOUT_MS}).
 */
@DisplayName("aiAgentExecutor 크기 학습 테스트 (이슈 #177 조사)")
class AgentExecutorSizingLearningTest {

    private static final int TASKS = 15;
    private static final int PERMITS = 4;
    private static final long HOLD_MS = 200;
    /** 운영의 {@code llm.timeout-ms}에 해당한다. 셋째 줄(대기 400ms)부터는 넘는다. */
    private static final long PERMIT_TIMEOUT_MS = 300;
    /** 제출 직후 줄이 자리 잡을 때까지 기다리는 시간. 첫 반납(200ms)보다 충분히 짧다. */
    private static final long SETTLE_MS = 50;

    private final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executors.forEach(ThreadPoolTaskExecutor::shutdown);
    }

    @Test
    @DisplayName("Q1. 운영 설정(core 4 / max 8 / 큐 50) — 스레드는 4개에서 늘지 않고 줄은 큐에 선다")
    void productionConfigQueuesInExecutor() throws Exception {
        ThreadPoolTaskExecutor executor = track(new AsyncConfig().aiAgentExecutor());

        Run run = submitAll(executor);

        assertThat(run.poolSize).as("max 8 은 쓰이지 않는다").isEqualTo(4);
        assertThat(run.executorQueue).as("나머지 11개는 스레드를 기다린다").isEqualTo(TASKS - 4);
        assertThat(run.permitWaiters).as("세마포어 앞에는 아무도 없다").isZero();
        assertThat(run.timeouts.get()).as("큐 대기에는 상한이 없다 — 아무도 포기하지 않는다").isZero();
        assertThat(run.elapsedMs).as("대신 마지막 작업은 대기 상한을 훌쩍 넘겨 끝난다")
            .isGreaterThanOrEqualTo(4 * HOLD_MS);
    }

    @Test
    @DisplayName("Q2. 스레드를 동시 작업 수 이상으로 두면 — 줄이 세마포어 앞으로 옮겨가고 대기 상한이 먹는다")
    void enoughThreadsMoveWaitToSemaphore() throws Exception {
        ThreadPoolTaskExecutor executor = track(executor(16, 16, 50));

        Run run = submitAll(executor);

        assertThat(run.poolSize).isEqualTo(TASKS);
        assertThat(run.executorQueue).isZero();
        assertThat(run.permitWaiters).as("줄이 세마포어 앞에 선다 — waiting 게이지에 잡힌다")
            .isEqualTo(TASKS - PERMITS);
        assertThat(run.timeouts.get()).as("상한을 넘긴 작업은 포기한다").isPositive();
    }

    @Test
    @DisplayName("Q3. core 를 그대로 두고 max 만 키우면 — 큐가 차기 전에는 아무 효과가 없다")
    void raisingMaxAloneDoesNothing() throws Exception {
        ThreadPoolTaskExecutor executor = track(executor(4, 64, 50));

        Run run = submitAll(executor);

        assertThat(run.poolSize).isEqualTo(4);
        assertThat(run.executorQueue).isEqualTo(TASKS - 4);
    }

    @Test
    @DisplayName("Q4. CallerRunsPolicy 는 스레드와 큐가 모두 찼을 때만 — 그 작업은 제출한 스레드에서 끝까지 돈다")
    void callerRunsOnlyWhenThreadsAndQueueAreFull() throws Exception {
        ThreadPoolTaskExecutor executor = track(executor(2, 2, 1));
        Semaphore gate = new Semaphore(PERMITS);
        List<String> threadNames = new ArrayList<>();
        String caller = Thread.currentThread().getName();

        long start = System.nanoTime();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                hold(gate, new AtomicInteger());
                return Thread.currentThread().getName();
            }, executor));
        }
        long submitMs = elapsedMs(start);
        for (CompletableFuture<String> future : futures) {
            threadNames.add(future.get(5, TimeUnit.SECONDS));
        }

        assertThat(threadNames).as("스레드 2 + 큐 1 을 넘친 네 번째만 제출 스레드에서 돈다")
            .filteredOn(caller::equals).hasSize(1);
        assertThat(submitMs).as("그동안 제출이 막힌다 — 비동기로 던졌는데 동기로 기다린 셈이다")
            .isGreaterThanOrEqualTo(HOLD_MS);
    }

    // ── 도구 ────────────────────────────────────────────────────────────────

    /** 한 번의 실행에서 본 줄의 위치와 결말. */
    private record Run(int poolSize, int executorQueue, int permitWaiters, AtomicInteger timeouts,
                       long elapsedMs) {}

    private Run submitAll(ThreadPoolTaskExecutor executor) throws Exception {
        Semaphore gate = new Semaphore(PERMITS);
        AtomicInteger timeouts = new AtomicInteger();

        long start = System.nanoTime();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < TASKS; i++) {
            futures.add(CompletableFuture.runAsync(() -> hold(gate, timeouts), executor));
        }
        Thread.sleep(SETTLE_MS);
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        int poolSize = pool.getPoolSize();
        int queued = pool.getQueue().size();
        int waiters = gate.getQueueLength();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        return new Run(poolSize, queued, waiters, timeouts, elapsedMs(start));
    }

    /** {@code OpenAiLlmClient.generate}의 슬롯 처리와 같은 순서. */
    private static void hold(Semaphore gate, AtomicInteger timeouts) {
        try {
            if (!gate.tryAcquire(PERMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                timeouts.incrementAndGet();
                return;
            }
            try {
                Thread.sleep(HOLD_MS);
            } finally {
                gate.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadPoolTaskExecutor executor(int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("learning-");
        executor.initialize();
        return executor;
    }

    private ThreadPoolTaskExecutor track(ThreadPoolTaskExecutor executor) {
        executors.add(executor);
        return executor;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
