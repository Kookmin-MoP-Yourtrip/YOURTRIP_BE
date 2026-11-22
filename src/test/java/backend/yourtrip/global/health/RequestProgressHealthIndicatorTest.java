package backend.yourtrip.global.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * 판정 규칙(설계 문서 3-3절)을 시간과 직접 확인 결과를 조작해 잠근다. 네트워크·Tomcat 없이 규칙만 본다 —
 * 실제 워커를 묶었을 때의 동작은 {@link RequestProgressLivenessIntegrationTest}가 본다.
 */
class RequestProgressHealthIndicatorTest {

    private static final Duration THRESHOLD = Duration.ofSeconds(10);

    private final AtomicLong now = new AtomicLong(0);
    private final ProgressTracker tracker = new ProgressTracker(now::get);
    private final AtomicInteger probeCalls = new AtomicInteger();

    private RequestProgressHealthIndicator indicator(MainPortProbe probe) {
        return new RequestProgressHealthIndicator(tracker, () -> {
            probeCalls.incrementAndGet();
            return probe.ping();
        }, THRESHOLD);
    }

    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    @Test
    @DisplayName("최근 완료가 있으면 8080을 확인하지 않고 UP — 과부하에서 헬스체크가 줄에 서지 않는다")
    void recentCompletionIsUpWithoutProbing() {
        advance(Duration.ofSeconds(9));
        tracker.markCompleted();
        advance(Duration.ofSeconds(10));

        Health health = indicator(() -> false).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("check", "recent-completion");
        // 직접 확인이 실패하도록 해 두었으므로, 확인을 했다면 DOWN이었을 것이다.
        assertThat(probeCalls).hasValue(0);
    }

    @Test
    @DisplayName("완료가 끊겼어도 8080 확인이 성공하면 UP — 한가한 서버, 긴 요청 하나만 처리 중인 서버")
    void staleButProbeOkIsUp() {
        advance(Duration.ofSeconds(11));

        Health health = indicator(() -> true).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("check", "probe-ok");
        assertThat(probeCalls).hasValue(1);
    }

    @Test
    @DisplayName("완료가 끊기고 8080 확인도 실패하면 DOWN — 워커가 전부 묶였거나 8080이 요청을 받지 못한다")
    void staleAndProbeFailedIsDown() {
        advance(Duration.ofSeconds(11));

        Health health = indicator(() -> false).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("check", "probe-failed")
            .containsEntry("msSinceLastCompletion", 11_000L);
    }

    @Test
    @DisplayName("확인이 실패해도 그사이 다른 요청이 끝났으면 UP — 한가하다고 본 직후 요청이 몰린 경합")
    void completionDuringFailedProbeIsUp() {
        advance(Duration.ofSeconds(11));

        Health health = indicator(() -> {
            // 확인 요청이 줄 뒤에 밀려 있는 동안 앞의 요청이 끝났다.
            advance(Duration.ofSeconds(1));
            tracker.markCompleted();
            return false;
        }).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("check", "completed-during-probe");
    }

    @Test
    @DisplayName("임계값과 정확히 같은 경과는 아직 최근 완료로 본다")
    void exactlyAtThresholdIsRecent() {
        advance(THRESHOLD);

        assertThat(indicator(() -> false).health().getStatus()).isEqualTo(Status.UP);
        assertThat(probeCalls).hasValue(0);
    }

    @Test
    @DisplayName("기동 직후에는 기동 시각이 첫 완료 기록이라 확인 없이 UP")
    void startupCountsAsCompletion() {
        assertThat(indicator(() -> false).health().getStatus()).isEqualTo(Status.UP);
        assertThat(probeCalls).hasValue(0);
    }
}
