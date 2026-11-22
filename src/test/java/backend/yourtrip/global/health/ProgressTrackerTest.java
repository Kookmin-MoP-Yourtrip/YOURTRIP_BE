package backend.yourtrip.global.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProgressTrackerTest {

    private final AtomicLong now = new AtomicLong(0);
    private final ProgressTracker tracker = new ProgressTracker(now::get);

    @Test
    @DisplayName("늦게 도착한 옛 완료 시각은 더 최신 기록을 덮어쓰지 않는다")
    void olderTimestampDoesNotOverwriteNewer() {
        now.set(Duration.ofSeconds(12).toNanos());
        tracker.markCompleted();

        // 시각 1을 읽어 둔 워커가 뒤늦게 저장하는 상황
        now.set(Duration.ofSeconds(1).toNanos());
        tracker.markCompleted();

        now.set(Duration.ofSeconds(14).toNanos());
        assertThat(tracker.sinceLastCompletion()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("nanoTime 이 음수에서 양수로 넘어가도 순서를 차이의 부호로 비교한다")
    void comparesByDifferenceAcrossSignChange() {
        now.set(-Duration.ofSeconds(1).toNanos());
        tracker.markCompleted();

        now.set(Duration.ofSeconds(1).toNanos());
        tracker.markCompleted();

        assertThat(tracker.lastCompletedAt()).isEqualTo(Duration.ofSeconds(1).toNanos());
    }

    @Test
    @DisplayName("주어진 기준값부터의 경과를 잰다")
    void sinceMeasuresFromGivenReference() {
        long reference = tracker.lastCompletedAt();
        now.set(Duration.ofSeconds(5).toNanos());
        tracker.markCompleted();

        assertThat(tracker.since(reference)).isEqualTo(Duration.ofSeconds(5));
        assertThat(tracker.sinceLastCompletion()).isZero();
    }
}
