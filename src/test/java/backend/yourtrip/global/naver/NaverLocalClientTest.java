package backend.yourtrip.global.naver;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.naver.config.NaverConfig;
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
 * {@link NaverLocalClient} 스텁 테스트 (ROADMAP 4-1 / 4-6).
 *
 * <p>스텁 본문은 <b>4-2 실호출로 받은 실제 응답</b>을 축약한 것이다 — 손으로 지어낸 픽스처를 쓰면
 * 실제 형식과 갈라져 "테스트는 통과하는데 프로덕션은 깨지는" 상태가 된다.
 *
 * <p>{@code KakaoLocalClientTest}와 같은 패턴이다: Spring 컨텍스트를 띄우지 않고 프로덕션 팩토리
 * ({@link NaverConfig#buildNaverWebClient})에 WireMock의 동적 포트를 넘겨 조립한다.
 */
@DisplayName("NaverLocalClient — 지역검색 시더 (ROADMAP 4-1)")
class NaverLocalClientTest {

    private WireMockServer wireMock;
    private NaverLocalClient client;

    @BeforeEach
    void startStubServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new NaverLocalClient(
            NaverConfig.buildNaverWebClient(wireMock.baseUrl(), "test-id", "test-secret"));
    }

    @AfterEach
    void stopStubServer() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Nested
    @DisplayName("요청")
    class Request {

        @Test
        @DisplayName("이관된 경로로 sort=comment 를 고정해 보낸다")
        void 경로와_정렬을_고정한다() {
            stubBody(twoItems());

            client.search("경주 황리단길 카페", 5);

            List<LoggedRequest> sent = wireMock.findAll(
                getRequestedFor(urlPathEqualTo("/search/v1/local")));
            assertThat(sent).hasSize(1);

            String url = sent.get(0).getUrl();
            assertThat(url)
                .as("레거시 경로 /v1/search/local.json 은 404다 — 이관된 경로여야 한다")
                .startsWith("/search/v1/local");
            assertThat(url).contains("sort=comment");
            assertThat(url).contains("display=5");
            assertThat(url)
                .as("start 는 무시되는 파라미터라 아예 보내지 않는다")
                .doesNotContain("start=");
        }

        @Test
        @DisplayName("인증 헤더는 API HUB 방식이다 — 레거시 헤더로는 401이 온다")
        void 인증_헤더를_보낸다() {
            stubBody(twoItems());

            client.search("경주 카페", 5);

            LoggedRequest sent = wireMock.findAll(
                getRequestedFor(urlPathEqualTo("/search/v1/local"))).get(0);
            assertThat(sent.getHeader("X-NCP-APIGW-API-KEY-ID")).isEqualTo("test-id");
            assertThat(sent.getHeader("X-NCP-APIGW-API-KEY")).isEqualTo("test-secret");
            assertThat(sent.getHeader("X-Naver-Client-Id")).isNull();
        }

        @Test
        @DisplayName("display 는 5를 넘길 수 없다 — 넘겨도 잘라서 보낸다")
        void display_상한을_적용한다() {
            stubBody(twoItems());

            client.search("경주 카페", 50);

            assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo("/search/v1/local")))
                .get(0).getUrl()).contains("display=5");
        }
    }

    @Nested
    @DisplayName("응답 정규화")
    class Normalization {

        @Test
        @DisplayName("<b> 태그를 걷어내고 좌표를 WGS84로 환산하며 순위를 매긴다")
        void 응답을_후보로_바꾼다() {
            stubBody(twoItems());

            NaverLocalResult result = client.search("경주 황리단길 카페", 5);

            assertThat(result).isInstanceOf(NaverLocalResult.Found.class);
            List<NaverPlace> places = ((NaverLocalResult.Found) result).places();
            assertThat(places).hasSize(2);

            NaverPlace first = places.get(0);
            assertThat(first.name()).isEqualTo("이치니산도");
            assertThat(first.seedRank()).isEqualTo(1);
            assertThat(first.latitude()).isEqualTo(35.83639);
            assertThat(first.longitude()).isEqualTo(129.2092884);

            NaverPlace second = places.get(1);
            assertThat(second.name())
                .as("검색어 매칭 <b> 태그가 상호명에 그대로 남으면 코스에 태그가 저장된다")
                .isEqualTo("두낫디스터브 경주본점");
            assertThat(second.seedRank())
                .as("sort=comment 라 응답 순서가 곧 인기 순위다")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("좌표가 깨진 항목은 후보로 남되 좌표만 비운다 — 0.0/0.0으로 위장하지 않는다")
        void 좌표가_깨져도_후보는_살린다() {
            stubBody("""
                {"total":1,"start":1,"display":1,"items":[
                  {"title":"좌표없는가게","link":"","category":"음식점>한식","description":"",
                   "telephone":"","address":"경상북도 경주시 어딘가","roadAddress":"",
                   "mapx":"","mapy":"NaN"}
                ]}""");

            NaverLocalResult result = client.search("경주 맛집", 5);

            NaverPlace place = ((NaverLocalResult.Found) result).places().get(0);
            assertThat(place.latitude()).isNull();
            assertThat(place.longitude()).isNull();
            assertThat(place.hasCoordinates()).isFalse();
            assertThat(place.name()).isEqualTo("좌표없는가게");
        }

        @Test
        @DisplayName("도로명주소가 비면 지번주소로 떨어진다 — dedupe 키가 비지 않게")
        void 주소를_고른다() {
            stubBody("""
                {"total":1,"start":1,"display":1,"items":[
                  {"title":"지번만","link":"","category":"음식점","description":"","telephone":"",
                   "address":"경상북도 경주시 사정동 124-1","roadAddress":"",
                   "mapx":"1292092884","mapy":"358363900"}
                ]}""");

            NaverPlace place = ((NaverLocalResult.Found) client.search("경주 맛집", 5))
                .places().get(0);

            assertThat(place.bestAddress()).isEqualTo("경상북도 경주시 사정동 124-1");
        }
    }

    @Nested
    @DisplayName("실패 분기 — 예외가 아니라 값으로 돌려준다")
    class Failures {

        @Test
        @DisplayName("0건은 Empty 다 — 그 지역에 그 업종이 없다는 신호")
        void 결과가_없으면_Empty() {
            stubBody("{\"total\":0,\"start\":1,\"display\":5,\"items\":[]}");

            assertThat(client.search("경주 없는업종", 5))
                .isInstanceOf(NaverLocalResult.Empty.class);
        }

        @Test
        @DisplayName("429는 QUOTA_EXCEEDED 로 갈라진다 — 일일 한도는 시간이 지나야 풀리는 실패다")
        void 한도_초과를_구분한다() {
            stubStatus(429);

            NaverLocalResult result = client.search("경주 카페", 5);

            assertThat(result).isInstanceOf(NaverLocalResult.Failed.class);
            assertThat(((NaverLocalResult.Failed) result).cause())
                .isEqualTo(NaverLocalResult.Cause.QUOTA_EXCEEDED);
        }

        @Test
        @DisplayName("401은 UNAUTHORIZED 다 — 키 누락과 API 미활성화가 여기로 온다")
        void 인증_실패를_구분한다() {
            stubStatus(401);

            assertThat(((NaverLocalResult.Failed) client.search("경주 카페", 5)).cause())
                .isEqualTo(NaverLocalResult.Cause.UNAUTHORIZED);
        }

        @Test
        @DisplayName("5xx는 HTTP_ERROR 다")
        void 서버_오류를_구분한다() {
            stubStatus(503);

            assertThat(((NaverLocalResult.Failed) client.search("경주 카페", 5)).cause())
                .isEqualTo(NaverLocalResult.Cause.HTTP_ERROR);
        }

        @Test
        @DisplayName("200인데 본문이 스키마와 다르면 MALFORMED 다 — 후보만 비고 코스는 산다")
        void 깨진_본문을_구분한다() {
            stubBody("{\"items\": \"배열이 아니라 문자열\"}");

            assertThat(((NaverLocalResult.Failed) client.search("경주 카페", 5)).cause())
                .isEqualTo(NaverLocalResult.Cause.MALFORMED);
        }

        @Test
        @DisplayName("키가 비어도 조립·호출이 예외를 던지지 않는다 — fail-open 의 전제")
        void 키가_비어도_예외를_던지지_않는다() {
            stubStatus(401);
            NaverLocalClient keyless = new NaverLocalClient(
                NaverConfig.buildNaverWebClient(wireMock.baseUrl(), "", ""));

            NaverLocalResult result = keyless.search("경주 카페", 5);

            assertThat(result)
                .as("키 부재가 기동 실패나 예외가 되면 후보 공급이 코스 생성을 죽인다")
                .isInstanceOf(NaverLocalResult.Failed.class);
            assertThat(((NaverLocalResult.Failed) result).cause())
                .isEqualTo(NaverLocalResult.Cause.UNAUTHORIZED);
        }

        @Test
        @DisplayName("빈 쿼리는 호출하지 않고 Empty 다 — 쿼터를 낭비하지 않는다")
        void 빈_쿼리는_호출하지_않는다() {
            assertThat(client.search("  ", 5)).isInstanceOf(NaverLocalResult.Empty.class);
            assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo("/search/v1/local"))))
                .isEmpty();
        }
    }

    // ── 스텁 ────────────────────────────────────────────────────────────────

    private void stubBody(String body) {
        wireMock.stubFor(get(urlPathEqualTo("/search/v1/local"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json;charset=UTF-8")
                .withBody(body)));
    }

    private void stubStatus(int status) {
        wireMock.stubFor(get(urlPathEqualTo("/search/v1/local"))
            .willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json;charset=UTF-8")
                .withBody("{\"error\":{\"errorCode\":\"" + status + "\"}}")));
    }

    /** 4-2 실호출 응답에서 두 건만 남긴 것. 두 번째 항목에 실제로 &lt;b&gt; 태그가 들어 있었다. */
    private static String twoItems() {
        return """
            {
              "lastBuildDate":"Wed, 19 Aug 2026 12:46:41 +0900",
              "total":2,"start":1,"display":2,
              "items":[
                {"title":"이치니산도","link":"https://www.instagram.com/ichini_sando/",
                 "category":"음식점>카페,디저트","description":"","telephone":"",
                 "address":"경상북도 경주시 사정동 124-1","roadAddress":"경상북도 경주시 사정로 58",
                 "mapx":"1292092884","mapy":"358363900"},
                {"title":"두낫디스터브 <b>경주</b>본점","link":"",
                 "category":"음식점>카페,디저트","description":"","telephone":"",
                 "address":"경상북도 경주시 황남동 325-6","roadAddress":"경상북도 경주시 첨성로 71-1",
                 "mapx":"1292101468","mapy":"358332105"}
              ]
            }""";
    }
}
