package backend.yourtrip.global.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.mail.entity.MailLog;
import backend.yourtrip.global.mail.repository.MailLogRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
 */
@SpringBootTest
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
    @DisplayName("SMTP를 기다리는 동안 트랜잭션이 없고 DB 커넥션을 하나도 쥐지 않는다")
    void sendsWithoutHoldingConnection() {
        List<Boolean> txActive = new ArrayList<>();
        List<Integer> activeConnections = new ArrayList<>();
        doAnswer(invocation -> {
            // mailSender.send() 안 = SMTP 왕복 중. 이 순간의 상태를 기록한다.
            txActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            activeConnections.add(hikari().getHikariPoolMXBean().getActiveConnections());
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));

        mailService.sendVerificationMail(TO, "123456");

        assertThat(txActive).containsExactly(false);
        assertThat(activeConnections).containsExactly(0);
    }

    @Test
    @DisplayName("재시도를 모두 실패해도 시도마다의 실패 로그 4건이 롤백되지 않고 남는다")
    void failedAttemptLogsSurviveFinalFailure() {
        doThrow(new MailSendException("일시 장애")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> mailService.sendVerificationMail(TO, "123456"))
            .isInstanceOf(BusinessException.class);

        assertThat(mailLogRepository.findAll())
            .extracting(MailLog::getStatus, MailLog::getRetryCount)
            .containsExactlyInAnyOrder(
                tuple("FAILED", 0),
                tuple("FAILED", 1),
                tuple("FAILED", 2),
                tuple("FAILED", 3));
    }

    @Test
    @DisplayName("컬럼(255자)보다 긴 SMTP 오류 메시지도 잘라서 저장한다 — 로그 저장 실패가 원래 실패를 덮지 않는다")
    void longErrorMessageIsTruncated() {
        // 실제 메시지 예: "Mail server connection failed. Failed messages: ...MailConnectException: ... nested exception is: ..."
        String longMessage = "Mail server connection failed. " + "x".repeat(400);
        doThrow(new MailSendException(longMessage)).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> mailService.sendVerificationMail(TO, "123456"))
            .isInstanceOf(BusinessException.class);

        assertThat(mailLogRepository.findAll())
            .hasSize(4)
            .allSatisfy(log -> assertThat(log.getErrorMessage())
                .hasSizeLessThanOrEqualTo(255)
                .startsWith("Mail server connection failed."));
    }

    private HikariDataSource hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
