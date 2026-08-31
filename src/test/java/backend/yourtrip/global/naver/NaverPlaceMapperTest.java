package backend.yourtrip.global.naver;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.naver.dto.NaverLocalResponse;
import backend.yourtrip.global.naver.dto.NaverLocalResponse.Item;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link NaverPlaceMapper} 단위 테스트 (ROADMAP 4-1 · 4-6).
 *
 * <p>변환 값은 <b>4-2 실호출로 받은 실제 응답</b>에서 가져왔다. 좌표 규칙이 TourAPI와 정반대라
 * ({@code TourPlaceMapper}는 나누지 않는다) 이 테스트가 두 규칙이 섞이는 것을 막는 자리다.
 */
@DisplayName("NaverPlaceMapper — 응답 원문 정규화 (ROADMAP 4-1)")
class NaverPlaceMapperTest {

    @Nested
    @DisplayName("좌표 — 10⁷ 정수 문자열이다")
    class Coordinates {

        @Test
        @DisplayName("10⁷로 나눠 WGS84 도로 바꾼다 — TourAPI와 정반대 규칙이다")
        void dividesByTenMillion() {
            assertThat(NaverPlaceMapper.parseCoordinate("1292092884")).isEqualTo(129.2092884);
            assertThat(NaverPlaceMapper.parseCoordinate("358363900")).isEqualTo(35.83639);
        }

        @Test
        @DisplayName("자릿수로 소수점을 끼워 넣으면 위도가 10배로 튄다 — 그래서 정수로 읽어 나눈다")
        void doesNotSliceByDigitCount() {
            // 경도는 10자리, 위도는 9자리다. 같은 위치에 소수점을 넣는 규칙이었다면
            // 위도가 358.36 이 되어 지구 밖으로 나간다.
            Double latitude = NaverPlaceMapper.parseCoordinate("358363900");

            assertThat(latitude).isBetween(33.0, 39.0);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "숫자아님", "129.2092884"})
        @DisplayName("읽을 수 없으면 null이다 — 좌표 하나 때문에 후보 목록을 버리지 않는다")
        void returnsNullWhenUnparsable(String raw) {
            assertThat(NaverPlaceMapper.parseCoordinate(raw)).isNull();
        }

        @Test
        @DisplayName("null도 null이다")
        void handlesNull() {
            assertThat(NaverPlaceMapper.parseCoordinate(null)).isNull();
        }

