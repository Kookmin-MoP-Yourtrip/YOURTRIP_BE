package backend.yourtrip.global.fault;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 해제되지 않는 메모리를 천천히 쌓는 누수 (시나리오 S6).
 *
 * <p>한 번에 큰 배열을 할당해 즉시 OOM을 내지 않고 <b>초당 일정량씩</b> 붙잡는다. 실제 누수(해제되지 않는 캐시·컬렉션)는
 * 서서히 쌓이고, 그동안 GC 빈도가 올라가는 과정이 지표에 남아야 "누수 → GC 압박 → OOM → 종료 → 재시작"의 흐름을
 * 한 그래프로 보일 수 있다. 즉시 OOM은 그 과정 없이 마지막 장면만 만든다.
 *
 * <p>{@link #stop()}은 통합 테스트가 누수를 멈추고 붙잡은 메모리를 놓기 위한 것이다. 측정에서는 OOM까지 간다.
 */
@Slf4j
@Component
@ConditionalOnFaultInjection
public class MemoryLeak {

    private static final int CHUNK_BYTES = 1024 * 1024;

    private final List<byte[]> retained = new ArrayList<>();
    private volatile Thread worker;

    /** 이미 돌고 있으면 아무것도 하지 않고 false를 돌려준다. */
    public synchronized boolean start(int megabytesPerSecond) {
        if (megabytesPerSecond <= 0) {
            throw new IllegalArgumentException("megabytesPerSecond는 양수여야 한다: " + megabytesPerSecond);
        }
        if (worker != null) {
            return false;
        }
        long intervalMillis = Math.max(1, 1000L / megabytesPerSecond);
        Thread thread = new Thread(() -> leak(intervalMillis), "fault-memory-leak");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        log.warn("결함 주입: 초당 {}MB씩 메모리를 붙잡기 시작한다", megabytesPerSecond);
        return true;
    }

    private void leak(long intervalMillis) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (this) {
                    retained.add(new byte[CHUNK_BYTES]);
                }
                Thread.sleep(intervalMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void stop() {
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        retained.clear();
    }

    public synchronized int retainedMegabytes() {
        return retained.size();
    }
}
