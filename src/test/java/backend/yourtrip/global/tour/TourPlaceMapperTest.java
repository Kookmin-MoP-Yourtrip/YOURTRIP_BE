package backend.yourtrip.global.tour;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.tour.dto.TourApiResponse;
import backend.yourtrip.global.tour.dto.TourApiResponse.Body;
import backend.yourtrip.global.tour.dto.TourApiResponse.Header;
import backend.yourtrip.global.tour.dto.TourApiResponse.Item;
import backend.yourtrip.global.tour.dto.TourApiResponse.Items;
import backend.yourtrip.global.tour.dto.TourApiResponse.Response;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link TourPlaceMapper} 단위 테스트 (ROADMAP 4-7 · 4-6).
 *
 * <p>변환은 <b>실패할 수 있는 지점</b>이고(좌표가 비거나 숫자가 아니다) 그 분기는 HTTP 없이
 * 고정돼야 한다 — {@code NaverPlaceMapper}를 따로 뗀 것과 같은 이유다.
 */
@DisplayName("TourPlaceMapper — 응답 원문 정규화 (ROADMAP 4-7)")
class TourPlaceMapperTest {

    @Nested
    @DisplayName("좌표")
    class Coordinates {

        @Test
        @DisplayName("평문 십진 도를 그대로 쓴다 — 네이버처럼 1e7로 나누면 좌표가 0 근처로 무너진다")
        void keepsPlainDecimalDegrees() {
            TourPlace place = TourPlaceMapper.toPlace(
                item("129.2095707739", "35.8362047083", "167.71"));

            assertThat(place.longitude()).isEqualTo(129.2095707739);
            assertThat(place.latitude()).isEqualTo(35.8362047083);
            assertThat(place.hasCoordinates()).isTrue();
        }

        @Test
        @DisplayName("mapx가 경도이고 mapy가 위도다 — 뒤바뀌면 국내 좌표가 아니게 된다")
        void mapsAxesCorrectly() {
            TourPlace place = TourPlaceMapper.toPlace(item("129.21", "35.83", "10"));

            assertThat(place.longitude()).isGreaterThan(124).isLessThan(132);
            assertThat(place.latitude()).isGreaterThan(33).isLessThan(39);
        }

        @Test
        @DisplayName("좌표를 못 읽으면 null이지만 후보 자체는 살린다")
        void keepsPlaceWhenCoordinateIsUnparsable() {
            TourPlace place = TourPlaceMapper.toPlace(item("", "숫자아님", "10"));

            assertThat(place.latitude()).isNull();
            assertThat(place.longitude()).isNull();
            assertThat(place.hasCoordinates()).isFalse();
            assertThat(place.title()).isEqualTo("경주 내물왕릉");
        }

        @Test
        @DisplayName("dist도 못 읽으면 null이다 — 0으로 채우면 가장 가까운 후보가 된다")
        void doesNotDefaultDistanceToZero() {
            assertThat(TourPlaceMapper.toPlace(item("129.21", "35.83", "")).distanceMeters())
                .isNull();
        }
    }

    @Nested
    @DisplayName("주소 합치기")
    class Address {

        @Test
        @DisplayName("addr2가 비면 공백이 붙어 다니지 않는다 — 주소가 dedupe 키의 절반이다")
        void doesNotLeaveTrailingSpace() {
            assertThat(TourPlaceMapper.toPlace(item("129.21", "35.83", "10")).address())
                .isEqualTo("경상북도 경주시 포석로 1065");
        }

        @Test
        @DisplayName("addr2가 있으면 공백 하나로 잇는다")
        void joinsBothParts() {
            Item source = new Item("1", "12", "테스트", "경북 경주시 포석로 1065", "2층",
                "A02", "A0201", "A02010700", "129.21", "35.83", "10", "", "", "");

            assertThat(TourPlaceMapper.toPlace(source).address())
                .isEqualTo("경북 경주시 포석로 1065 2층");
        }
    }

    @Nested
    @DisplayName("빈 값 처리")
    class EmptyValues {

        @Test
        @DisplayName("빈 문자열 필드는 null이 된다 — 4-9가 cat3의 빈 문자열을 코드로 오인하면 안 된다")
        void turnsBlankFieldsIntoNull() {
            Item source = new Item("1", "12", "테스트", "주소", "",
                "", "", "", "129.21", "35.83", "10", "", "", "");
            TourPlace place = TourPlaceMapper.toPlace(source);

            assertThat(place.cat1()).isNull();
            assertThat(place.cat3()).isNull();
            assertThat(place.imageUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("응답 전체 변환")
    class WholeResponse {

        @Test
        @DisplayName("항목 순서를 그대로 유지한다 — arrange=E의 거리순이 곧 후보 순위다")
        void preservesOrder() {
            List<TourPlace> places = TourPlaceMapper.toPlaces(response(
                item("129.21", "35.83", "10"),
                item("129.22", "35.84", "500")));

            assertThat(places).extracting(TourPlace::distanceMeters)
                .containsExactly(10.0, 500.0);
        }

        @Test
        @DisplayName("items가 null이면 빈 목록이다 — 0건 응답이 실제로 이 경로를 지난다")
        void handlesNullItems() {
            TourApiResponse empty = new TourApiResponse(new Response(
                new Header("0000", "OK"), new Body(null, 0, 1, 0)));

            assertThat(TourPlaceMapper.toPlaces(empty)).isEmpty();
        }

        @Test
        @DisplayName("응답 자체가 null이어도 빈 목록이다")
        void handlesNullResponse() {
            assertThat(TourPlaceMapper.toPlaces(null)).isEmpty();
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private static Item item(String mapx, String mapy, String dist) {
        return new Item("1621397", "12", "경주 내물왕릉", "경상북도 경주시 포석로 1065", "",
            "A02", "A0201", "A02010700", mapx, mapy, dist, "", "", "");
    }

    private static TourApiResponse response(Item... items) {
        return new TourApiResponse(new Response(
            new Header("0000", "OK"),
            new Body(new Items(Arrays.asList(items)), items.length, 1, items.length)));
    }
}
