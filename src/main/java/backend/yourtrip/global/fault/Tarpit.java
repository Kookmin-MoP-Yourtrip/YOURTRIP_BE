package backend.yourtrip.global.fault;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 연결은 받지만 아무것도 보내지 않는 로컬 소켓 — "응답하지 않는 외부 API"의 대역이다 (시나리오 S2·S7).
 *
 * <p>클라이언트가 타임아웃 없이 읽기를 걸면 워커가 {@code SocketInputStream.read}에서 무한히 기다린다. 외부 호출에
 * 타임아웃을 빠뜨렸을 때 실제로 생기는 상태와 같고, 스레드 덤프에 그대로 드러난다 — {@code Thread.sleep}으로 묶으면
 * 덤프에는 TIMED_WAITING이 보여 "외부 대기"라는 주장을 증거로 남길 수 없다.
 *
 * <p>루프백(127.0.0.1)의 임시 포트에만 바인딩한다. 외부에서 이 소켓에 닿을 경로는 없다.
 *
 * <p>{@link #releaseAll()}이 받아 둔 연결을 모두 닫으면 대기하던 읽기가 EOF로 끝나 워커가 풀린다. 측정에서는 교체로
 * 끝나므로 필요 없지만, 통합 테스트가 같은 장치를 안전하게 쓰려면 되돌릴 수 있어야 한다.
 */
@Slf4j
@Component
@ConditionalOnFaultInjection
public class Tarpit {

    private final ServerSocket server;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();

    public Tarpit() throws IOException {
        this.server = new ServerSocket(0, 1024, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "fault-tarpit-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log.warn("결함 주입: 무응답 소켓이 127.0.0.1:{}에서 대기한다", server.getLocalPort());
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                accepted.add(server.accept());
            } catch (IOException e) {
                if (!server.isClosed()) {
                    log.warn("무응답 소켓 accept 실패: {}", e.toString());
                }
            }
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    /** 지금 붙잡혀 있는 연결 수. 요청을 보낸 것만으로는 워커가 실제로 묶였는지 알 수 없어, 묶인 것을 이 값으로 확인한다. */
    public int waiting() {
        return accepted.size();
    }

    /** 받아 둔 연결을 모두 닫는다. 대기하던 읽기는 EOF를 받고 끝난다. 닫은 연결 수를 돌려준다. */
    public int releaseAll() {
        int count = 0;
        for (Socket socket : accepted) {
            closeQuietly(socket);
            accepted.remove(socket);
            count++;
        }
        return count;
    }

    @PreDestroy
    void close() {
        releaseAll();
        try {
            server.close();
        } catch (IOException ignored) {
            // 종료 중이다. 남길 것이 없다.
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 이미 닫힌 연결이다.
        }
    }
}
