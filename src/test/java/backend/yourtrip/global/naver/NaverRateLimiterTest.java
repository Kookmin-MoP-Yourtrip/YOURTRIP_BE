package backend.yourtrip.global.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link NaverRateLimiter} — 버스트 뒤 고른 간격, 대기 상한 거절 (이슈 #185).
 *
 * <p>시계와 대기를 가짜로 바꿔 <b>실제로 자지 않고</b> 잰다. 대기는 시계를 그만큼 앞으로 민다 —
 * 실제 스레드가 잔 뒤 다시 시계를 읽는 것과 같은 효과다.
 */
@DisplayName("NaverRateLimiter — 서버 단위 호출 속도 제한 (이슈 #185)")
class NaverRateLimiterTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    /** 초당 25건 = 40ms 간격. */
    private static final double RATE = 25;

    private final AtomicLong clock = new AtomicLong(1_000_000 * MS);
    private final List<Long> sleeps = new ArrayList<>();

    private NaverRateLimiter limiter(int burst, long maxWaitMs) {
        return new NaverRateLimiter(RATE, burst, maxWaitMs, clock::get, nanos -> {
            sleeps.add(nanos);
            clock.addAndGet(nanos);
        });
    }

    @Nested
    @DisplayName("버스트와 간격")
    class BurstAndInterval {

        @Test
        @DisplayName("쉬던 뒤에는 burst 건을 기다리지 않고 내보낸다 — 단일 요청의 기본 질의가 늦어지지 않는다")
        void burstPassesImmediately() throws Exception {
            NaverRateLimiter limiter = limiter(15, 2_000);

            for (int i = 0; i < 15; i++) {
                assertThat(limiter.acquire()).as("%d번째", i + 1).isZero();
            }
            assertThat(sleeps).isEmpty();
        }

        @Test
        @DisplayName("버스트를 넘긴 호출은 간격(1/rate)마다 하나씩 나간다")
        void spacesCallsAfterBurst() throws Exception {
            NaverRateLimiter limiter = limiter(15, 2_000);
            for (int i = 0; i < 15; i++) {
                limiter.acquire();
            }

            assertThat(limiter.acquire()).isEqualTo(40 * MS);
            assertThat(limiter.acquire()).isEqualTo(40 * MS);
        }

        @Test
        @DisplayName("한꺼번에 몰린 호출의 대기는 줄 선 순서대로 간격만큼 늘어난다 — 시계가 멈춘 채 예약만 쌓인다")
        void queuedWaitsGrowLinearly() throws Exception {
            // 대기가 시계를 밀지 않게 해 "같은 순간 몰린 호출들"을 흉내 낸다.
            NaverRateLimiter limiter = new NaverRateLimiter(RATE, 1, 2_000, clock::get, nanos -> { });

            List<Long> waits = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                waits.add(limiter.acquire());
            }
            assertThat(waits).containsExactly(0L, 40 * MS, 80 * MS, 120 * MS);
        }

        @Test
        @DisplayName("충분히 쉬면 버스트가 다시 찬다")
        void refillsAfterIdle() throws Exception {
            NaverRateLimiter limiter = limiter(3, 2_000);
            for (int i = 0; i < 3; i++) {
                limiter.acquire();
            }
            clock.addAndGet(1_000 * MS);

            for (int i = 0; i < 3; i++) {
                assertThat(limiter.acquire()).isZero();
            }
        }

        @Test
        @DisplayName("임의의 1초 창에 나가는 호출은 burst + rate 를 넘지 않는다")
        void boundsAnyOneSecondWindow() throws Exception {
            NaverRateLimiter limiter = limiter(15, 60_000);
            long start = clock.get();
            int sent = 0;
            while (true) {
                limiter.acquire();
                if (clock.get() - start >= TimeUnit.SECONDS.toNanos(1)) {
                    break;
                }
                sent++;
            }
            // 버스트 15 + 1초 동안 25건 (경계의 1건은 1초 시점에 나가므로 창 밖이다)
            assertThat(sent).isEqualTo(15 + 25 - 1);
        }
    }

    @Nested
    @DisplayName("대기 상한")
    class MaxWait {

        @Test
        @DisplayName("상한보다 오래 기다려야 하면 -1 로 거절하고 자지 않는다")
        void rejectsBeyondMaxWait() throws Exception {
            NaverRateLimiter limiter = new NaverRateLimiter(RATE, 1, 100, clock::get, nanos -> { });

            assertThat(limiter.acquire()).isZero();          // 0ms
            assertThat(limiter.acquire()).isEqualTo(40 * MS); // 40ms
            assertThat(limiter.acquire()).isEqualTo(80 * MS); // 80ms
            assertThat(limiter.acquire()).as("120ms > 상한 100ms").isEqualTo(-1);
        }

        @Test
        @DisplayName("거절은 차례를 예약하지 않는다 — 포기한 호출이 뒤 호출의 대기를 늘리면 안 된다")
        void rejectionDoesNotReserve() throws Exception {
            NaverRateLimiter limiter = new NaverRateLimiter(RATE, 1, 100, clock::get, nanos -> { });
            for (int i = 0; i < 3; i++) {
                limiter.acquire();
            }
            assertThat(limiter.acquire()).isEqualTo(-1);
            assertThat(limiter.acquire()).isEqualTo(-1);

            clock.addAndGet(40 * MS);
            assertThat(limiter.acquire())
                .as("거절이 예약했다면 여기서도 상한을 넘었을 것이다")
                .isEqualTo(80 * MS);
        }
    }

    @Nested
    @DisplayName("동시성과 경계")
    class Concurrency {

        @Test
        @DisplayName("여러 스레드가 동시에 와도 같은 차례를 두 번 주지 않는다")
        void reservesDistinctSlotsAcrossThreads() throws Exception {
            NaverRateLimiter limiter = new NaverRateLimiter(RATE, 1, 60_000, clock::get, nanos -> { });
            int threads = 16;
            List<Long> waits = java.util.Collections.synchronizedList(new ArrayList<>());
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                workers.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        waits.add(limiter.acquire());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Thread worker : workers) {
                worker.join();
            }

            // 시계가 멈춰 있으므로 대기는 0, 40, 80 … 이 정확히 한 번씩 나와야 한다.
            List<Long> expected = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                expected.add(i * 40 * MS);
            }
            assertThat(waits).containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("제한 없는 인스턴스는 기다리지 않는다 — 스텁 테스트·프로브용")
        void unlimitedNeverWaits() throws Exception {
            NaverRateLimiter unlimited = NaverRateLimiter.unlimited();
            for (int i = 0; i < 1_000; i++) {
                assertThat(unlimited.acquire()).isZero();
            }
        }

        @Test
        @DisplayName("잘못된 설정은 기동 시점에 거부한다")
        void rejectsInvalidSettings() {
            assertThatThrownBy(() -> new NaverRateLimiter(0, 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NaverRateLimiter(25, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
