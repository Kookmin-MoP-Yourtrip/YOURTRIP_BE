package backend.yourtrip.global.health;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * 메인 포트(8080)에서 워커가 마지막으로 요청을 마친 시각.
 *
 * <p>{@link RequestProgressFilter}가 8080의 워커 스레드에서 쓰고, {@link RequestProgressHealthIndicator}가
 * 관리 포트(8081)의 스레드에서 읽는다. 두 포트는 같은 JVM이므로 메모리에서 바로 공유한다 — 이것이 헬스체크가
 * 8080의 줄에 서지 않고도 "8080이 일하고 있는가"를 알 수 있는 이유다.
 *
 * <p><b>벽시계가 아니라 {@link System#nanoTime()}을 쓴다.</b> NTP 보정으로 벽시계가 되돌아가면 "마지막 완료 이후
 * 경과 시간"이 음수가 되거나 갑자기 커져 판정이 흔들린다. nanoTime은 단조 증가가 보장된다.
 *
 * <p>처리 중 요청 수는 세지 않는다. 판정 규칙이 그 값을 쓰지 않기 때문이다(설계 문서 D1) — 쓰지 않는 값을
 * 들고 있으면 언젠가 누군가 판정에 끼워 넣고, 그때 비동기 요청을 언제 "처리 중"으로 볼지 같은 문제가 되살아난다.
 */
@Component
public class ProgressTracker {

    private final LongSupplier nanoClock;
    private final AtomicLong lastCompletedAt;

    public ProgressTracker() {
        this(System::nanoTime);
    }

    /** 테스트가 시간을 조작하기 위한 생성자. */
    ProgressTracker(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        // 기동 시각을 첫 기록으로 둔다. 0으로 두면 기동 직후 첫 헬스체크가 "아주 오래 완료가 없었다"로 읽혀
        // 매번 직접 확인으로 빠진다. 결과가 틀리지는 않지만 의미 없는 확인이다.
        this.lastCompletedAt = new AtomicLong(nanoClock.getAsLong());
    }

    void markCompleted() {
        lastCompletedAt.set(nanoClock.getAsLong());
    }

    /** 마지막 완료 시각의 원시값. 직접 확인 전후로 비교해 "그사이 완료가 있었는가"를 판단하는 데만 쓴다. */
    long lastCompletedAt() {
        return lastCompletedAt.get();
    }

    Duration sinceLastCompletion() {
        return Duration.ofNanos(nanoClock.getAsLong() - lastCompletedAt.get());
    }
}
