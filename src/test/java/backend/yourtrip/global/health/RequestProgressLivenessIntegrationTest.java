package backend.yourtrip.global.health;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 Tomcat 워커를 묶었을 때 관리 포트의 liveness가 어떻게 답하는지 확인한다 (설계 문서 P2·P5의 로직 부분).
 *
 * <p>운영과 같은 구조를 작게 만든다 — 관리 포트를 따로 띄우고(운영 8081 ↔ 여기서는 무작위), 메인 포트 워커를 2개로
 * 줄여 "워커가 전부 묶인" 상태를 요청 2개로 만든다. 임계값·타임아웃도 수백 ms로 줄여 테스트가 늘어지지 않게 한다.
 * 운영 인프라 측정에서는 이 로직이 아니라 포트·보안그룹·ALB·ASG 연결과 실제 소요 시간만 확인하면 된다.
 *
 * <p>워커를 묶는 장치는 테스트 전용 필터다. 컨트롤러로 만들면 보안 필터가 인증 없는 요청을 403으로 돌려보내 워커가
 * 묶이지 않는다. {@link RequestProgressFilter} 바로 안쪽에 두므로, 묶인 동안에는 완료 기록이 갱신되지 않는다 — 운영의
 * "워커가 멈춘" 상태와 바깥에서 보이는 모습이 같다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "management.server.port=0",
        "server.tomcat.threads.max=2",
        "server.tomcat.threads.min-spare=1",
        "health.progress.stall-threshold=300ms",
        "health.progress.probe-timeout=500ms"
    })
@Import(RequestProgressLivenessIntegrationTest.HoldConfig.class)
@ActiveProfiles("test")
class RequestProgressLivenessIntegrationTest {

    private static final String HOLD_PATH = "/test/hold";
    private static final Duration PAST_THRESHOLD = Duration.ofMillis(500);

    /** 묶인 요청을 풀어 줄 문. 테스트마다 새로 연다. */
    static volatile CountDownLatch release = new CountDownLatch(1);
    /** 워커가 실제로 묶였음을 알리는 신호. 요청을 보낸 것만으로는 워커에 닿았다는 보장이 없다. */
    static final Semaphore held = new Semaphore(0);

    @LocalServerPort
    int mainPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    ProgressTracker tracker;

    @Autowired
    HealthContributorRegistry contributors;

    private final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    private final List<CompletableFuture<HttpResponse<Void>>> holds = new ArrayList<>();

    @AfterEach
    void releaseHolds() {
        release.countDown();
        holds.forEach(CompletableFuture::join);
        holds.clear();
        release = new CountDownLatch(1);
        held.drainPermits();
    }

    @Test
    @DisplayName("관리 포트가 메인 포트와 따로 뜨고, liveness 그룹에 requestProgress가 들어 있다")
    void wiring() {
        assertThat(managementPort).isNotEqualTo(mainPort);
        assertThat(contributors.getContributor("requestProgress")).isNotNull();
    }

    @Test
    @DisplayName("한가할 때 — 완료 기록이 끊겨도 8080 직접 확인이 성공해 UP")
    void idleIsUp() throws Exception {
        Thread.sleep(PAST_THRESHOLD);

        HttpResponse<String> liveness = liveness();

        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("probe-ok");
    }

    @Test
    @DisplayName("워커가 전부 묶이면 — 완료가 없고 직접 확인도 처리되지 못해 503 (P2)")
    void allWorkersHeldIsDown() throws Exception {
        holdWorkers(2);
        Thread.sleep(PAST_THRESHOLD);

        HttpResponse<String> liveness = liveness();

        assertThat(liveness.statusCode()).isEqualTo(503);
        assertThat(liveness.body()).contains("probe-failed");
    }

    @Test
    @DisplayName("묶인 워커가 풀리면 다시 UP — 상태를 되돌리는 별도 코드 없이 회복한다")
    void recoversAfterRelease() throws Exception {
        holdWorkers(2);
        Thread.sleep(PAST_THRESHOLD);
        assertThat(liveness().statusCode()).isEqualTo(503);

        release.countDown();
        holds.forEach(CompletableFuture::join);

        assertThat(liveness().statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("긴 요청 하나만 처리 중일 때 — 남은 워커가 직접 확인을 처리해 UP (P5)")
    void oneLongRequestIsUp() throws Exception {
        holdWorkers(1);
        Thread.sleep(PAST_THRESHOLD);

        HttpResponse<String> liveness = liveness();

        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("probe-ok");
    }

    @Test
    @DisplayName("관리 포트 요청은 완료 기록을 갱신하지 않는다 — 갱신하면 8080이 멈춰도 영원히 UP")
    void managementRequestsAreNotCounted() throws Exception {
        long before = tracker.lastCompletedAt();

        // liveness는 직접 확인으로 8080을 부를 수 있어 쓰지 않는다. 확인을 일으키지 않는 엔드포인트로 본다.
        HttpResponse<String> metrics = get(managementPort, "/actuator/metrics");

        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(tracker.lastCompletedAt()).isEqualTo(before);
    }

    @Test
    @DisplayName("직접 확인 경로는 인증 없이 2xx — 403이면 멀쩡한 서버가 DOWN이 된다")
    void pingIsPermitted() throws Exception {
        assertThat(get(mainPort, HttpMainPortProbe.PING_PATH).statusCode()).isEqualTo(204);
    }

    private void holdWorkers(int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            holds.add(client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mainPort + HOLD_PATH)).build(),
                HttpResponse.BodyHandlers.discarding()));
        }
        assertThat(held.tryAcquire(count, 5, TimeUnit.SECONDS))
            .as("워커 %d개가 묶이지 않았다", count).isTrue();
    }

    private HttpResponse<String> liveness() throws Exception {
        return get(managementPort, "/actuator/health/liveness");
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class HoldConfig {

        @Bean
        FilterRegistrationBean<Filter> holdFilter() {
            Filter filter = (req, res, chain) -> {
                if (HOLD_PATH.equals(((HttpServletRequest) req).getRequestURI())) {
                    held.release();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ((HttpServletResponse) res).setStatus(204);
                    return;
                }
                chain.doFilter(req, res);
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            // RequestProgressFilter(HIGHEST_PRECEDENCE) 바로 안쪽. 바깥에 두면 묶인 요청이 완료 기록 밖에 있게 된다.
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
            return registration;
        }
    }
}
