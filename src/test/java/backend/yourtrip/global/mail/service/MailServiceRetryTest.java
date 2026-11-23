package backend.yourtrip.global.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import backend.yourtrip.global.mail.MailMetrics;
import backend.yourtrip.global.mail.MailRetryBackoff;
import backend.yourtrip.global.mail.entity.MailLog;
import backend.yourtrip.global.mail.repository.MailLogRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 재시도 정책을 고정한다 (이슈 #221, 설계 README 3-4·3-5절).
 *
 * <p>개선 전에는 실패하면 대기 없이 곧바로 3번 더 보냈고, 인증 오류처럼 다시 보내도 소용없는 실패와 일시 장애를 가르지
 * 않았다. 또 재시도 중에 사용자가 재발송하면, 늦게 성공한 옛 메일이 틀린 번호를 전했다. 여기서는
 * <ul>
 *   <li>일시 실패만 1·2·4초 간격으로 재시도하고</li>
 *   <li>SMTP 5xx·인증 오류는 즉시 포기하며</li>
 *   <li>재시도 직전에 저장된 번호가 바뀌었거나 만료됐으면 보내지 않고 버리는지</li>
 * </ul>
 * 를 본다. 실제로 기다리지 않도록 간격은 기록만 하는 백오프를 넣고, 실행기는 동기({@code Runnable::run})다.
 */
@DisplayName("MailService — 재시도 정책 (#221)")
class MailServiceRetryTest {

    private static final String TO = "retry@example.com";
    private static final String CODE = "123456";

    private JavaMailSender mailSender;
    private MailLogRepository mailLogRepository;
    private PrometheusMeterRegistry registry;
    private final List<Duration> waited = new ArrayList<>();
    private final AtomicReference<Optional<String>> storedCode = new AtomicReference<>(Optional.of(CODE));
    private final AtomicReference<RuntimeException> storeFailure = new AtomicReference<>();
    private MailService mailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        mailLogRepository = mock(MailLogRepository.class);
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // 운영 기본 간격(1·2·4초)을 그대로 쓰되, 기다리지 않고 기록만 한다.
        MailRetryBackoff recording = new MailRetryBackoff(List.of(1000L, 2000L, 4000L)) {
            @Override
            protected void sleep(Duration delay) {
                waited.add(delay);
            }
        };
        mailService = new MailService(mailSender, mailLogRepository, new MailMetrics(registry), Runnable::run,
            email -> {
                if (storeFailure.get() != null) {
                    throw storeFailure.get();
                }
                return storedCode.get();
            }, recording);
    }

    @Test
    @DisplayName("일시 실패 2번 뒤 성공 — 1초·2초를 기다리고, 로그는 FAILED 0 · FAILED 1 · SUCCESS 2")
    void transientFailuresAreRetriedWithBackoff() {
        doThrow(transientFailure()).doThrow(transientFailure()).doNothing()
            .when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, CODE);

        assertThat(waited).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(savedLogs()).extracting(MailLog::getStatus, MailLog::getRetryCount)
            .containsExactly(tuple("FAILED", 0), tuple("FAILED", 1), tuple("SUCCESS", 2));
        assertThat(mails("sent")).isEqualTo(1);
    }

    @Test
    @DisplayName("일시 실패가 계속되면 1·2·4초 간격으로 4번 시도하고 마지막 행을 GAVE_UP으로 남긴다")
    void givesUpAfterMaxRetries() {
        doThrow(transientFailure()).when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, CODE);

        verify(mailSender, times(4)).send(any(SimpleMailMessage.class));
        assertThat(waited).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(savedLogs()).extracting(MailLog::getStatus)
            .containsExactly("FAILED", "FAILED", "FAILED", "GAVE_UP");
        assertThat(mails("gave_up")).isEqualTo(1);
    }

    @Test
    @DisplayName("SMTP 5xx(없는 주소 550)는 영구 실패라 재시도하지 않는다")
    void permanentSmtpFailureIsNotRetried() throws Exception {
        SMTPAddressFailedException rejected = new SMTPAddressFailedException(
            new InternetAddress(TO), "RCPT TO", 550, "5.1.1 user unknown");
        doThrow(new MailSendException(Map.of(new Object(), rejected)))
            .when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, CODE);

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(waited).isEmpty();
        assertThat(savedLogs()).extracting(MailLog::getStatus).containsExactly("GAVE_UP");
        assertThat(mails("gave_up")).isEqualTo(1);
    }

    @Test
    @DisplayName("SMTP 4xx(일시 과부하 451)는 일시 실패라 재시도한다")
    void transientSmtpReplyIsRetried() {
        SMTPSendFailedException busy = new SMTPSendFailedException(
            "DATA", 451, "4.3.0 try again later", null, null, null, null);
        doThrow(new MailSendException(Map.of(new Object(), busy))).doNothing()
            .when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, CODE);

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
        assertThat(mails("sent")).isEqualTo(1);
    }

    @Test
    @DisplayName("인증 오류는 설정 문제라 재시도하지 않는다")
    void authenticationFailureIsNotRetried() {
        doThrow(new MailAuthenticationException("535 bad credentials"))
            .when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, CODE);

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(waited).isEmpty();
        assertThat(savedLogs()).extracting(MailLog::getStatus).containsExactly("GAVE_UP");
    }

    @Test
    @DisplayName("백오프 중 사용자가 재발송해 번호가 바뀌면 옛 메일은 보내지 않고 STALE로 버린다")
    void resendDuringBackoffDropsOldMail() {
        // 첫 시도가 실패한 직후(백오프 중) 재발송이 일어나 저장된 번호가 새 값이 된다.
        doAnswer(invocation -> {
            storedCode.set(Optional.of("999999"));
            throw transientFailure();
        }).when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, CODE);

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(savedLogs()).extracting(MailLog::getStatus, MailLog::getRetryCount)
            .containsExactly(tuple("FAILED", 0), tuple("STALE", 1));
        assertThat(mails("stale")).isEqualTo(1);
        assertThat(mails("gave_up")).isZero();
    }

    @Test
    @DisplayName("백오프 중 번호가 만료(5분)되면 보내 봐야 쓸 수 없으므로 STALE로 버린다")
    void expiredCodeDropsMail() {
        doAnswer(invocation -> {
            storedCode.set(Optional.empty());
            throw transientFailure();
        }).when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, CODE);

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(mails("stale")).isEqualTo(1);
    }

    @Test
    @DisplayName("번호 저장소 장애로 확인하지 못하면 버리지 않고 재시도한다")
    void storeFailureDoesNotDropMail() {
        doThrow(transientFailure()).doNothing().when(mailSender).send(any(SimpleMailMessage.class));
        storeFailure.set(new IllegalStateException("Redis 연결 실패"));

        mailService.sendVerificationMail(TO, CODE);

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
        assertThat(mails("sent")).isEqualTo(1);
        assertThat(mails("stale")).isZero();
    }

    @Test
    @DisplayName("큐에서 기다리는 동안 번호가 만료되면 첫 시도도 하지 않고 STALE로 버린다")
    void expiredWhileQueuedIsDroppedBeforeFirstAttempt() {
        // SMTP가 응답하지 않으면 한 통이 스레드를 약 47초 쥐어, 큐 뒤쪽 메일은 첫 시도 전에 5분이 지날 수 있다.
        storedCode.set(Optional.empty());

        mailService.sendVerificationMail(TO, CODE);

        verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
        assertThat(savedLogs()).extracting(MailLog::getStatus, MailLog::getRetryCount)
            .containsExactly(tuple("STALE", 0));
        assertThat(mails("stale")).isEqualTo(1);
    }

    // 연결 거부·타임아웃처럼 SMTP 응답 코드가 없는 실패 — JavaMailSenderImpl이 연결 실패를 이렇게 감싼다.
    private static MailSendException transientFailure() {
        return new MailSendException("Mail server connection failed");
    }

    private List<MailLog> savedLogs() {
        ArgumentCaptor<MailLog> captor = ArgumentCaptor.forClass(MailLog.class);
        verify(mailLogRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private double mails(String outcome) {
        return registry.get(MailMetrics.MAIL).tag("outcome", outcome).counter().count();
    }
}
