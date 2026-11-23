package backend.yourtrip.global.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import backend.yourtrip.global.exception.RetryLaterException;
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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * "예약을 얻은 제출은 풀에서 거절되지 않는다"를 자리 반납 직후의 틈에서 고정한다 (이슈 #221, PR #224 리뷰).
 *
 * <p>메일 작업은 본문 끝에서 자리를 반납하는데, 그 순간 워커는 아직 큐에서 다음 작업을 꺼내지 않았다. 자리 수를
 * "스레드 + 큐"로 두면 이 틈에 들어온 예약이 성공한 뒤 큐가 가득 차 제출이 거절된다 — 호출자는 이미 인증번호를
 * 덮어쓴 뒤라, 큐의 이전 메일이 낡은 번호로 버려진다. 자리 수를 큐 용량으로 두면 자리를 쥔 메일은 모두 실행 중이거나
 * 큐에 있으므로 큐에 든 메일 수가 자리 수(= 큐 용량)를 넘지 못한다.
 *
 * <p>틈을 재현하려고, 작업이 끝난 뒤(자리를 반납한 뒤) 워커가 다음 작업을 꺼내지 못하게 붙잡는 장식자를 단다.
 */
@DisplayName("MailService — 자리 반납 직후의 예약 (#221)")
class MailReservationRaceTest {

    private final CountDownLatch finished = new CountDownLatch(1);
    private final CountDownLatch holdWorker = new CountDownLatch(1);
    private ThreadPoolTaskExecutor executor;
    private MailService mailService;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        // 작업 본문(자리 반납 포함)이 끝난 뒤, 워커가 큐로 돌아가기 전에 멈춘다.
        executor.setTaskDecorator(task -> () -> {
            task.run();
            finished.countDown();
            try {
                holdWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        executor.initialize();

        mailService = new MailService(mock(JavaMailSender.class), mock(MailLogRepository.class),
            new MailMetrics(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)), executor,
            email -> Optional.of("111111"), new MailRetryBackoff(List.of(0L, 0L, 0L)));
    }

    @AfterEach
    void tearDown() {
        holdWorker.countDown();
        executor.shutdown();
    }

    @Test
    @DisplayName("작업이 자리를 반납했지만 워커가 아직 큐로 돌아가지 않은 틈에도, 큐에 넣을 수 없는 예약은 내주지 않는다")
    void doesNotGrantReservationThatTheQueueCannotHold() throws InterruptedException {
        try (MailReservation a = mailService.reserve()) {
            a.sendVerificationMail("a@example.com", "111111");
        }
        // A가 끝나 자리를 반납했다. 워커는 붙잡혀 있어 큐에서 다음 작업을 꺼내지 못한다.
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();

        // B는 큐 1칸에 들어간다.
        try (MailReservation b = mailService.reserve()) {
            b.sendVerificationMail("b@example.com", "111111");
        }

        // 큐가 가득 찼으므로 C는 예약 단계에서 거절돼야 한다 — 호출자가 번호를 저장하기 전에.
        // 자리 수를 "스레드 + 큐"(2)로 두면 여기서 예약이 성공하고, 저장 뒤의 제출이 큐 포화로 거절된다.
        assertThatThrownBy(() -> mailService.reserve()).isInstanceOf(RetryLaterException.class);
    }
}
