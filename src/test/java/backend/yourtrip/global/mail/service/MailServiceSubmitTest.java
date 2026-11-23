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
import backend.yourtrip.global.mail.MailRetryBackoff;
import backend.yourtrip.global.mail.repository.MailLogRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.Optional;
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
        mailService = new MailService(mailSender, mock(MailLogRepository.class), new MailMetrics(registry), executor,
            // 저장소는 늘 같은 번호를 돌려준다 — 여기서는 제출·거절만 보고 낡은 번호 폐기는 MailServiceRetryTest가 본다.
            email -> Optional.of("111111"), new MailRetryBackoff(List.of(0L, 0L, 0L)));
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
        mailService.sendVerificationMail("b@example.com", "111111");  // 큐 1칸

        long start = System.nanoTime();
        assertThatThrownBy(() -> mailService.sendVerificationMail("c@example.com", "111111"))
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
        mailService.sendVerificationMail("b@example.com", "111111");
        assertThatThrownBy(() -> mailService.sendVerificationMail("c@example.com", "111111"))
            .isInstanceOf(RetryLaterException.class);

        smtpRelease.countDown();
        executor.getThreadPoolExecutor().shutdown();
        assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
        assertThat(registry.get(MailMetrics.MAIL).tag("outcome", "sent").counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("자리 수는 풀 설정(스레드 + 큐)에서 정해지고, 다 차면 예약 단계에서 MAIL_BUSY로 거절한다")
    void reserveRejectsWhenPoolCapacityIsTaken() {
        assertThat(MailService.capacityOf(executor)).isEqualTo(2);

        MailReservation first = mailService.reserve();
        MailReservation second = mailService.reserve();

        assertThatThrownBy(() -> mailService.reserve())
            .isInstanceOfSatisfying(RetryLaterException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(MailErrorCode.MAIL_BUSY));
        assertThat(registry.get(MailMetrics.MAIL).tag("outcome", "rejected").counter().count()).isEqualTo(1);

        first.close();
        second.close();
    }

    @Test
    @DisplayName("보내지 않고 닫은 예약은 자리를 돌려준다 — 두 번 닫아도 한 번만 돌려준다")
    void unusedReservationReturnsItsSlotOnce() {
        MailReservation first = mailService.reserve();
        MailReservation second = mailService.reserve();
        first.close();
        first.close();  // 두 번째 close는 아무것도 하지 않아야 한다 — 두 번 돌려주면 상한이 늘어난다

        MailReservation third = mailService.reserve();  // 돌려받은 한 자리
        assertThatThrownBy(() -> mailService.reserve()).isInstanceOf(RetryLaterException.class);

        second.close();
        third.close();
    }

    @Test
    @DisplayName("예약 하나로는 메일 한 통만 보낸다")
    void reservationSendsOnlyOnce() {
        try (MailReservation reservation = mailService.reserve()) {
            reservation.sendVerificationMail("a@example.com", "111111");
            assertThatThrownBy(() -> reservation.sendVerificationMail("a@example.com", "111111"))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("보낸 메일의 자리는 발송 작업이 끝나야 돌아온다")
    void sentReservationReturnsSlotWhenDeliveryFinishes() throws InterruptedException {
        try (MailReservation a = mailService.reserve()) {
            a.sendVerificationMail("a@example.com", "111111");
        }
        try (MailReservation b = mailService.reserve()) {
            b.sendVerificationMail("b@example.com", "111111");
        }
        // 두 통 모두 SMTP에서 멈춰 있어 자리가 없다 — close()는 이미 보낸 예약의 자리를 돌려주지 않는다.
        assertThatThrownBy(() -> mailService.reserve()).isInstanceOf(RetryLaterException.class);

        smtpRelease.countDown();
        executor.getThreadPoolExecutor().shutdown();
        assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // 발송이 끝나 두 자리가 돌아왔다.
        mailService.reserve().close();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
