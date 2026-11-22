package backend.yourtrip.global.fault;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * 두 락을 서로 반대 순서로 잡는 실제 데드락 (시나리오 S3).
 *
 * <p>{@code side=a}는 A → B, {@code side=b}는 B → A 순서로 잡는다. 두 요청이 첫 락을 잡은 뒤 상대의 락을 기다리면
 * 영원히 풀리지 않는다. 그 뒤에 오는 요청들은 이미 잡힌 첫 락 앞에서 기다리므로, a·b 한 쌍과 나머지 30개로 워커
 * 32개가 모두 묶인다. {@code jstack}은 이 상태를 {@code Found one Java-level deadlock}으로 보고한다.
 *
 * <p><b>{@code synchronized}가 아니라 {@link ReentrantLock#lockInterruptibly()}를 쓴다.</b> 데드락이라는 점은 같지만
 * 인터럽트로 풀 수 있어, 단위 테스트가 데드락을 만들고 확인한 뒤 스레드를 정리할 수 있다. 모니터 락으로 만들면
 * 테스트 JVM에 풀 수 없는 스레드가 남는다. 운영 측정에서는 어느 쪽이든 교체로 끝난다.
 */
@Component
@ConditionalOnFaultInjection
public class LockOrderDeadlock {

    /** 두 요청이 각자 첫 락을 잡을 시간을 준다. 없으면 한쪽이 두 락을 다 잡고 끝나 데드락이 생기지 않을 수 있다. */
    static final Duration HOLD_BEFORE_SECOND = Duration.ofMillis(200);

    public enum Side { A, B }

    private final ReentrantLock lockA = new ReentrantLock();
    private final ReentrantLock lockB = new ReentrantLock();

    public void enter(Side side) throws InterruptedException {
        ReentrantLock first = side == Side.A ? lockA : lockB;
        ReentrantLock second = side == Side.A ? lockB : lockA;

        first.lockInterruptibly();
        try {
            Thread.sleep(HOLD_BEFORE_SECOND);
            second.lockInterruptibly();
            second.unlock();
        } finally {
            first.unlock();
        }
    }
}
