package backend.yourtrip.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Meter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code hibernate.generate_statistics: true}가 실제로 Micrometer 메트릭까지 이어지는지 잠근다.
 *
 * <p>이 테스트가 필요한 이유는 과거에 정확히 이 지점이 <b>조용히 깨져 있었기</b> 때문이다 —
 * {@code docs/tasks/TASK-CLOUDFRONT.md}에 기록된 대로, 설정을 켰는데도 {@code /actuator/metrics}에
 * {@code hibernate.*}가 전혀 나오지 않았다. 원인은 Spring Boot의 {@code HibernateMetricsAutoConfiguration}이
 * {@code @ConditionalOnClass}로 요구하는 {@code org.hibernate.stat.HibernateMetrics}가 {@code hibernate-core}가
 * 아니라 별도 아티팩트({@code org.hibernate.orm:hibernate-micrometer})에 있어서, 그 의존성이 없으면
 * 자동 설정 자체가 활성화되지 않는 것이었다.
 *
 * <p>설정과 의존성 중 어느 한쪽이라도 빠지면 <b>예외 없이 메트릭만 사라진다.</b> 부하테스트의
 * "요청당 SQL 수"와 Grafana의 Hibernate 패널이 통째로 그 위에 서 있으므로, 회귀를 여기서 잡는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class HibernateMetricsRegistrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("hibernate.statements 메트릭이 Micrometer에 등록된다")
    void hibernateStatementsMeterIsRegistered() {
        assertThat(meterRegistry.find("hibernate.statements").meter())
            .as("hibernate.statements가 없다면 generate_statistics 설정이나 "
                + "hibernate-micrometer 의존성 중 하나가 빠진 것이다")
            .isNotNull();
    }

    @Test
    @DisplayName("Hibernate 세션 메트릭 묶음이 함께 등록된다")
    void hibernateSessionMetersAreRegistered() {
        assertThat(meterRegistry.getMeters())
            .extracting(meter -> meter.getId().getName())
            .filteredOn(name -> name.startsWith("hibernate."))
            .isNotEmpty();
    }

    @Test
    @DisplayName("hibernate.statements는 카운터라 Prometheus에서 _total 접미사로 노출된다")
    void hibernateStatementsIsCounterType() {
        Meter meter = meterRegistry.find("hibernate.statements").meter();
        assertThat(meter).isNotNull();
        // Grafana 대시보드가 rate(hibernate_statements_total[...])로 질의한다.
        // 타입이 카운터가 아니면 그 이름으로 노출되지 않아 패널이 조용히 빈다.
        assertThat(meter.getId().getType()).isEqualTo(Meter.Type.COUNTER);
    }
}
