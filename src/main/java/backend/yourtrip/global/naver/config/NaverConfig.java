package backend.yourtrip.global.naver.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * 네이버 지역검색(NAVER API HUB)용 {@link WebClient} 조립.
 *
 * <p><b>타임아웃·풀 값은 카카오({@code KakaoConfig})와 같게 뒀다.</b> 감이 아니라 4-2 실측 근거가
 * 있다 — 지역검색 지연은 단건 57ms, 9회 평균 109ms, 최대 134ms였다. 응답 제한 3초는 정상 응답의
 * 20배가 넘는 여유이므로, 이 값을 넘긴다면 기다릴 이유가 없는 상황이다.
 *
 * <p>후보 공급은 fail-open이라 <b>키가 비어 있어도 기동은 성공해야 한다.</b> 그래서 키를 필수
 * 프로퍼티로 두지 않고 빈 기본값을 허용한다({@code llm.openai.api-key}가 세운 관례) — 키가 없으면
 * 호출이 401로 실패하고 {@code NaverLocalClient}가 그것을 값으로 돌려주어 시드 후보만 빠진다.
 */
@Configuration
@RequiredArgsConstructor
public class NaverConfig {

    /** TCP 연결 수립 제한. 국내 리전이라 정상 연결은 수십 ms면 끝난다. */
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    /** 요청 전송 후 응답 수신까지의 제한. 4-2 실측 최대 134ms의 약 22배 여유. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 커넥션 풀 크기. reactor-netty 기본값은 머신의 가용 프로세서 수에 좌우되므로 명시한다.
     * 코스 1건이 슬롯 × (기본 + 스타일) 만큼 호출하고(설계 기준 18~30회) 5단계에서 병렬로
     * 돌리므로, 동시 요청 수만큼의 커넥션이 필요하다.
     */
    private static final int MAX_CONNECTIONS = 50;

    private static final Duration PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_IDLE_TIME = Duration.ofSeconds(30);
    private static final Duration MAX_LIFE_TIME = Duration.ofMinutes(5);

    @Value("${naver.local.base-url}")
    private String naverBaseUrl;

    @Value("${naver.client-id:}")
    private String clientId;

    @Value("${naver.client-secret:}")
    private String clientSecret;

    @Bean
    public WebClient naverWebClient() {
        return buildNaverWebClient(naverBaseUrl, clientId, clientSecret);
    }

    /**
     * 네이버용 WebClient 조립을 한 곳에 모은다.
     *
     * <p>WireMock 테스트와 실호출 프로브가 Spring 컨텍스트 없이 클라이언트를 만들어 쓰는데,
     * 그쪽이 이 메서드를 호출하지 않으면 프로덕션과 타임아웃·풀 설정이 갈라져 검증이 실제 동작을
     * 반영하지 못한다({@code KakaoConfig.buildKakaoWebClient}가 만든 선례).
     */
    public static WebClient buildNaverWebClient(String baseUrl, String clientId,
        String clientSecret) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("naver")
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
            // 구 방식(X-Naver-Client-Id / X-Naver-Client-Secret)이 아니다. API HUB 이관으로 바뀌었고,
            // 레거시 헤더로 호출하면 401 "Not Exist Client ID" 가 돌아온다(4-2 실측).
            .defaultHeader("X-NCP-APIGW-API-KEY-ID", clientId)
            .defaultHeader("X-NCP-APIGW-API-KEY", clientSecret)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            // 네이버는 JSON을 text/plain 으로 돌려준다(4-3 보강 측정에서 확인). 기본 Jackson
            // 디코더는 application/json 만 받으므로, 그대로 두면 200 응답인데도 역직렬화가
            // 거부되어 모든 호출이 실패로 떨어진다. ObjectMapper 를 직접 만드는 이유는 이
            // 팩토리가 Spring 컨텍스트 없이도 호출되기 때문이고(WireMock 테스트·실호출 프로브),
            // 지역검색 응답은 전부 문자열·정수라 별도 모듈이 필요 없다.
            .codecs(configurer -> configurer.defaultCodecs()
                .jackson2JsonDecoder(new Jackson2JsonDecoder(
                    new ObjectMapper(), MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN)))
            .build();
    }
}
