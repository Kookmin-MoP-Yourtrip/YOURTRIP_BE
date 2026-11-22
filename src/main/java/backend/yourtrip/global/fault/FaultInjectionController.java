package backend.yourtrip.global.fault;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>8080 워커를 묶는</b> 결함 주입 장치 (시나리오 S2·S3·S7). 측정 전용이다.
 *
 * <p>워커를 묶으려면 요청이 8080으로 들어와야 하므로 이 장치만 메인 포트에 둔다. 상태를 조작하는 장치(해제, 커넥터
 * 정지, 누수)는 관리 포트의 {@link FaultInjectionEndpoint}에 있다 — 워커를 다 묶은 뒤에도 조작할 수 있어야 하기
 * 때문이다.
 *
 * <p>호출은 인스턴스 안에서만 한다({@code curl localhost:8080/internal/fault/...}). ALB 리스너가 {@code /internal}을
 * 차단한다.
 */
@RestController
@RequestMapping("/internal/fault")
@RequiredArgsConstructor
@ConditionalOnFaultInjection
public class FaultInjectionController {

    private final Tarpit tarpit;
    private final LockOrderDeadlock deadlock;

    /**
     * 응답하지 않는 소켓에서 <b>타임아웃 없이</b> 읽기를 건다 — 외부 호출에 타임아웃을 빠뜨린 코드와 같다.
     * {@link Tarpit#releaseAll()}이 연결을 닫을 때까지 워커가 돌아오지 않는다.
     */
    @GetMapping("/hang")
    public ResponseEntity<Void> hang() throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), tarpit.port());
             InputStream in = socket.getInputStream()) {
            // SO_TIMEOUT을 걸지 않는다. 이 장치의 목적이 바로 그 누락이다.
            in.read();
        }
        return ResponseEntity.noContent().build();
    }

    /** 락 순서 역전으로 워커를 묶는다. {@code side=a}와 {@code side=b}를 동시에 보내면 데드락이 생긴다. */
    @GetMapping("/deadlock")
    public ResponseEntity<Void> deadlock(@RequestParam("side") String side) throws InterruptedException {
        deadlock.enter(LockOrderDeadlock.Side.valueOf(side.toUpperCase()));
        return ResponseEntity.noContent().build();
    }
}
