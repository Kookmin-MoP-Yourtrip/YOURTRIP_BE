package backend.yourtrip.global.health;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * {@code 127.0.0.1:<메인 포트>/internal/ping}을 짧은 타임아웃으로 호출한다.
 *
 * <p><b>메인 포트 번호는 기동 이벤트에서 받는다.</b> {@code server.port}를 읽으면 테스트의 무작위 포트(0)를 알 수
 * 없고, 관리 포트도 같은 이벤트를 발행하므로 네임스페이스로 구분한다 — 메인 서버는 네임스페이스가 없고 관리
 * 서버는 {@code "management"}다. 자식 컨텍스트의 이벤트는 부모로 전파되므로 이 리스너는 두 이벤트를 모두 받는다.
 *
 * <p><b>{@code localhost}가 아니라 {@code 127.0.0.1}로 부른다.</b> {@code localhost}가 IPv6(::1)로 먼저 해석되는
 * 환경에서 Tomcat이 IPv4에만 바인딩돼 있으면 확인이 실패해 멀쩡한 서버가 DOWN이 된다. 운영은
 * {@code server.address: 0.0.0.0}이라 루프백 IPv4로 닿는다.
 *
 * <p><b>HTTP/1.1로 고정한다.</b> JDK {@link HttpClient}의 기본값(HTTP/2)은 평문 연결에서 h2c 업그레이드를
 * 시도하는데, 이 확인에는 쓸모없는 왕복이고 업그레이드 처리 방식에 따라 결과가 흔들릴 여지만 만든다.
 */
@Slf4j
public class HttpMainPortProbe implements MainPortProbe,
    ApplicationListener<ServletWebServerInitializedEvent> {

    static final String PING_PATH = "/internal/ping";

    private final HttpClient client;
    private final Duration timeout;
    private volatile int mainPort = -1;

    public HttpMainPortProbe(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .build();
    }

    @Override
    public void onApplicationEvent(ServletWebServerInitializedEvent event) {
        if (event.getApplicationContext().getServerNamespace() == null) {
            mainPort = event.getWebServer().getPort();
        }
    }

    @Override
    public boolean ping() {
        int port = mainPort;
        if (port <= 0) {
            // 메인 서버가 뜨기 전이다. 헬스 엔드포인트도 그 전에는 서비스되지 않으므로 운영에서는 닿지 않는
            // 분기지만, 실패로 보고해 둔다 — 포트를 모르면서 성공이라고 할 근거는 없다.
            log.warn("메인 포트가 아직 정해지지 않아 직접 확인을 건너뛴다");
            return false;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PING_PATH))
            .timeout(timeout)
            .GET()
            .build();
        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            // 타임아웃(HttpTimeoutException)도 여기로 온다. 판정에 필요한 정보는 "실패" 하나뿐이고, 원인은
            // 헬스 응답의 상세와 함께 남긴다. DOWN이 이어지는 동안 30초마다 찍히므로 스택은 남기지 않는다.
            log.warn("메인 포트 직접 확인 실패: {}", e.toString());
            return false;
        }
    }
}
