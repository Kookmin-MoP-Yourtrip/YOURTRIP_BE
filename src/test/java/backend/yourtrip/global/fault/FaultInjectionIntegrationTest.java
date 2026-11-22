package backend.yourtrip.global.fault;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * 결함 주입 장치가 운영 측정에서 쓸 그대로의 경로(8080의 워커 묶기, 관리 포트의 조작)로 동작하고, 각 결함에서
 * liveness가 503이 되는지 확인한다 (시나리오 S2·S3·S4·S6). 운영 측정 전에 장치 자체의 결함을 여기서 걸러낸다.
 *
 * <p>워커를 2개로 줄여, 운영의 "워커 32개가 전부 묶인" 상태를 요청 2개로 만든다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "fault-injection.enabled=true",
        "management.server.port=0",
        "server.tomcat.threads.max=2",
        "server.tomcat.threads.min-spare=1",
        "health.progress.stall-threshold=300ms",
        "health.progress.probe-timeout=500ms"
    })
@ActiveProfiles("test")
class FaultInjectionIntegrationTest {

    private static final Duration PAST_THRESHOLD = Duration.ofMillis(500);

    @LocalServerPort
    int mainPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    Tarpit tarpit;

    @Autowired
    MemoryLeak memoryLeak;

    private final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    private final List<CompletableFuture<HttpResponse<Void>>> inFlight = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        fault("resume-connector");
        fault("stop-leak");
        tarpit.releaseAll();
        interruptDeadlockedWorkers();
        inFlight.forEach(f -> f.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).exceptionally(e -> null).join());
        inFlight.clear();
    }

    @Test
    @DisplayName("S2 외부 API 무응답 — 워커가 전부 소켓 읽기에 묶이면 503, 연결을 닫으면 회복")
    void hangingExternalCall() throws Exception {
        send("/internal/fault/hang");
        send("/internal/fault/hang");
        await(() -> tarpit.waiting() == 2, "워커 2개가 무응답 소켓에 묶이지 않았다");
        Thread.sleep(PAST_THRESHOLD);

        assertThat(liveness().statusCode()).isEqualTo(503);

        assertThat(fault("release-hang").body()).contains("\"released\":2");
        inFlight.forEach(CompletableFuture::join);
        assertThat(liveness().statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("S3 데드락 — 락 순서 역전 한 쌍으로 워커가 전부 묶이면 503이고, JVM도 데드락으로 검출한다")
    void lockOrderDeadlock() throws Exception {
        send("/internal/fault/deadlock?side=a");
        send("/internal/fault/deadlock?side=b");
        await(() -> deadlockedThreadCount() >= 2, "데드락이 생기지 않았다");
        Thread.sleep(PAST_THRESHOLD);

        assertThat(liveness().statusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("S4 8080 커넥터 정지 — 관리 포트는 살아 있지만 8080 직접 확인이 실패해 503, 재개하면 회복")
    void pausedConnector() throws Exception {
        assertThat(fault("pause-connector").statusCode()).isEqualTo(200);
        Thread.sleep(PAST_THRESHOLD);

        assertThat(liveness().statusCode()).isEqualTo(503);

        fault("resume-connector");
        assertThat(liveness().statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("S6 메모리 누수 — 관리 포트에서 시작하면 메모리를 붙잡기 시작한다")
    void memoryLeakStarts() throws Exception {
        HttpResponse<String> response = fault("leak", "{\"mbPerSecond\":20}");

        assertThat(response.body()).contains("\"started\":true");
        await(() -> memoryLeak.retainedMegabytes() >= 2, "누수가 시작되지 않았다");
    }

    @Test
    @DisplayName("상태 조회가 묶인 연결 수를 알려 준다 — 측정 스크립트가 주입 성공을 확인하는 경로")
    void statusReportsWaiting() throws Exception {
        send("/internal/fault/hang");
        await(() -> tarpit.waiting() == 1, "워커가 묶이지 않았다");

        assertThat(fault("status").body()).contains("\"hangWaiting\":1");
    }

    private void send(String path) {
        inFlight.add(client.sendAsync(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mainPort + path)).build(),
            HttpResponse.BodyHandlers.discarding()));
    }

    private HttpResponse<String> liveness() throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health/liveness"))
                .timeout(Duration.ofSeconds(5)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> fault(String action) throws Exception {
        return fault(action, "{}");
    }

    /**
     * actuator 쓰기 동작은 본문이 비어 있어도 JSON {@code Content-Type}을 요구한다 — 빠뜨리면 415다. 측정 스크립트도
     * {@code curl -H 'Content-Type: application/json' -d '{}'}로 불러야 한다.
     */
    private HttpResponse<String> fault(String action, String jsonBody) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/fault/" + action))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(5)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(message);
    }

    private static int deadlockedThreadCount() {
        long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        return ids == null ? 0 : ids.length;
    }

    /**
     * 데드락에 걸린 Tomcat 워커를 인터럽트해 정리한다. {@code lockInterruptibly}라 인터럽트로 풀린다 — 이것이 장치를
     * {@code synchronized}가 아니라 {@code ReentrantLock}으로 만든 이유다. 정리하지 않으면 이 컨텍스트를 재사용하는
     * 테스트가 워커 없이 시작한다.
     */
    private static void interruptDeadlockedWorkers() {
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            boolean inDeadlock = Arrays.stream(stack)
                .anyMatch(frame -> frame.getClassName().equals(LockOrderDeadlock.class.getName()));
            if (inDeadlock) {
                thread.interrupt();
            }
        });
    }
}
