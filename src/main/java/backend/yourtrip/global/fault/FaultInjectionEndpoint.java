package backend.yourtrip.global.fault;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * <b>관리 포트에서</b> 결함 상태를 조작하는 장치 (시나리오 S2·S4·S6·S7). 측정 전용이다.
 *
 * <p>{@code POST /actuator/fault/{action}}. 운영 구성에서는 8081에 뜬다. 8080을 멈추거나 워커를 다 묶은 뒤에도
 * 조작할 수 있어야 하므로 관리 포트에 둔다.
 *
 * <ul>
 *   <li>{@code pause-connector} / {@code resume-connector} — 8080 커넥터의 요청 처리를 멈추고 재개한다(S4). JVM과
 *       관리 포트는 그대로 살아 있으므로 "8080만 죽은" 상태가 된다. JMX를 열지 않고 메인 서버 객체에서 직접 부른다</li>
 *   <li>{@code release-hang} — {@link Tarpit}의 연결을 닫아 묶인 워커를 푼다</li>
 *   <li>{@code leak} — {@link MemoryLeak}을 시작한다(S6). {@code mbPerSecond}로 속도를 준다</li>
 *   <li>{@code stop-leak} — 누수를 멈추고 붙잡은 메모리를 놓는다(테스트 정리용)</li>
 *   <li>{@code status} — 붙잡힌 연결 수와 누수량. 주입이 의도대로 걸렸는지 측정 스크립트가 확인한다</li>
 * </ul>
 *
 * <p>이 빈은 메인 컨텍스트에 있으므로 {@link ApplicationContext}는 메인 컨텍스트이고, 그 웹 서버가 8080이다. 관리
 * 서버는 자식 컨텍스트에 따로 있다.
 */
@Slf4j
@Component
@Endpoint(id = "fault")
@RequiredArgsConstructor
@ConditionalOnFaultInjection
public class FaultInjectionEndpoint {

    private final ApplicationContext context;
    private final Tarpit tarpit;
    private final MemoryLeak memoryLeak;

    @WriteOperation
    public Map<String, Object> act(@Selector String action, @Nullable Integer mbPerSecond) {
        log.warn("결함 주입: {}", action);
        return switch (action) {
            case "pause-connector" -> {
                mainConnector().pause();
                yield Map.of("action", action, "port", mainConnector().getLocalPort());
            }
            case "resume-connector" -> {
                mainConnector().resume();
                yield Map.of("action", action, "port", mainConnector().getLocalPort());
            }
            case "release-hang" -> Map.of("action", action, "released", tarpit.releaseAll());
            case "status" -> Map.of("action", action, "hangWaiting", tarpit.waiting(),
                "leakRetainedMb", memoryLeak.retainedMegabytes());
            case "leak" -> {
                int rate = mbPerSecond == null ? 10 : mbPerSecond;
                yield Map.of("action", action, "started", memoryLeak.start(rate), "mbPerSecond", rate);
            }
            case "stop-leak" -> {
                memoryLeak.stop();
                yield Map.of("action", action);
            }
            default -> throw new IllegalArgumentException("알 수 없는 동작: " + action);
        };
    }

    private Connector mainConnector() {
        WebServer server = ((ServletWebServerApplicationContext) context).getWebServer();
        return ((TomcatWebServer) server).getTomcat().getConnector();
    }
}
