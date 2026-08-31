package backend.yourtrip.global.kakao;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.common.ApiFailureCause;
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

            // 실측에서 이 유형(주소는 맞고 이름만 표기가 다른 경우)의 표본 18건이 전부
            // 정답이었다. 정규화 전에는 contains가 거짓 음성을 내 3점에 머물렀다.
            assertThat(kakaoLocalClient.lookupBestPlace("동궁과 월지", "경주"))
                .isInstanceOfSatisfying(PlaceLookup.Found.class, found ->
                    assertThat(found.document().place_name()).isEqualTo("동궁과월지"));
        }

        @Test
        @DisplayName("이름이 전혀 다르면 주소·카테고리가 맞아도 매칭 실패로 처리한다")
        void rejectsWhenNameDoesNotMatchEvenIfAddressAndCategoryDo() {
            // 주소(+3)와 카테고리(+2)로 5점이 나오지만 이름이 하나도 맞지 않는다.
            // 하한선만 있고 이름 게이트가 없으면 이 후보가 그대로 저장됐다.
            stubDocuments(document("개미집 국제시장본점직영점", "부산 해운대구 구남로 34", "FD6"));

            // NameMismatch 로 받는 것이 요지다 — "우리가 걸렀다"와 "카카오에 없다"는 다른 사건이고,
            // 옛 계약의 null 은 그 둘을 같은 값으로 뭉갰다.
            assertThat(kakaoLocalClient.lookupBestPlace("해운대 시장", "부산"))
                .isInstanceOf(PlaceLookup.NameMismatch.class);
        }

        @Test
        @DisplayName("이름이 맞는 후보가 최고점이 아니어도 그 후보를 고른다")
        void picksNameMatchedCandidateOverHigherScoringMismatch() {
            // 첫 후보는 주소+카테고리로 5점이지만 이름 불일치, 둘째는 이름이 맞는다.
            // 필터가 max()보다 뒤에 있으면 첫 후보가 뽑힌다.
            stubDocuments(
                document("전혀다른가게", "경북 경주시 첨성로 1", "FD6"),
                document("황리단길커피", "경북 경주시 포석로 2", null));

            assertThat(kakaoLocalClient.lookupBestPlace("황리단길커피", "경주"))
                .isInstanceOfSatisfying(PlaceLookup.Found.class, found ->
                    assertThat(found.document().place_name()).isEqualTo("황리단길커피"));
        }

        @Test
        @DisplayName("AI가 준 장소명이 비어 있으면 어떤 후보도 통과시키지 않는다")
        void rejectsBlankPlaceName() {
            stubDocuments(document("아무가게", "경북 경주시 원화로 102", "FD6"));

            // 지역 접두사가 붙어 키워드 자체는 비지 않으므로 호출은 나가고, 이름 게이트가
            // 전멸시킨다 — 빈 이름은 정규화하면 빈 문자열이라 무엇과도 같지 않다.
            assertThat(kakaoLocalClient.lookupBestPlace("  ", "경주"))
                .isInstanceOf(PlaceLookup.NameMismatch.class);
        }
    }

    @Nested
    @DisplayName("결과를 값으로 돌려주는 경로 (ROADMAP 4-8)")
    class ValueReturningLookup {

        @Test
        @DisplayName("카카오에 아무것도 없으면 NoResult다 — 실패와 뭉치면 캐스케이드가 헛돈다")
        void returnsNoResultWhenKakaoHasNothing() {
            stubDocuments();

            assertThat(kakaoLocalClient.lookupBestPlace("있을리없는가게이름", "경주"))
                .isInstanceOf(PlaceLookup.NoResult.class);
        }

        @Test
        @DisplayName("결과는 있는데 이름이 안 맞으면 NameMismatch다 — 세탁 위험 구간 (ROADMAP 5-2)")
        void returnsNameMismatchWhenGateRejectsAll() {
            // 배경이 비판한 실수가 정확히 이 구간이다 — 하한선 없는 score()가 "그 지역의 무관한
            // POI"를 최고점으로 뽑아 환각을 실존 장소로 세탁했다. 1-2가 이름 게이트로 막았고,
            // 이제 그 게이트가 몇 번 발동했는지를 no_result 와 갈라 세야 한다(5-6).
            stubDocuments(document("전혀다른가게", "경북 경주시 첨성로 1", "FD6"));

            assertThat(kakaoLocalClient.lookupBestPlace("있을리없는가게이름", "경주"))
                .isInstanceOfSatisfying(PlaceLookup.NameMismatch.class, mismatch ->
                    assertThat(mismatch.bestCandidateName()).isEqualTo("전혀다른가게"));
        }

        @Test
        @DisplayName("이름 게이트를 걸지 않는 조회는 NameMismatch를 낼 수 없다 — 4-8 판정 14")
        void firstPlaceLookupNeverMismatches() {
            // 캐스케이드 마지막 단계는 사용자가 입력한 지역명이라 막을 환각이 없다.
            stubDocuments(document("전혀다른가게", "경북 경주시 첨성로 1", "FD6"));

            assertThat(kakaoLocalClient.lookupFirstPlace("경주"))
                .isInstanceOf(PlaceLookup.Found.class);
        }

        @Test
        @DisplayName("응답이 지연되면 원시 예외가 아니라 Failed(TRANSPORT_ERROR)가 값으로 온다")
        void classifiesTimeoutAsTransportError() {
            // KakaoConfig의 responseTimeout(3초)을 넘기도록 지연시킨다.
            // 예전에는 .block(20초)이 IllegalStateException을 던져
            // WebClientResponseException catch를 빠져나가 원시 500이 됐다.
            wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withFixedDelay(6_000)
                    .withBody("{\"documents\":[],\"meta\":{\"total_count\":0}}")));

            assertThat(kakaoLocalClient.lookupBestPlace("불국사", "경주"))
                .isInstanceOfSatisfying(PlaceLookup.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.TRANSPORT_ERROR));
        }

        @Test
        @DisplayName("카카오가 5xx를 주면 Failed(HTTP_ERROR)다")
        void classifiesServerErrorAsHttpError() {
            wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(500)));

            assertThat(kakaoLocalClient.lookupBestPlace("불국사", "경주"))
                .isInstanceOfSatisfying(PlaceLookup.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.HTTP_ERROR));
        }

        @Test
        @DisplayName("429는 QUOTA_EXCEEDED로 갈라 둔다 — 시간이 지나야 풀리는 유일한 실패다")
        void classifiesQuotaExceeded() {
            wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(429)));

            assertThat(kakaoLocalClient.lookupBestPlace("불국사", "경주"))
                .isInstanceOfSatisfying(PlaceLookup.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.QUOTA_EXCEEDED));
        }

        @Test
        @DisplayName("401은 UNAUTHORIZED다")
        void classifiesUnauthorized() {
            wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(401)));

            assertThat(kakaoLocalClient.lookupBestPlace("불국사", "경주"))
                .isInstanceOfSatisfying(PlaceLookup.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.UNAUTHORIZED));
        }

        @Test
        @DisplayName("본문이 스키마와 다르면 MALFORMED다 — 예전에는 이것만 원시 500으로 샜다")
        void classifiesMalformedBody() {
            wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"documents\": \"배열이 아니다\"}")));

            assertThat(kakaoLocalClient.lookupBestPlace("불국사", "경주"))
                .isInstanceOfSatisfying(PlaceLookup.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.MALFORMED));
        }

        @Test
        @DisplayName("lookupFirstPlace는 이름을 보지 않고 1등을 준다 — 지오코딩 마지막 단계용이다")
        void firstPlaceIgnoresNameGate() {
            stubDocuments(
                document("순천만국가정원", "전남 순천시 국가정원1호길 47", "AT4"),
                document("순천시청", "전남 순천시 장명로 30", null));

            assertThat(kakaoLocalClient.lookupFirstPlace("순천시"))
                .isInstanceOfSatisfying(PlaceLookup.Found.class, found ->
                    assertThat(found.document().place_name()).isEqualTo("순천만국가정원"));
        }

        @Test
        @DisplayName("검색어가 비면 호출 없이 NoResult다")
        void doesNotCallForBlankKeyword() {
            assertThat(kakaoLocalClient.lookupFirstPlace("   "))
                .isInstanceOf(PlaceLookup.NoResult.class);
            wireMock.verify(0, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
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