        @Test
        @DisplayName("mapy가 위도이고 mapx가 경도다 — 뒤바뀌면 국내 좌표가 아니게 된다")
        void mapsAxesCorrectly() {
            NaverPlace place = onePlace(item("가게", "1292092884", "358363900"));

            assertThat(place.longitude()).isBetween(124.0, 132.0);
            assertThat(place.latitude()).isBetween(33.0, 39.0);
        }
    }

    @Nested
    @DisplayName("<b> 태그 스트립")
    class BoldTags {

        @Test
        @DisplayName("검색어 매칭 태그를 걷어낸다 — 남으면 그대로 사용자 코스에 저장된다")
        void stripsBoldTags() {
            assertThat(NaverPlaceMapper.stripBoldTags("두낫디스터브 <b>경주</b>본점"))
                .isEqualTo("두낫디스터브 경주본점");
        }

        @Test
        @DisplayName("대문자 태그도 걷어낸다")
        void stripsUppercaseTags() {
            assertThat(NaverPlaceMapper.stripBoldTags("<B>황리단길</B> 커피"))
                .isEqualTo("황리단길 커피");
        }

        @Test
        @DisplayName("태그가 없으면 그대로 둔다")
        void leavesPlainTitleUnchanged() {
            assertThat(NaverPlaceMapper.stripBoldTags("브런치카페")).isEqualTo("브런치카페");
        }

        @Test
        @DisplayName("null은 빈 문자열이다")
        void handlesNull() {
            assertThat(NaverPlaceMapper.stripBoldTags(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("HTML 엔티티 디코딩 (이슈 #147)")
    class HtmlEntities {

        @Test
        @DisplayName("회귀 — 쉼팡마씸의 &amp; 가 후보 목록에 그대로 실리지 않는다")
        void decodesAmpersandInRealCase() {
            assertThat(NaverPlaceMapper.normalizeTitle(
                "<b>쉼팡마씸</b> 24시 무인카페 &amp; 4가지 식당"))
                .isEqualTo("쉼팡마씸 24시 무인카페 & 4가지 식당");
        }

        @Test
        @DisplayName("응답 전체를 옮길 때도 디코딩된 이름이 실린다 — 후보 이름은 여기서 한 번만 정해진다")
        void decodesThroughToPlaces() {
            NaverPlace place = onePlace(
                item("호텔 아쿠아펠리스 스카이 전망대&amp;스카이 워크", "1292092884", "358363900"));

            assertThat(place.name()).isEqualTo("호텔 아쿠아펠리스 스카이 전망대&스카이 워크");
        }

        @Test
        @DisplayName("나머지 네 종류도 되돌린다")
        void decodesRemainingEntities() {
            assertThat(NaverPlaceMapper.decodeEntities("&lt;&gt;&quot;&#39;"))
                .isEqualTo("<>\"'");
        }

        @Test
        @DisplayName("&amp;amp; 를 이중 디코딩하지 않는다 — 앰퍼샌드를 마지막에 푸는 이유다")
        void doesNotDoubleDecode() {
            assertThat(NaverPlaceMapper.decodeEntities("&amp;lt;b&amp;gt;")).isEqualTo("&lt;b&gt;");
        }

        @Test
        @DisplayName("이스케이프된 태그가 진짜 태그로 되살아나지 않는다 — 스트립이 먼저다")
        void doesNotResurrectTags() {
            assertThat(NaverPlaceMapper.normalizeTitle("가게 &lt;b&gt;강조&lt;/b&gt;"))
                .isEqualTo("가게 <b>강조</b>");
        }

        @Test
        @DisplayName("null은 빈 문자열이다")
        void handlesNull() {
            assertThat(NaverPlaceMapper.normalizeTitle(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("seedRank — 응답 순서가 곧 인기 순위다")
    class SeedRank {

        @Test
        @DisplayName("1부터 응답 순서대로 매긴다 — sort=comment라 이 순서가 리뷰 수 순위다")
        void numbersFromOneInResponseOrder() {
            List<NaverPlace> places = NaverPlaceMapper.toPlaces(response(
                item("첫째", "1292092884", "358363900"),
                item("둘째", "1292092884", "358363900"),
                item("셋째", "1292092884", "358363900")));

            assertThat(places).extracting(NaverPlace::seedRank).containsExactly(1, 2, 3);
            assertThat(places).extracting(NaverPlace::name)
                .containsExactly("첫째", "둘째", "셋째");
        }
    }

    @Nested
    @DisplayName("주소")
    class Address {

        @Test
        @DisplayName("도로명주소를 우선하고 없으면 지번으로 떨어진다 — dedupe 키의 절반이다")
        void prefersRoadAddress() {
            NaverPlace withRoad = onePlace(new Item("가게", "", "카페", "", "",
                "경북 경주시 황남동 1", "경북 경주시 포석로 1080", "1292092884", "358363900"));
            NaverPlace withoutRoad = onePlace(new Item("가게", "", "카페", "", "",
                "경북 경주시 황남동 1", "", "1292092884", "358363900"));

            assertThat(withRoad.bestAddress()).isEqualTo("경북 경주시 포석로 1080");
            assertThat(withoutRoad.bestAddress()).isEqualTo("경북 경주시 황남동 1");
        }
    }

    @Nested
    @DisplayName("빈 응답")
    class EmptyResponse {

        @Test
        @DisplayName("items가 null이면 빈 목록이다")
        void handlesNullItems() {
            assertThat(NaverPlaceMapper.toPlaces(
                new NaverLocalResponse("", 0, 1, 0, null))).isEmpty();
        }

        @Test
        @DisplayName("응답 자체가 null이어도 빈 목록이다")
        void handlesNullResponse() {
            assertThat(NaverPlaceMapper.toPlaces(null)).isEmpty();
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private static Item item(String title, String mapx, String mapy) {
        return new Item(title, "", "음식점>카페,디저트", "", "",
            "경북 경주시 황남동 1", "경북 경주시 포석로 1080", mapx, mapy);
    }

    private static NaverLocalResponse response(Item... items) {
        return new NaverLocalResponse("", items.length, 1, items.length, Arrays.asList(items));
    }

    private static NaverPlace onePlace(Item item) {
        return NaverPlaceMapper.toPlaces(response(item)).get(0);
    }
}
