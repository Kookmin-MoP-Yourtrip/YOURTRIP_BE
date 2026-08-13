package backend.yourtrip.global.kakao;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link KakaoLocalClient}의 매칭 게이트와 장애 처리 회귀 테스트.
 *
 * <p><b>Spring 컨텍스트를 띄우지 않는다.</b> {@code application-test.yml}이 DB·Redis는
 * 실제 인스턴스를 전제하고 있어 컨텍스트를 띄우면 인프라가 필요해지는데, 이 테스트가
 * 확인하려는 것은 클라이언트 한 개의 동작이라 그 비용이 정당화되지 않는다
 * ({@code SpringAiStructuredOutputVerificationTest}가 만든 선례).
 *
 * <p>대신 WebClient는 {@link KakaoConfig#buildKakaoWebClient}로 조립한다 — 프로덕션과
 * 같은 타임아웃·커넥션 풀 설정 위에서 검증해야 의미가 있기 때문이다.
 */
class KakaoLocalClientTest {

    private static final String SEARCH_PATH = "/v2/local/search/keyword.json";

    private WireMockServer wireMock;
    private KakaoLocalClient kakaoLocalClient;

    @BeforeEach
    void startStubServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        kakaoLocalClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient(wireMock.baseUrl(), "test-kakao-api-key"));
    }

    @AfterEach
    void stopStubServer() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Nested
    @DisplayName("이름 매칭 게이트")
    class NameGate {

        @Test
        @DisplayName("띄어쓰기·중점만 다른 이름은 같은 장소로 인정한다")
        void acceptsNamesDifferingOnlyBySpacingAndPunctuation() {
            stubDocuments(document("동궁과월지", "경북 경주시 원화로 102", "AT4"));

            Document best = kakaoLocalClient.findBestPlace("동궁과 월지", "경주");

            // 실측에서 이 유형(주소는 맞고 이름만 표기가 다른 경우)의 표본 18건이 전부
            // 정답이었다. 정규화 전에는 contains가 거짓 음성을 내 3점에 머물렀다.
            assertThat(best).isNotNull();
            assertThat(best.place_name()).isEqualTo("동궁과월지");
        }

        @Test
        @DisplayName("이름이 전혀 다르면 주소·카테고리가 맞아도 매칭 실패로 처리한다")
        void rejectsWhenNameDoesNotMatchEvenIfAddressAndCategoryDo() {
            // 주소(+3)와 카테고리(+2)로 5점이 나오지만 이름이 하나도 맞지 않는다.
            // 하한선만 있고 이름 게이트가 없으면 이 후보가 그대로 저장됐다.
            stubDocuments(document("개미집 국제시장본점직영점", "부산 해운대구 구남로 34", "FD6"));

            Document best = kakaoLocalClient.findBestPlace("해운대 시장", "부산");

            assertThat(best).isNull();
        }

        @Test
        @DisplayName("이름이 맞는 후보가 최고점이 아니어도 그 후보를 고른다")
        void picksNameMatchedCandidateOverHigherScoringMismatch() {
            // 첫 후보는 주소+카테고리로 5점이지만 이름 불일치, 둘째는 이름이 맞는다.
            // 필터가 max()보다 뒤에 있으면 첫 후보가 뽑힌다.
            stubDocuments(
                document("전혀다른가게", "경북 경주시 첨성로 1", "FD6"),
                document("황리단길커피", "경북 경주시 포석로 2", null));

            Document best = kakaoLocalClient.findBestPlace("황리단길커피", "경주");

            assertThat(best).isNotNull();
            assertThat(best.place_name()).isEqualTo("황리단길커피");
        }

        @Test
        @DisplayName("검색 결과가 없으면 null을 반환한다")
        void returnsNullWhenNoDocuments() {
            stubDocuments();

            assertThat(kakaoLocalClient.findBestPlace("있을리없는가게이름", "경주")).isNull();
        }

        @Test
        @DisplayName("AI가 준 장소명이 비어 있으면 매칭하지 않는다")
        void rejectsBlankPlaceName() {
            stubDocuments(document("아무가게", "경북 경주시 원화로 102", "FD6"));

            assertThat(kakaoLocalClient.findBestPlace("  ", "경주")).isNull();
        }
    }

    @Nested
    @DisplayName("장애 처리")
    class FailureHandling {

        @Test
        @DisplayName("응답이 지연되면 원시 예외가 아니라 KAKAO_API_FAILED로 변환된다")
        void convertsTimeoutToBusinessException() {
            // KakaoConfig의 responseTimeout(3초)을 넘기도록 지연시킨다.
            // 예전에는 .block(20초)이 IllegalStateException을 던져
            // WebClientResponseException catch를 빠져나가 원시 500이 됐다.
            wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withFixedDelay(6_000)
                    .withBody("{\"documents\":[],\"meta\":{\"total_count\":0}}")));

            assertThatThrownBy(() -> kakaoLocalClient.findBestPlace("불국사", "경주"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MyCourseErrorCode.KAKAO_API_FAILED);
        }

        @Test
        @DisplayName("카카오가 5xx를 주면 KAKAO_API_FAILED로 변환된다")
        void convertsServerErrorToBusinessException() {
            wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> kakaoLocalClient.findBestPlace("불국사", "경주"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MyCourseErrorCode.KAKAO_API_FAILED);
        }
    }

    // ── 스텁 헬퍼 ──────────────────────────────────────────────────────────────

    private void stubDocuments(String... documentsJson) {
        String body = "{\"documents\":[" + String.join(",", documentsJson)
            + "],\"meta\":{\"total_count\":" + documentsJson.length + "}}";

        wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private static String document(String placeName, String roadAddress, String categoryGroupCode) {
        return """
            {
              "place_name": "%s",
              "road_address_name": "%s",
              "address_name": "%s",
              "category_group_code": %s,
              "category_name": "테스트 > 카테고리",
              "place_url": "http://place.map.kakao.com/1",
              "x": "129.2094",
              "y": "35.8347"
            }
            """.formatted(placeName, roadAddress, roadAddress,
            categoryGroupCode == null ? "null" : "\"" + categoryGroupCode + "\"");
    }
}
