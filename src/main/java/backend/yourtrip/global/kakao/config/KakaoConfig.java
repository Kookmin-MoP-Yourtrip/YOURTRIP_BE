package backend.yourtrip.global.kakao.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
@RequiredArgsConstructor
public class KakaoConfig {

    /**
     * TCP 연결 수립 제한. 카카오 API는 국내 리전이라 정상 연결은 수십 ms면 끝난다 —
     * 2초를 넘긴다면 네트워크나 상대 쪽 문제이므로 기다릴 이유가 없다.
     */
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    /**
     * 요청 전송 후 응답 수신까지의 제한. 기존에는 이 값이 없어 응답을 무제한 기다렸고,
     * 유일한 방어이던 {@code .block(20초)}는 초과 시 IllegalStateException을 던져
     * WebClientResponseException catch를 빠져나가 원시 500이 됐다.
     */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 커넥션 풀 크기. reactor-netty 기본값은 max(가용 프로세서, 8)이라 머신에 따라
     * 달라진다 — 동작이 환경에 좌우되지 않도록 명시한다. AI 코스 생성 1건이 장소 수만큼
     * (최대 18회) 순차 호출하므로, 동시 요청 수만큼의 커넥션이면 충분하다.
     */
    private static final int MAX_CONNECTIONS = 50;

    /**
     * 풀이 가득 찼을 때 커넥션을 기다리는 시간. 기본값 45초는 이 API의 응답 제한(3초)에
     * 비해 지나치게 길어, 장애 시 요청 스레드가 오래 묶인다.
     */
    private static final Duration PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(5);

    /** 상대가 조용히 끊은 커넥션을 재사용해 실패하는 것을 막는다. */
    private static final Duration MAX_IDLE_TIME = Duration.ofSeconds(30);
    private static final Duration MAX_LIFE_TIME = Duration.ofMinutes(5);

    @Value("${kakao.api-key}")
    private String apiKey;

    @Value("${kakao.local.base-url}")
    private String kakaoBaseUrl;

    @Bean
    public WebClient kakaoWebClient() {
        return buildKakaoWebClient(kakaoBaseUrl, apiKey);
    }

    /**
     * 카카오용 WebClient 조립을 한 곳에 모은다.
     *
     * <p>벤치마크 하네스({@code AiHallucinationBaselineTest})가 Spring 컨텍스트 없이
     * WebClient를 직접 만들어 쓰는데, 그쪽이 이 메서드를 호출하지 않으면 프로덕션과
     * 타임아웃·풀 설정이 갈라져 측정이 실제 동작을 반영하지 못한다.
     */
    public static WebClient buildKakaoWebClient(String baseUrl, String apiKey) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("kakao")
            .maxConnections(MAX_CONNECTIONS)
            .pendingAcquireTimeout(PENDING_ACQUIRE_TIMEOUT)
            .maxIdleTime(MAX_IDLE_TIME)
            .maxLifeTime(MAX_LIFE_TIME)
            .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            .responseTimeout(RESPONSE_TIMEOUT);

        return WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "KakaoAK " + apiKey)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
