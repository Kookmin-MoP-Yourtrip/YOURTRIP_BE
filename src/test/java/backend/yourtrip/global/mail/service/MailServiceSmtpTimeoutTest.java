package backend.yourtrip.global.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import backend.yourtrip.global.mail.MailMetrics;
import backend.yourtrip.global.mail.repository.MailLogRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * SMTP 서버가 연결만 받고 아무 말도 하지 않을 때, 발송이 <b>유한한 시간 안에 실패로 끝나고 지표에 남는지</b>
 * 고정한다 (이슈 #221).
 *
 * <p>개선 전 측정(docs/tasks/mail-async/measurement.md 4절)에서는 같은 상황에서 발송이 끝나지 않아
 * 스레드와 DB 커넥션이 영원히 묶였고, 시도가 끝나야 기록되는 {@code mail.send.attempt}가 420초 동안
 * 0건이었다. 타임아웃이 있으면 시도마다 실패로 끝나므로 그 장애가 지표에 드러난다.
 *
 * <p>읽기 타임아웃은 운영값(10초)이 아니라 짧은 값으로 둔다 — 여기서는 "타임아웃이 있으면 끝난다"는
 * 동작을 보고, 운영값이 실제로 들어가는지는 {@code MailSmtpTimeoutConfigTest}가 잠근다.
 */
@DisplayName("MailService — 무응답 SMTP (#221)")
class MailServiceSmtpTimeoutTest {

    private static final int READ_TIMEOUT_MS = 300;

    private ServerSocket silentServer;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private PrometheusMeterRegistry registry;
    private MailService mailService;

    @BeforeEach
    void setUp() throws IOException {
        // 연결은 받아 주지만 인사말(220)조차 보내지 않는 서버. 측정에서 쓴 Toxiproxy timeout 독성과 같은 모양이다.
        silentServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!silentServer.isClosed()) {
                try {
                    accepted.add(silentServer.accept());
                } catch (IOException closed) {
                    return;
                }
            }
        }, "silent-smtp");
        acceptor.setDaemon(true);
        acceptor.start();

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(silentServer.getInetAddress().getHostAddress());
        sender.setPort(silentServer.getLocalPort());
        Properties props = new Properties();
        props.setProperty("mail.smtp.connectiontimeout", "1000");
        props.setProperty("mail.smtp.timeout", String.valueOf(READ_TIMEOUT_MS));
        props.setProperty("mail.smtp.writetimeout", "1000");
        sender.setJavaMailProperties(props);

        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // 동기 실행기 — 발송 시간을 호출 구간에서 재려고 메일 스레드 대신 테스트 스레드에서 돌린다.
        mailService = new MailService(sender, mock(MailLogRepository.class), new MailMetrics(registry), Runnable::run);
    }

    @AfterEach
    void tearDown() throws IOException {
        silentServer.close();
        for (Socket s : accepted) {
            s.close();
        }
    }

    @Test
    @DisplayName("응답 없는 서버에 보내면 시도마다 읽기 타임아웃으로 실패하고, 실패 4·gave_up 1이 지표에 남는다")
    void silentServerFailsWithinTimeoutAndIsObservable() {
        long start = System.nanoTime();

        mailService.sendVerificationMail("user@example.com", "123456");

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // 현재 재시도는 대기 없이 4번이라 약 4 × 300ms. 타임아웃이 없으면 이 테스트는 끝나지 않는다.
        assertThat(elapsedMs).isLessThan(5_000);
        assertThat(accepted).hasSize(4);

        assertThat(registry.get(MailMetrics.ATTEMPT).tag("outcome", "failure").timer().count()).isEqualTo(4);
        assertThat(registry.get(MailMetrics.MAIL).tag("outcome", "gave_up").counter().count()).isEqualTo(1);
    }
}
