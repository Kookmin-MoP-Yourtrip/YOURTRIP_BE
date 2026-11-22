package backend.yourtrip.global.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HealthConfigTest {

    @Test
    @DisplayName("직접 확인 요청만 http.server.requests에서 빠지고 다른 요청과 다른 지표는 남는다")
    void excludesOnlyInternalPing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new HealthConfig().excludeInternalPingFromHttpMetrics());

        registry.timer("http.server.requests", Tags.of("uri", HttpMainPortProbe.PING_PATH)).record(() -> {});
        registry.timer("http.server.requests", Tags.of("uri", "/api/feeds")).record(() -> {});
        registry.timer("other.timer", Tags.of("uri", HttpMainPortProbe.PING_PATH)).record(() -> {});

        assertThat(registry.find("http.server.requests").tag("uri", HttpMainPortProbe.PING_PATH).timer()).isNull();
        assertThat(registry.find("http.server.requests").tag("uri", "/api/feeds").timer()).isNotNull();
        assertThat(registry.find("other.timer").timer()).isNotNull();
    }
}
