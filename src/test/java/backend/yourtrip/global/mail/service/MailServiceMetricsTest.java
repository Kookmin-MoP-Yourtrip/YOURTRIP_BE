package backend.yourtrip.global.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import backend.yourtrip.global.mail.MailMetrics;
import backend.yourtrip.global.mail.MailRetryBackoff;
import backend.yourtrip.global.mail.repository.MailLogRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 메일 발송 지표가 <b>시도 단위와 메일 단위를 섞지 않는지</b>를 고정한다 (이슈 #221).
 *
 * <p>현재 재시도는 재귀 호출이라, 결말을 세는 자리를 잘못 잡으면 재귀 깊이만큼 중복 집계된다.
 * 그래서 "메일 한 통 = {@code mail.send} 정확히 1"을 시나리오마다 단언한다.
 *
 * <p>재시도 분류·백오프·낡은 코드 폐기의 동작은 {@code MailServiceRetryTest}가 맡는다. 여기서는 백오프를 0으로,
 * 저장된 코드를 늘 같은 값으로 두고 지표 집계만 본다.
 *
 * <p>발송은 {@code mailExecutor}에서 돌지만, 여기서는 동기 실행기({@code Runnable::run})를 넣어 지표만 본다.
 * 그래서 포기한 메일도 호출자에게 예외가 오지 않는다 — 발송 결과는 응답이 아니라 지표로만 보이기 때문이다.
 */
@DisplayName("MailService — 발송 지표 (#221)")
class MailServiceMetricsTest {

    private static final String TO = "user@example.com";

    private JavaMailSender mailSender;
    private PrometheusMeterRegistry registry;
    private MailService mailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        mailService = new MailService(mailSender, mock(MailLogRepository.class),
            new MailMetrics(registry), Runnable::run, email -> Optional.of("123456"),
            new MailRetryBackoff(List.of(0L, 0L, 0L)));
    }

    @Test
    @DisplayName("첫 시도에 성공하면 시도 성공 1, 메일 sent 1")
    void firstAttemptSuccess() {
        mailService.sendVerificationMail(TO, "123456");

        assertThat(attempts("success")).isEqualTo(1);
        assertThat(attempts("failure")).isZero();
        assertThat(mails("sent")).isEqualTo(1);
        assertThat(mails("gave_up")).isZero();
    }

    @Test
    @DisplayName("두 번 실패한 뒤 성공하면 시도 실패 2·성공 1, 메일은 sent 1 — 재시도가 흡수한 실패는 메일 결말에 남지 않는다")
    void succeedsAfterRetries() {
        doThrow(new MailSendException("일시 장애"))
            .doThrow(new MailSendException("일시 장애"))
            .doNothing()
            .when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, "123456");

        assertThat(attempts("failure")).isEqualTo(2);
        assertThat(attempts("success")).isEqualTo(1);
        assertThat(mails("sent")).isEqualTo(1);
        assertThat(mails("gave_up")).isZero();
    }

    @Test
    @DisplayName("네 번 모두 실패하면 시도 실패 4, 메일 gave_up 1 — 재귀 깊이만큼 중복 집계되지 않는다")
    void givesUpAfterAllRetries() {
        doThrow(new MailSendException("장애")).when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, "123456");

        assertThat(attempts("failure")).isEqualTo(4);
        assertThat(attempts("success")).isZero();
        assertThat(mails("gave_up")).isEqualTo(1);
        assertThat(mails("sent")).isZero();
    }

    @Test
    @DisplayName("MailSendException이 아닌 실패(인증 오류)는 재시도하지 않는다 — 시도 실패 1, 메일 gave_up 1")
    void nonSendFailureIsNotRetried() {
        doThrow(new MailAuthenticationException("인증 실패"))
            .when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, "123456");

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(attempts("failure")).isEqualTo(1);
        assertThat(mails("gave_up")).isEqualTo(1);
    }

    @Test
    @DisplayName("기동 직후 모든 결말 시계열이 0으로 노출되고, 시도 지연은 히스토그램 버킷으로 나간다")
    void zeroSeriesAndHistogramAreExposed() {
        String scrape = registry.scrape();

        assertThat(scrape)
            .contains("mail_send_total{outcome=\"sent\"} 0.0")
            .contains("mail_send_total{outcome=\"gave_up\"} 0.0")
            .contains("mail_send_total{outcome=\"rejected\"} 0.0")
            .contains("mail_send_total{outcome=\"stale\"} 0.0")
            .contains("mail_send_attempt_seconds_count{outcome=\"success\"} 0")
            .contains("mail_send_attempt_seconds_count{outcome=\"failure\"} 0")
            // p95를 서버 쪽에서 계산하려면 누적 버킷이 있어야 한다
            .contains("mail_send_attempt_seconds_bucket{outcome=\"success\",le=");
    }

    private long attempts(String outcome) {
        return registry.get(MailMetrics.ATTEMPT).tag("outcome", outcome).timer().count();
    }

    private double mails(String outcome) {
        return registry.get(MailMetrics.MAIL).tag("outcome", outcome).counter().count();
    }
}
