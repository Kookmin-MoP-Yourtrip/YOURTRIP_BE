package backend.yourtrip.global.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import backend.yourtrip.global.exception.RetryLaterException;
import backend.yourtrip.global.exception.errorCode.MailErrorCode;
import backend.yourtrip.global.mail.MailMetrics;
import backend.yourtrip.global.mail.repository.MailLogRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 요청 스레드가 SMTP를 기다리지 않고, 메일 풀이 가득 차면 <b>기다리지 않고 바로</b> 503을 던지는지 고정한다
 * (이슈 #221, 설계 README 3-1·3-2절).
 *
 * <p>개선 전에는 요청 스레드(Tomcat 워커)가 SMTP 응답을 직접 기다렸다. SMTP가 느려지자 워커 32개가 전부 묶여
 * 메일과 무관한 API까지 멈췄다(docs/tasks/mail-async/measurement.md). 여기서는 SMTP를 "풀어 줄 때까지 멈춤"으로
 * 두고, 그동안 호출이 즉시 돌아오는지 본다. 풀은 운영과 같은 종류({@link ThreadPoolTaskExecutor}, 기본
 * {@code AbortPolicy})를 스레드 1 · 큐 1로 작게 만들어 포화를 재현한다.
 */
@DisplayName("MailService — 풀 제출과 즉시 거절 (#221)")
class MailServiceSubmitTest {

    private final CountDownLatch smtpRelease = new CountDownLatch(1);
    private JavaMailSender mailSender;
    private ThreadPoolTaskExecutor executor;
    private PrometheusMeterRegistry registry;
    private MailService mailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        // 멈춘 SMTP — 테스트가 풀어 줄 때까지 응답하지 않는다.
        doAnswer(invocation -> {
            smtpRelease.await(10, TimeUnit.SECONDS);
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("mail-test-");
        executor.initialize();

        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        mailService = new MailService(mailSender, mock(MailLogRepository.class), new MailMetrics(registry), executor);
    }

    @AfterEach
    void tearDown() {
        smtpRelease.countDown();
        executor.shutdown();
    }

    @Test
    @DisplayName("SMTP가 멈춰 있어도 호출은 접수만 하고 바로 돌아온다")
    void returnsWithoutWaitingForSmtp() {
        long start = System.nanoTime();

        mailService.sendVerificationMail("a@example.com", "111111");

        // 개선 전이라면 SMTP가 풀릴 때까지(최대 10초) 돌아오지 않는다.
        assertThat(elapsedMs(start)).isLessThan(500);
    }

    @Test
    @DisplayName("풀(스레드 1 + 큐 1)이 차면 세 번째 요청은 기다리지 않고 MAIL_BUSY·Retry-After 30초로 거절되고 rejected로 센다")
    void rejectsImmediatelyWhenPoolIsFull() {
        mailService.sendVerificationMail("a@example.com", "111111");  // 스레드에서 SMTP 대기
        mailService.sendVerificationMail("b@example.com", "222222");  // 큐 1칸

        long start = System.nanoTime();
        assertThatThrownBy(() -> mailService.sendVerificationMail("c@example.com", "333333"))
            .isInstanceOfSatisfying(RetryLaterException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(MailErrorCode.MAIL_BUSY);
                assertThat(e.getRetryAfterSeconds()).isEqualTo(MailService.RETRY_AFTER_SECONDS);
            });
        assertThat(elapsedMs(start)).isLessThan(500);

        assertThat(registry.get(MailMetrics.MAIL).tag("outcome", "rejected").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("거절된 메일은 SMTP를 시도하지 않고, 받아 둔 메일은 SMTP가 풀리면 모두 발송된다")
    void acceptedMailsAreDeliveredAfterSmtpRecovers() throws InterruptedException {
        mailService.sendVerificationMail("a@example.com", "111111");
        mailService.sendVerificationMail("b@example.com", "222222");
        assertThatThrownBy(() -> mailService.sendVerificationMail("c@example.com", "333333"))
            .isInstanceOf(RetryLaterException.class);

        smtpRelease.countDown();
        executor.getThreadPoolExecutor().shutdown();
        assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
        assertThat(registry.get(MailMetrics.MAIL).tag("outcome", "sent").counter().count()).isEqualTo(2);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
