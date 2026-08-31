package backend.yourtrip.global.tour.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.DefaultUriBuilderFactory.EncodingMode;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * 한국관광공사 TourAPI용 {@link WebClient} 조립 (ROADMAP 4-7).
 *
 * <p>타임아웃·풀 값은 {@code NaverConfig}·{@code KakaoConfig}와 같게 뒀다. 4-7 실호출에서 잰
 * 지연은 118ms로 네이버(57~134ms)와 같은 대역이라 값을 달리할 근거가 없다.
 *
 * <p>후보 공급은 fail-open이라 <b>키가 비어 있어도 기동은 성공해야 한다</b>({@code naver.client-id}가
 * 세운 관례). 키가 없으면 호출이 403으로 실패하고 {@code TourApiClient}가 그것을 값으로 돌려주어
 * 관광 슬롯의 LISTED 후보만 빠진다.
 *
 * <h2>이 설정에는 다른 두 클라이언트에 없는 함정이 둘 있다</h2>
 * <ol>
 *   <li><b>{@code serviceKey} 이중 인코딩</b> — {@link #noEncodingUriBuilderFactory}</li>
 *   <li><b>0건일 때 {@code "items": ""}</b> — {@link #tourObjectMapper()}</li>
 * </ol>
 * 둘 다 4-7 실호출로 확인했고, 둘 다 <b>막지 않으면 "결과 없음"이 "호출 실패"로 둔갑한다.</b>
 */
@Configuration
@RequiredArgsConstructor
public class TourApiConfig {

    private static final int CONNECT_TIMEOUT_MS = 2_000;

    /** 4-7 실측 118ms의 약 25배 여유. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 커넥션 풀 크기. TourAPI는 코스당 최대 9회(고유 anchor ≤3 × contentTypeId 3)라 네이버보다
     * 훨씬 적게 부르지만, 풀을 따로 두는 것 자체가 목적이다 — 공유하면 네이버 호출이 풀을 다 쓴
     * 순간 관광 후보까지 함께 굶는다.
     */
    private static final int MAX_CONNECTIONS = 20;

    private static final Duration PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_IDLE_TIME = Duration.ofSeconds(30);
    private static final Duration MAX_LIFE_TIME = Duration.ofMinutes(5);

    /** 이미 퍼센트 인코딩된 문자열인지 판별한다. Encoding 키에는 {@code %2B}·{@code %3D}가 들어 있다. */
    private static final Pattern PERCENT_ENCODED = Pattern.compile(".*%[0-9A-Fa-f]{2}.*");

    @Value("${tour.base-url}")
    private String tourBaseUrl;

    @Value("${tour.service-key:}")
    private String serviceKey;

    @Bean
    public WebClient tourApiWebClient() {
        return buildTourApiWebClient(tourBaseUrl);
    }

    /**
     * 공공데이터포털이 Encoding / Decoding 두 형태로 발급하는 키를 <b>Encoding 형태로 통일</b>한다.
     *
     * <h2>왜 Decoding이 아니라 Encoding으로 맞추는가</h2>
     * 반대 방향(디코딩해서 넘기고 인코더에 맡기기)이 더 자연스러워 보이지만 <b>틀린다.</b> 디코딩된
     * 키에는 {@code +}가 들어 있는데 {@code +}는 쿼리 문자열에서 합법적인 문자라 인코더가 그대로
     * 통과시키고, 서버는 그것을 <b>공백으로 해석</b>한다. 키 한 글자가 조용히 바뀌어 인증이 깨진다.
     *
     * <p>그래서 이미 인코딩된 키는 손대지 않고, 평문 키만 한 번 인코딩한다. 어느 쪽을 발급받아
     * 넣든 같은 결과가 나오므로 <b>".env에 어느 형태를 넣었는가"가 장애 원인이 되지 않는다.</b>
     *
     * @return 인코딩된 키. 입력이 비어 있으면 빈 문자열(기동은 성공하고 호출만 실패한다)
     */
    public static String normalizeServiceKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return "";
        }
        String trimmed = rawKey.trim();
        if (PERCENT_ENCODED.matcher(trimmed).matches()) {
            return trimmed;
        }
        return URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
    }

    /**
     * TourAPI용 WebClient 조립을 한 곳에 모은다. WireMock 테스트와 실호출 프로브가 Spring 컨텍스트
     * 없이 프로덕션과 같은 설정을 쓰게 하기 위한 것으로, {@code KakaoConfig}·{@code NaverConfig}가
     * 세운 관례를 따른다.
     */
    public static WebClient buildTourApiWebClient(String baseUrl) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("tour")
            .maxConnections(MAX_CONNECTIONS)
            .pendingAcquireTimeout(PENDING_ACQUIRE_TIMEOUT)
            .maxIdleTime(MAX_IDLE_TIME)
            .maxLifeTime(MAX_LIFE_TIME)
            .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            .responseTimeout(RESPONSE_TIMEOUT);

        return WebClient.builder()
            .uriBuilderFactory(noEncodingUriBuilderFactory(baseUrl))
            .baseUrl(baseUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs()
                .jackson2JsonDecoder(new Jackson2JsonDecoder(tourObjectMapper())))
            .build();
    }

    /**
     * <b>URI를 더 이상 인코딩하지 않는 팩토리.</b>
     *
     * <p>{@code serviceKey}는 쿼리 파라미터이고 이미 퍼센트 인코딩돼 있다. 기본 인코딩 모드로
     * {@code queryParam("serviceKey", key)}에 넣으면 {@code %}가 {@code %25}로 한 번 더 인코딩돼
     * 서버가 다른 키로 읽는다 — 4-7 실호출에서 <b>같은 키로 raw URI는 {@code resultCode 0000},
     * {@code UriBuilder} 조립은 403 {@code SERVICE_KEY_IS_NOT_REGISTERED_ERROR}</b> 였다.
     *
     * <p>{@link EncodingMode#NONE}으로 두는 것이 안전한 이유는 <b>이 API에 보내는 값이 전부 숫자와
     * ASCII 상수</b>이기 때문이다(좌표·반경·{@code contentTypeId}·{@code arrange}). 한글이 들어가는
     * 파라미터({@code searchKeyword2} 등)를 쓰게 되는 날에는 호출부가 직접 인코딩해야 하므로,
     * 그때 이 주석을 근거로 판단한다.
     */
    private static DefaultUriBuilderFactory noEncodingUriBuilderFactory(String baseUrl) {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(baseUrl);
        factory.setEncodingMode(EncodingMode.NONE);
        return factory;
    }

    /**
     * 응답 형태가 흔들리는 두 지점을 흡수하고 알 수 없는 필드는 무시한다.
     *
     * <p>{@code ObjectMapper}를 직접 만드는 것은 이 팩토리가 Spring 컨텍스트 없이도 호출되기
     * 때문이다(WireMock 테스트·실호출 프로브). TourAPI 응답은 전부 문자열·정수라 별도 모듈이 필요 없다.
     */
    private static ObjectMapper tourObjectMapper() {
        return new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // 0건이면 items 가 객체가 아니라 빈 문자열로 온다. 이걸 켜지 않으면
            // MismatchedInputException 이 나 "관광지가 없는 지역"이 "호출 실패"로 둔갑한다.
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            // 1건일 때 배열이 아니라 객체로 올 가능성에 대비한다. 실측에서는 항상 배열이었지만
            // 이 옵션은 배열인 경우의 동작을 바꾸지 않으므로 켜 두는 쪽이 안전하다.
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

}
