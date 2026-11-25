package backend.yourtrip.global.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import backend.yourtrip.global.mail.entity.MailLog;
import backend.yourtrip.global.mail.repository.MailLogRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 메일 발송이 <b>트랜잭션 밖에서</b> 도는지 고정한다 (이슈 #221, 설계 README 1-1·3-5절).
 *
 * <p>개선 전에는 {@code @Transactional} 때문에 SMTP를 기다리는 내내 DB 커넥션을 쥐었다. 측정에서 이 점유가
 * 커넥션 10개를 바닥내고 워커 32개까지 묶었다(measurement.md 3절). 또 최종 실패의 예외가 트랜잭션을
 * 롤백해, 앞서 저장한 실패 로그까지 지웠다.
 *
 * <p>실제 프록시와 H2를 거쳐야 트랜잭션·커넥션·롤백을 볼 수 있어 컨텍스트를 띄운다. SMTP만 목으로 바꾼다.
 * 발송 풀({@code mailExecutor})도 실제 빈이라, 발송이 요청 스레드가 아니라 메일 스레드에서 도는지까지 본다.
 */
// 재시도 간격을 0으로 — 실패 시나리오마다 운영 간격(합 7초)을 기다리지 않게 한다. 간격 자체는 MailServiceRetryTest가 본다.
@SpringBootTest(properties = "mail.retry.backoff-ms=0,0,0")
@ActiveProfiles("test")
@DisplayName("MailService — 트랜잭션 밖 발송 (#221)")
class MailServiceTransactionTest {

    private static final String TO = "tx-test@example.com";

    @Autowired
    private MailService mailService;

    @Autowired
    private MailLogRepository mailLogRepository;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    // 인터페이스(JavaMailSender)로 목을 만들면 메일 헬스체크 자동 설정이 구현체를 못 찾아 컨텍스트가 뜨지 않는다.
    private JavaMailSenderImpl mailSender;

    @BeforeEach
    void setUp() {
        mailLogRepository.deleteAll();
    }

    @Test
    @DisplayName("발송은 mail- 스레드에서 돌고, SMTP를 기다리는 동안 트랜잭션이 없고 DB 커넥션을 하나도 쥐지 않는다")
    void sendsOnMailThreadWithoutHoldingConnection() throws InterruptedException {
        List<String> threadNames = new CopyOnWriteArrayList<>();
        List<Boolean> txActive = new CopyOnWriteArrayList<>();
        List<Integer> activeConnections = new CopyOnWriteArrayList<>();
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(invocation -> {
            // mailSender.send() 안 = SMTP 왕복 중. 이 순간의 상태를 기록한다.
            threadNames.add(Thread.currentThread().getName());
            txActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            activeConnections.add(hikari().getHikariPoolMXBean().getActiveConnections());
            sent.countDown();
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, "123456");

        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadNames).singleElement().asString().startsWith("mail-");
        assertThat(txActive).containsExactly(false);
        assertThat(activeConnections).containsExactly(0);
        // SUCCESS 행은 send() 반환 뒤에 저장된다. 기다리지 않으면 다음 테스트의 deleteAll() 뒤에 끼어들 수 있다.
        assertThat(awaitLogs(1)).extracting(MailLog::getStatus).containsExactly("SUCCESS");
    }

    @Test
    @DisplayName("재시도를 모두 실패해도 시도마다의 실패 로그 4건이 롤백되지 않고 남는다 — 마지막 행은 GAVE_UP")
    void failedAttemptLogsSurviveFinalFailure() throws InterruptedException {
        doThrow(new MailSendException("일시 장애")).when(mailSender).send(any(SimpleMailMessage.class));

        // 포기는 메일 스레드 안에서 끝난다 — 호출자에게 예외가 오지 않는다.
        mailService.sendVerificationMail(TO, "123456");

        assertThat(awaitLogs(4))
            .extracting(MailLog::getStatus, MailLog::getRetryCount)
            .containsExactlyInAnyOrder(
                tuple("FAILED", 0),
                tuple("FAILED", 1),
                tuple("FAILED", 2),
                tuple("GAVE_UP", 3));
    }

    @Test
    @DisplayName("컬럼(255자)보다 긴 SMTP 오류 메시지도 잘라서 저장한다 — 로그 저장 실패가 원래 실패를 덮지 않는다")
    void longErrorMessageIsTruncated() throws InterruptedException {
        // 실제 메시지 예: "Mail server connection failed. Failed messages: ...MailConnectException: ... nested exception is: ..."
        String longMessage = "Mail server connection failed. " + "x".repeat(400);
        doThrow(new MailSendException(longMessage)).when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, "123456");

        assertThat(awaitLogs(4))
            .allSatisfy(log -> assertThat(log.getErrorMessage())
                .hasSizeLessThanOrEqualTo(255)
                .startsWith("Mail server connection failed."));
    }

    // 발송이 메일 스레드에서 끝나므로 MailLog가 다 쌓일 때까지 기다린다. 기대보다 많이 쌓이는 회귀도 잡도록
    // 개수에 닿으면 잠깐 더 기다렸다가 다시 센다.
    private List<MailLog> awaitLogs(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (mailLogRepository.count() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
        List<MailLog> logs = mailLogRepository.findAll();
        assertThat(logs).hasSize(expected);
        return logs;
    }

    private HikariDataSource hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
