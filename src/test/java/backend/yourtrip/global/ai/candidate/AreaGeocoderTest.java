package backend.yourtrip.global.ai.candidate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link AreaGeocoder} 캐스케이드 테스트 (ROADMAP 4-8).
 *
 * <p><b>Mockito 대신 WireMock을 쓴다.</b> 이 클래스가 검증하려는 계약은 "무결과면 다음 단계,
 * 실패면 중단"인데 그 판단의 재료가 <b>HTTP 응답</b>이라, 클라이언트를 모킹하면 정작 응답 →
 * {@code PlaceLookup} 변환이 검증에서 빠진다. {@code KakaoLocalClientTest}가 세운 패턴을 그대로
 * 따르고, WebClient도 {@link KakaoConfig#buildKakaoWebClient}로 프로덕션과 같게 조립한다.
 *
 * <p><b>호출 횟수를 단언하는 테스트가 여럿인 것이 의도다.</b> 캐스케이드의 핵심은 "무엇을
 * 돌려주는가"만이 아니라 <b>"몇 번 물어보는가"</b>다 — 실패했는데 두 번 더 두드리면 쿼터만 쓴다.
 */
@DisplayName("AreaGeocoder — anchor → area → location 캐스케이드 (ROADMAP 4-8)")
class AreaGeocoderTest {

    private static final String SEARCH_PATH = "/v2/local/search/keyword.json";

    private static final String LOCATION = "경주시";
    private static final String AREA = "황리단길·대릉원 일대";
    private static final String ANCHOR = "대릉원";

    private static final String ANCHOR_QUERY = LOCATION + " " + ANCHOR;
    private static final String AREA_QUERY = LOCATION + " " + AREA;

    private WireMockServer wireMock;
    private AreaGeocoder areaGeocoder;

    @BeforeEach
    void startStubServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        areaGeocoder = new AreaGeocoder(new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient(wireMock.baseUrl(), "test-kakao-api-key")));
        // 어떤 쿼리든 기본은 0건이다. 개별 테스트가 필요한 쿼리만 덮어쓴다
        // (WireMock은 우선순위 숫자가 작을수록 먼저 매칭한다).
        wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).atPriority(10)
            .willReturn(okJson(body())));
    }

    @AfterEach
    void stopStubServer() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Nested
    @DisplayName("정상 경로 — 앞 단계가 이기면 뒤는 부르지 않는다")
    class Cascade {

        @Test
        @DisplayName("anchor로 찾으면 HIT이고 그것으로 끝난다")
        void resolvesByAnchor() {
            stubQuery(ANCHOR_QUERY, document("대릉원", "128.9", "35.83"));

            GeocodeResult result = areaGeocoder.geocode(LOCATION, AREA, ANCHOR);

            assertThat(result.outcome()).isEqualTo(GeocodeOutcome.HIT);
            assertThat(result.latitude()).isEqualTo(35.83);
            assertThat(result.longitude()).isEqualTo(128.9);
            wireMock.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        }

        @Test
        @DisplayName("anchor가 무결과면 area로 넘어간다")
        void fallsBackToArea() {
            stubQuery(AREA_QUERY, document("황리단길", "129.21", "35.83"));

            GeocodeResult result = areaGeocoder.geocode(LOCATION, AREA, ANCHOR);

            assertThat(result.outcome()).isEqualTo(GeocodeOutcome.FALLBACK_AREA);
            assertThat(result.hasCoordinate()).isTrue();
            wireMock.verify(2, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        }

        @Test
        @DisplayName("area까지 무결과면 여행지 이름으로 넘어간다")
        void fallsBackToLocation() {
            stubQuery(LOCATION, document("경주시청", "129.22", "35.85"));

            GeocodeResult result = areaGeocoder.geocode(LOCATION, AREA, ANCHOR);

            assertThat(result.outcome()).isEqualTo(GeocodeOutcome.FALLBACK_LOCATION);
            assertThat(result.hasCoordinate()).isTrue();
            wireMock.verify(3, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        }

        @Test
        @DisplayName("셋 다 무결과면 NO_RESULT다 — 그 day의 TourAPI를 건너뛴다")
        void noResultWhenEveryStepIsEmpty() {
            GeocodeResult result = areaGeocoder.geocode(LOCATION, AREA, ANCHOR);

            assertThat(result.outcome()).isEqualTo(GeocodeOutcome.NO_RESULT);
            assertThat(result.hasCoordinate()).isFalse();
            wireMock.verify(3, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        }
    }

    @Nested
    @DisplayName("실패는 중단이다 — 이 계약이 4-8의 핵심이다")
    class FailureStopsCascade {

        @Test
        @DisplayName("첫 호출이 실패하면 FAILED이고 더 물어보지 않는다")
        void stopsImmediatelyOnFailure() {
            wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).atPriority(1)
                .withQueryParam("query", equalTo(ANCHOR_QUERY))
                .willReturn(aResponse().withStatus(500)));
            // area·location 쿼리는 성공하도록 두었는데도 호출되지 않아야 한다.
            stubQuery(AREA_QUERY, document("황리단길", "129.21", "35.83"));
            stubQuery(LOCATION, document("경주시청", "129.22", "35.85"));

            GeocodeResult result = areaGeocoder.geocode(LOCATION, AREA, ANCHOR);

            assertThat(result.outcome()).isEqualTo(GeocodeOutcome.FAILED);
            wireMock.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        }

        @Test
        @DisplayName("두 번째 단계에서 실패해도 세 번째로 넘어가지 않는다")
        void stopsAtWhicheverStepFails() {
            wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).atPriority(1)
                .withQueryParam("query", equalTo(AREA_QUERY))
                .willReturn(aResponse().withStatus(429)));
            stubQuery(LOCATION, document("경주시청", "129.22", "35.85"));

            GeocodeResult result = areaGeocoder.geocode(LOCATION, AREA, ANCHOR);

            assertThat(result.outcome()).isEqualTo(GeocodeOutcome.FAILED);
            wireMock.verify(2, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        }
    }

    @Nested
    @DisplayName("물어볼 말이 없으면 부르지 않는다")
    class SkipsEmptyQueries {

        @Test
        @DisplayName("anchor가 비면 그 단계는 호출 없이 건너뛴다 — 6-3이 만드는 상태다")
        void skipsBlankAnchorWithoutCalling() {
            stubQuery(AREA_QUERY, document("황리단길", "129.21", "35.83"));

            GeocodeResult result = areaGeocoder.geocode(LOCATION, AREA, null);

            assertThat(result.outcome()).isEqualTo(GeocodeOutcome.FALLBACK_AREA);
            wireMock.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        }

        @Test
        @DisplayName("여행지가 비면 한 번도 부르지 않는다 — 접두사가 없으면 캐스케이드 전체가 무의미하다")
        void skipsEverythingWhenLocationIsBlank() {
            GeocodeResult result = areaGeocoder.geocode("   ", AREA, ANCHOR);

            assertThat(result.outcome()).isEqualTo(GeocodeOutcome.NO_RESULT);
            wireMock.verify(0, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        }
    }

    @Nested
    @DisplayName("이름 게이트 — LLM이 지어낸 이름에만 건다")
    class NameGate {

        @Test
        @DisplayName("anchor 이름과 전혀 다른 장소가 오면 채택하지 않고 다음 단계로 간다")
        void rejectsHallucinatedAnchor() {
            // Planner가 없는 랜드마크를 지어냈고 카카오가 엉뚱한 곳을 1등으로 줬다.
            // 게이트가 없으면 이 좌표가 그대로 권역 중심이 된다.
            stubQuery(ANCHOR_QUERY, document("전혀다른가게", "127.0", "37.5"));
            stubQuery(AREA_QUERY, document("황리단길", "129.21", "35.83"));

            GeocodeResult result = areaGeocoder.geocode(LOCATION, AREA, ANCHOR);

            assertThat(result.outcome()).isEqualTo(GeocodeOutcome.FALLBACK_AREA);
            assertThat(result.longitude()).isEqualTo(129.21);
        }

        @Test
        @DisplayName("마지막 단계는 게이트를 걸지 않는다 — 사용자가 준 지역명에는 막을 환각이 없다")
        void lastResortIsNotGated() {
            // "순천시"와 "순천만국가정원"은 서로를 포함하지 않아 게이트를 걸면 탈락한다.
            // 그러면 TourAPI가 가장 필요한 무인지 지역에서 마지막 안전망이 먼저 끊어진다.
            stubQuery("순천시", document("순천만국가정원", "127.50", "34.94"));

            GeocodeResult result = areaGeocoder.geocode("순천시", null, null);

            assertThat(result.outcome()).isEqualTo(GeocodeOutcome.FALLBACK_LOCATION);
            assertThat(result.latitude()).isEqualTo(34.94);
            wireMock.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        }
    }

    @Nested
    @DisplayName("좌표를 못 읽으면 다음 단계로 — 중단할 일은 아니다")
    class BrokenCoordinates {

        @Test
        @DisplayName("좌표가 숫자가 아니면 그 후보를 버리고 다음 단계로 간다")
        void movesOnWhenCoordinateIsNotParsable() {
            stubQuery(ANCHOR_QUERY, document("대릉원", "", "notanumber"));
            stubQuery(AREA_QUERY, document("황리단길", "129.21", "35.83"));

            GeocodeResult result = areaGeocoder.geocode(LOCATION, AREA, ANCHOR);

            assertThat(result.outcome())
                .as("카카오는 멀쩡하므로 FAILED가 아니다")
                .isEqualTo(GeocodeOutcome.FALLBACK_AREA);
        }
    }

    // ── 스텁 헬퍼 ──────────────────────────────────────────────────────────────

    private void stubQuery(String query, String... documentsJson) {
        wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).atPriority(1)
            .withQueryParam("query", equalTo(query))
            .willReturn(okJson(body(documentsJson))));
    }

    private static ResponseDefinitionBuilder okJson(String responseBody) {
        return aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(responseBody);
    }

    private static String body(String... documentsJson) {
        return "{\"documents\":[" + String.join(",", documentsJson)
            + "],\"meta\":{\"total_count\":" + documentsJson.length + "}}";
    }

    private static String document(String placeName, String x, String y) {
        return """
            {
              "place_name": "%s",
              "road_address_name": "경북 경주시 계림로 9",
              "address_name": "경북 경주시 황남동 31-1",
              "category_group_code": "AT4",
              "category_name": "여행 > 관광,명소",
              "place_url": "http://place.map.kakao.com/1",
              "x": "%s",
              "y": "%s"
            }
            """.formatted(placeName, x, y);
    }
}
