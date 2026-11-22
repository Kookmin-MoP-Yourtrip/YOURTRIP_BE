package backend.yourtrip.global.health;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 진행 여부 판정의 조립 지점 (#216).
 *
 * <p>프로퍼티 클래스를 여기서 명시적으로 등록하는 것은 {@code AiConfig}와 같은 관례다 — 전역 스캔을 켜지 않아야
 * 어떤 클래스가 바인딩 대상인지가 등록 지점에서 보인다.
 */
@Configuration
@EnableConfigurationProperties(HealthProgressProperties.class)
public class HealthConfig {

    @Bean
    HttpMainPortProbe mainPortProbe(HealthProgressProperties properties) {
        return new HttpMainPortProbe(properties.probeTimeout());
    }

    /**
     * 빈 이름이 곧 health group에서 부르는 이름이다 — {@code application.yml}의
     * {@code management.endpoint.health.group.liveness.include}에 적힌 {@code requestProgress}.
     * Spring Boot는 이름 끝의 {@code HealthIndicator}를 떼어 기여자 이름을 만든다.
     */
    @Bean
    RequestProgressHealthIndicator requestProgressHealthIndicator(ProgressTracker tracker,
        MainPortProbe probe, HealthProgressProperties properties) {
        return new RequestProgressHealthIndicator(tracker, probe, properties.stallThreshold());
    }

    /**
     * 직접 확인 요청을 {@code http.server.requests}에서 뺀다. 이 지표는 부하테스트 집계와 운영 대시보드가 읽는데,
     * 한가할 때 헬스체크마다 들어오는 이 요청이 섞이면 "트래픽이 없는데 요청이 있는" 모습이 된다.
     */
    @Bean
    MeterFilter excludeInternalPingFromHttpMetrics() {
        return MeterFilter.deny(id -> "http.server.requests".equals(id.getName())
            && HttpMainPortProbe.PING_PATH.equals(id.getTag("uri")));
    }
}
