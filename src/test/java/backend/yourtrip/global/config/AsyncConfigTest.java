package backend.yourtrip.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@DisplayName("AsyncConfig")
class AsyncConfigTest {

    @Nested
    @DisplayName("aiAgentExecutor — 줄이 풀이 아니라 LLM 슬롯 앞에 서야 한다 (#177)")
    class AgentExecutor {

        @Test
        @DisplayName("스레드 수는 슬롯 수를 따라가고, core 와 max 가 같다")
        void threadsFollowPermits() {
            ThreadPoolTaskExecutor executor = AsyncConfig.agentExecutorFor(4);
            try {
                assertThat(executor.getCorePoolSize()).isEqualTo(4 * AsyncConfig.THREADS_PER_PERMIT);
                assertThat(executor.getMaxPoolSize()).isEqualTo(executor.getCorePoolSize());
            } finally {
                executor.shutdown();
            }
        }

        /**
         * 슬롯보다 많은 작업이 몰려도 전부 스레드를 얻어야, 기다림이 세마포어(대기 상한·지표 있음) 앞으로
         * 간다. #177 이전 설정은 작업 5개째부터 큐에 쌓였다(STEP-4 실측: 동시 5명에서 큐 최대 11).
         */
        @Test
        @DisplayName("슬롯 × 4 개의 작업이 동시에 와도 큐에 쌓이지 않고 모두 스레드를 얻는다")
        void absorbsBurstWithoutQueueing() throws Exception {
            int permits = 4;
            int tasks = permits * AsyncConfig.THREADS_PER_PERMIT;
            ThreadPoolTaskExecutor executor = AsyncConfig.agentExecutorFor(permits);
            CountDownLatch started = new CountDownLatch(tasks);
            CountDownLatch release = new CountDownLatch(1);
            try {
                for (int i = 0; i < tasks; i++) {
                    executor.execute(() -> {
                        started.countDown();
                        await(release);
                    });
                }

                assertThat(started.await(5, TimeUnit.SECONDS)).as("모든 작업이 실행을 시작했다").isTrue();
                ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
                assertThat(pool.getActiveCount()).isEqualTo(tasks);
                assertThat(pool.getQueue()).isEmpty();
            } finally {
                release.countDown();
                executor.shutdown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
