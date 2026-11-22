package backend.yourtrip.global.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestProgressFilterTest {

    private final AtomicLong now = new AtomicLong(0);
    private final ProgressTracker tracker = new ProgressTracker(now::get);
    private final RequestProgressFilter filter = new RequestProgressFilter(tracker);

    private void run(String uri, FilterChain chain) throws Exception {
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        filter.doFilter(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse(), chain);
    }

    @Test
    @DisplayName("메인 포트 요청이 끝나면 완료로 기록한다")
    void recordsCompletion() throws Exception {
        run("/api/upload-courses", new MockFilterChain());

        assertThat(tracker.sinceLastCompletion()).isZero();
    }

    @Test
    @DisplayName("예외로 끝난 요청도 완료로 기록한다 — 워커가 응답을 끝냈다는 사실은 같다")
    void recordsCompletionOnException() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("처리 중 실패");
        };

        assertThatThrownBy(() -> run("/api/feeds", failing)).isInstanceOf(IllegalStateException.class);
        assertThat(tracker.sinceLastCompletion()).isZero();
    }

    @Test
    @DisplayName("actuator 요청은 기록하지 않는다 — 헬스체크가 스스로를 '진행'으로 세면 판정이 무력화된다")
    void ignoresActuator() throws Exception {
        run("/actuator/health/liveness", new MockFilterChain());
        run("/actuator", new MockFilterChain());

        assertThat(tracker.sinceLastCompletion()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("actuator로 시작하지만 다른 경로는 기록한다 — 접두사 비교가 경로 경계를 지킨다")
    void prefixRespectsPathBoundary() throws Exception {
        run("/actuatorish", new MockFilterChain());

        assertThat(tracker.sinceLastCompletion()).isZero();
    }
}
