package backend.yourtrip.global.fault;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LockOrderDeadlockTest {

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

    @Test
    @DisplayName("a·b 한 쌍이 JVM이 데드락으로 인식하는 상태를 만들고, 인터럽트로 풀린다")
    void createsRealDeadlock() throws Exception {
        LockOrderDeadlock deadlock = new LockOrderDeadlock();
        Thread a = start(deadlock, LockOrderDeadlock.Side.A);
        Thread b = start(deadlock, LockOrderDeadlock.Side.B);

        // jstack 의 "Found one Java-level deadlock"과 같은 검출이다. ReentrantLock 데드락도 잡는다.
        assertThat(awaitDeadlocked(a, b)).as("두 스레드가 데드락으로 검출되지 않았다").isTrue();

        a.interrupt();
        b.interrupt();
        a.join(2_000);
        b.join(2_000);
        assertThat(a.isAlive()).isFalse();
        assertThat(b.isAlive()).isFalse();
    }

    private static Thread start(LockOrderDeadlock deadlock, LockOrderDeadlock.Side side) {
        Thread thread = new Thread(() -> {
            try {
                deadlock.enter(side);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "deadlock-" + side);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private boolean awaitDeadlocked(Thread a, Thread b) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            long[] ids = threads.findDeadlockedThreads();
            if (ids != null) {
                Set<Long> found = Arrays.stream(ids).boxed().collect(Collectors.toSet());
                if (found.contains(a.threadId()) && found.contains(b.threadId())) {
                    return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }
}
