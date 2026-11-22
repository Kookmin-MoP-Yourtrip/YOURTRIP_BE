package backend.yourtrip.global.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemoryLeakTest {

    private final MemoryLeak leak = new MemoryLeak();

    @AfterEach
    void stop() {
        leak.stop();
    }

    @Test
    @DisplayName("시작하면 메모리를 붙잡아 쌓고, 멈추면 놓는다")
    void retainsAndReleases() throws Exception {
        assertThat(leak.start(50)).isTrue();

        for (int i = 0; i < 50 && leak.retainedMegabytes() < 3; i++) {
            Thread.sleep(20);
        }
        assertThat(leak.retainedMegabytes()).isGreaterThanOrEqualTo(3);

        leak.stop();
        assertThat(leak.retainedMegabytes()).isZero();
    }

    @Test
    @DisplayName("멈춘 뒤에는 이전 작업자가 더 붙잡지 않는다")
    void nothingRetainedAfterStop() throws Exception {
        leak.start(500);
        Thread.sleep(50);

        leak.stop();
        Thread.sleep(50);

        assertThat(leak.retainedMegabytes()).isZero();
    }

    @Test
    @DisplayName("이미 돌고 있으면 두 번째 시작은 무시한다 — 누수 스레드가 겹쳐 속도가 배가되지 않는다")
    void secondStartIsIgnored() {
        assertThat(leak.start(1)).isTrue();
        assertThat(leak.start(1)).isFalse();
    }

    @Test
    @DisplayName("속도가 0 이하면 거부한다")
    void rejectsNonPositiveRate() {
        assertThatThrownBy(() -> leak.start(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
