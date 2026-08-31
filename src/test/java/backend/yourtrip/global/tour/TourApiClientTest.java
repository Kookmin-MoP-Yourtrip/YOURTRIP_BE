package backend.yourtrip.global.tour;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.tour.config.TourApiConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link TourApiClient} WireMock 스텁 테스트 (ROADMAP 4-7 · 4-6).
 *
 * <p>{@code NaverLocalClientTest}와 같은 패턴이고, <b>스텁 본문은 4-7 실호출 응답을 축약한 것</b>이다.
 * 판정 11에서 배운 대로 스텁이 실제와 다르면 테스트가 전부 통과하면서 실호출만 죽으므로,
 * {@code Content-Type}과 0건 응답 형태를 실제 값 그대로 쓴다.
 */
@DisplayName("TourApiClient — 위치 기반 관광 조회 (ROADMAP 4-7)")
class TourApiClientTest {

    private static final String PATH = "/locationBasedList2";

    private static final double LATITUDE = 35.8347;
    private static final double LONGITUDE = 129.2094;

    private WireMockServer wireMock;
    private TourApiClient tourApiClient;

    @BeforeEach
    void startStubServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        tourApiClient = new TourApiClient(
            TourApiConfig.buildTourApiWebClient(wireMock.baseUrl()), "test-service-key");
    }

    @AfterEach
    void stopStubServer() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Nested
    @DisplayName("정상 응답")
    class Success {

        @Test
        @DisplayName("항목을 정규화해 거리순 그대로 돌려준다")
        void mapsItems() {
            stubOk(items(
                item("1621397", "경주 내물왕릉", "A02", "A0201", "A02010700",
                    "129.2095707739", "35.8362047083", "167.71118945537833"),
                item("126214", "천마총(대릉원)", "A02", "A0201", "A02010700",
                    "129.2104983997", "35.8386877792", "453.28615699847575")));

            TourApiResult result = tourApiClient.search(LATITUDE, LONGITUDE, 12, 50);

            assertThat(result).isInstanceOf(TourApiResult.Found.class);
            List<TourPlace> places = ((TourApiResult.Found) result).places();
            assertThat(places).hasSize(2);

            TourPlace first = places.get(0);
            assertThat(first.title()).isEqualTo("경주 내물왕릉");
            assertThat(first.cat3()).isEqualTo("A02010700");
            assertThat(first.distanceMeters()).isEqualTo(167.71118945537833);
            assertThat(first.address()).isEqualTo("경상북도 경주시 포석로 1065");
        }

        @Test
        @DisplayName("좌표는 평문 십진 도를 그대로 쓴다 — 네이버처럼 1e7로 나누면 안 된다")
        void keepsPlainDecimalCoordinates() {
            stubOk(items(item("1", "테스트", "A02", "A0201", "A02010700",
                "129.2095707739", "35.8362047083", "10.0")));

            TourPlace place = ((TourApiResult.Found)
                tourApiClient.search(LATITUDE, LONGITUDE, 12, 10)).places().get(0);

            assertThat(place.longitude()).isEqualTo(129.2095707739);
            assertThat(place.latitude()).isEqualTo(35.8362047083);
        }

        @Test
        @DisplayName("좌표를 못 읽어도 후보 자체는 살린다")
        void keepsPlaceWithBrokenCoordinates() {
            stubOk(items(item("1", "좌표없음", "A02", "A0201", "A02010700", "", "-", "10.0")));

            TourPlace place = ((TourApiResult.Found)
                tourApiClient.search(LATITUDE, LONGITUDE, 12, 10)).places().get(0);

            assertThat(place.hasCoordinates()).isFalse();
            assertThat(place.title()).isEqualTo("좌표없음");
        }
    }

    @Nested
    @DisplayName("0건 — 실패와 갈라야 한다")
    class EmptyResult {

        @Test
        @DisplayName("items가 빈 문자열로 와도 Empty다 — 이걸 놓치면 없는 지역이 장애로 보인다")
        void treatsEmptyStringItemsAsEmpty() {
            // 4-7 실호출에서 받은 실제 0건 응답이다. 객체가 아니라 빈 문자열이 온다.
            stubOk("{\"response\":{\"header\":{\"resultCode\":\"0000\",\"resultMsg\":\"OK\"},"
                + "\"body\":{\"items\":\"\",\"numOfRows\":0,\"pageNo\":1,\"totalCount\":0}}}");

            assertThat(tourApiClient.search(LATITUDE, LONGITUDE, 12, 50))
                .isInstanceOf(TourApiResult.Empty.class);
        }

        @Test
        @DisplayName("item 배열이 비어도 Empty다")
        void treatsEmptyArrayAsEmpty() {
            stubOk(items());

            assertThat(tourApiClient.search(LATITUDE, LONGITUDE, 12, 50))
                .isInstanceOf(TourApiResult.Empty.class);
        }
    }

    @Nested
    @DisplayName("실패 — 두 채널을 모두 본다")
    class Failure {

        @Test
        @DisplayName("HTTP 200인데 resultCode가 실패면 Failed다 — 상태코드만 보면 성공으로 읽는다")
        void detectsFailureInBody() {
            stubOk("{\"response\":{\"header\":{\"resultCode\":\"22\","
                + "\"resultMsg\":\"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR\"},"
                + "\"body\":{\"items\":\"\",\"numOfRows\":0,\"pageNo\":1,\"totalCount\":0}}}");

            assertThat(tourApiClient.search(LATITUDE, LONGITUDE, 12, 50))
                .isInstanceOfSatisfying(TourApiResult.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.QUOTA_EXCEEDED));
        }

        @Test
        @DisplayName("키가 틀리면 403 + 본문 오류로 온다 — 4-7 실호출에서 받은 그대로다")
        void classifiesUnregisteredServiceKey() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse()
                    .withStatus(403)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"OpenAPI_ServiceResponse\":{\"cmmMsgHeader\":{"
                        + "\"errMsg\":\"SERVICE_KEY_IS_NOT_REGISTERED_ERROR\","
                        + "\"returnAuthMsg\":\"등록되지 않은 서비스키\","
                        + "\"returnReasonCode\":\"30\"}}}")));

            assertThat(tourApiClient.search(LATITUDE, LONGITUDE, 12, 50))
                .isInstanceOfSatisfying(TourApiResult.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.UNAUTHORIZED));
        }

        @Test
        @DisplayName("403이라도 본문이 한도 초과면 QUOTA_EXCEEDED다 — 사유가 뭉치면 원인을 되짚어야 한다")
        void prefersQuotaCauseOverStatusWhenBodySaysSo() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse()
                    .withStatus(403)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"OpenAPI_ServiceResponse\":{\"cmmMsgHeader\":{"
                        + "\"errMsg\":\"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR\","
                        + "\"returnReasonCode\":\"22\"}}}")));

            assertThat(tourApiClient.search(LATITUDE, LONGITUDE, 12, 50))
                .isInstanceOfSatisfying(TourApiResult.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.QUOTA_EXCEEDED));
        }

        @Test
        @DisplayName("5xx는 HTTP_ERROR다")
        void classifiesServerError() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(500)));

            assertThat(tourApiClient.search(LATITUDE, LONGITUDE, 12, 50))
                .isInstanceOfSatisfying(TourApiResult.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.HTTP_ERROR));
        }

        @Test
        @DisplayName("본문이 스키마와 다르면 MALFORMED다")
        void classifiesMalformedBody() {
            stubOk("{\"response\":{\"body\":{\"items\":{\"item\":\"배열이 아니다\"}}}}");

            assertThat(tourApiClient.search(LATITUDE, LONGITUDE, 12, 50))
                .isInstanceOfSatisfying(TourApiResult.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.MALFORMED));
        }

        @Test
        @DisplayName("응답이 지연되면 TRANSPORT_ERROR다 — 예외가 아니라 값으로 온다")
        void classifiesTimeout() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withFixedDelay(6_000)
                    .withBody(items())));

            assertThat(tourApiClient.search(LATITUDE, LONGITUDE, 12, 50))
                .isInstanceOfSatisfying(TourApiResult.Failed.class, failed ->
                    assertThat(failed.cause()).isEqualTo(ApiFailureCause.TRANSPORT_ERROR));
        }
    }

    @Nested
    @DisplayName("요청 파라미터")
    class RequestParameters {

        @Test
        @DisplayName("설계가 정한 값이 그대로 나간다 — 반경은 고정이고 정렬은 거리순이다")
        void sendsDesignedParameters() {
            stubOk(items());

            tourApiClient.search(LATITUDE, LONGITUDE, 14, 50);

            LoggedRequest request = wireMock.findAll(getRequestedFor(urlPathEqualTo(PATH))).get(0);
            assertThat(request.queryParameter("radius").firstValue()).isEqualTo("20000");
            assertThat(request.queryParameter("arrange").firstValue()).isEqualTo("E");
            assertThat(request.queryParameter("contentTypeId").firstValue()).isEqualTo("14");
            assertThat(request.queryParameter("_type").firstValue()).isEqualTo("json");
            assertThat(request.queryParameter("mapX").firstValue())
                .as("mapX는 경도다 — 뒤바뀌면 엉뚱한 권역을 조회한다")
                .isEqualTo(String.valueOf(LONGITUDE));
            assertThat(request.queryParameter("mapY").firstValue()).isEqualTo(
                String.valueOf(LATITUDE));
        }

        @Test
        @DisplayName("numOfRows는 상한 50으로 잘린다 — 이 cap이 실질 필터다")
        void capsRows() {
            stubOk(items());

            tourApiClient.search(LATITUDE, LONGITUDE, 12, 500);

            LoggedRequest request = wireMock.findAll(getRequestedFor(urlPathEqualTo(PATH))).get(0);
            assertThat(request.queryParameter("numOfRows").firstValue()).isEqualTo("50");
        }

        @Test
        @DisplayName("이미 인코딩된 serviceKey를 다시 인코딩하지 않는다 — 실호출을 통째로 죽이던 함정이다")
        void doesNotDoubleEncodeServiceKey() {
            TourApiClient client = new TourApiClient(
                TourApiConfig.buildTourApiWebClient(wireMock.baseUrl()), "abc%2Bdef%3D");
            stubOk(items());

            client.search(LATITUDE, LONGITUDE, 12, 10);

            LoggedRequest request = wireMock.findAll(getRequestedFor(urlPathEqualTo(PATH))).get(0);
            assertThat(request.getUrl())
                .as("%%25가 보이면 이중 인코딩이다")
                .contains("serviceKey=abc%2Bdef%3D")
                .doesNotContain("%25");
        }
    }

    // ── 스텁 헬퍼 ─────────────────────────────────────────────────────────────

    private void stubOk(String body) {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
            .willReturn(aResponse()
                .withStatus(200)
                // 4-7 실호출에서 확인한 값. 네이버와 달리 text/plain 이 아니다.
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private static String items(String... itemsJson) {
        return "{\"response\":{\"header\":{\"resultCode\":\"0000\",\"resultMsg\":\"OK\"},"
            + "\"body\":{\"items\":{\"item\":[" + String.join(",", itemsJson) + "]},"
            + "\"numOfRows\":" + itemsJson.length + ",\"pageNo\":1,"
            + "\"totalCount\":" + itemsJson.length + "}}}";
    }

    private static String item(String contentId, String title, String cat1, String cat2,
        String cat3, String mapx, String mapy, String dist) {
        return """
            {
              "contentid": "%s",
              "contenttypeid": "12",
              "title": "%s",
              "addr1": "경상북도 경주시 포석로 1065",
              "addr2": "",
              "cat1": "%s",
              "cat2": "%s",
              "cat3": "%s",
              "mapx": "%s",
              "mapy": "%s",
              "dist": "%s",
              "firstimage": "",
              "firstimage2": "",
              "tel": ""
            }
            """.formatted(contentId, title, cat1, cat2, cat3, mapx, mapy, dist);
    }
}
