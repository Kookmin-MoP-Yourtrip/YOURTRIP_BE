package backend.yourtrip.global.ai.route;

import static backend.yourtrip.global.ai.route.RouteTestFixtures.place;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code RoutePlace} / {@code RouteRequest} / {@code TravelMode} 단위 테스트 (ROADMAP 3-3).
 *
 * <p>이 세 타입이 <b>경계에서 막아주는 덕분에</b> 최적화기 본체에 방어 코드가 없다. 좌표 null
 * 분기도, 시각 null 분기도 본체에는 존재하지 않는다. 그 전제가 실제로 성립하는지를 여기서
 * 확인한다 — 여기가 뚫리면 본체의 단순함이 안전이 아니라 방치가 된다.
 *
 * <p>특히 좌표 검증은 <b>일부러 예외로 실패시키는</b> 선택이라 근거를 남겨둔다. 좌표 없는 장소를
 * 조용히 통과시키면 "동선이 계산되지 않은 장소"가 코스에 섞이고, 0.0/0.0으로 채우면 로드맵 1-1에서
 * 지운 결함이 되돌아온다. 거르는 책임은 7단계에 있고, 3단계는 그 책임이 이행됐는지를 확인만 한다.
 */
@DisplayName("route 입출력 타입 — 경계 검증과 기본값 (ROADMAP 3-3)")
class RouteTypesTest {

    @Nested
    @DisplayName("RoutePlace — 좌표는 있어야만 한다")
    class RoutePlaceValidation {

        @Test
        @DisplayName("정상 좌표는 그대로 통과한다")
        void acceptsValidCoordinates() {
            assertThatCode(() -> place("대릉원", SlotType.ATTRACTION, 35.8347, 129.2094))
                .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(doubles = {90.001, -90.001, 180.0, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        @DisplayName("위도가 범위를 벗어나거나 NaN 이면 생성 자체가 실패한다")
        void rejectsInvalidLatitude(double latitude) {
            assertThatThrownBy(() -> place("x", SlotType.MEAL, latitude, 129.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("위도");
        }

        @ParameterizedTest
        @ValueSource(doubles = {180.001, -180.001, Double.NaN, Double.POSITIVE_INFINITY})
        @DisplayName("경도가 범위를 벗어나거나 NaN 이면 생성 자체가 실패한다")
        void rejectsInvalidLongitude(double longitude) {
            assertThatThrownBy(() -> place("x", SlotType.MEAL, 35.0, longitude))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("경도");
        }

        @Test
        @DisplayName("극점과 날짜변경선 경계값은 허용한다 — 범위의 끝은 유효한 좌표다")
        void acceptsBoundaryCoordinates() {
            assertThatCode(() -> place("북극", SlotType.VIEWPOINT, 90.0, 180.0))
                .doesNotThrowAnyException();
            assertThatCode(() -> place("남극", SlotType.VIEWPOINT, -90.0, -180.0))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("slotType 이 없으면 체류시간을 정할 수 없으므로 거부한다")
        void rejectsNullSlotType() {
            assertThatThrownBy(() -> new RoutePlace("id", "이름", null, 35.0, 129.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slotType");
        }
    }

    @Nested
    @DisplayName("RouteRequest — 기본값 흡수")
    class RouteRequestDefaults {

        private final List<RoutePlace> places = List.of(place("대릉원", SlotType.ATTRACTION));

        @Test
        @DisplayName("시각과 이동수단을 비워두면 09:30 ~ 23:59 / 미지정이 된다")
        void fillsDefaults() {
            RouteRequest request = new RouteRequest(1, places, null, null, null);

            assertThat(request.dayStartTime()).isEqualTo(LocalTime.of(9, 30));
            assertThat(request.dayEndTime())
                .as("설계 문서의 21:00 대신 자정 직전으로 넓혔다")
                .isEqualTo(LocalTime.of(23, 59));
            assertThat(request.travelMode()).isEqualTo(TravelMode.UNSPECIFIED);
        }

        @Test
        @DisplayName("of() 팩터리도 같은 기본값을 쓴다")
        void factoryUsesSameDefaults() {
            RouteRequest request = RouteRequest.of(2, places);

            assertThat(request.day()).isEqualTo(2);
            assertThat(request.dayStartTime()).isEqualTo(RouteRequest.DEFAULT_DAY_START);
            assertThat(request.dayEndTime()).isEqualTo(RouteRequest.DEFAULT_DAY_END);
            assertThat(request.travelMode()).isEqualTo(TravelMode.UNSPECIFIED);
        }

        @Test
        @DisplayName("명시한 값은 덮어쓰지 않는다")
        void keepsExplicitValues() {
            RouteRequest request = new RouteRequest(
                1, places, LocalTime.of(11, 0), LocalTime.of(20, 0), TravelMode.CAR);

            assertThat(request.dayStartTime()).isEqualTo(LocalTime.of(11, 0));
            assertThat(request.dayEndTime()).isEqualTo(LocalTime.of(20, 0));
            assertThat(request.travelMode()).isEqualTo(TravelMode.CAR);
        }
    }

    @Nested
    @DisplayName("RouteRequest — 불변식")
    class RouteRequestInvariants {

        @Test
        @DisplayName("종료가 시작보다 이르거나 같으면 거부한다")
        void rejectsInvertedDayWindow() {
            List<RoutePlace> places = List.of(place("대릉원", SlotType.ATTRACTION));

            assertThatThrownBy(() -> new RouteRequest(
                1, places, LocalTime.of(20, 0), LocalTime.of(9, 30), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("늦어야 한다");

            assertThatThrownBy(() -> new RouteRequest(
                1, places, LocalTime.of(9, 30), LocalTime.of(9, 30), null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("places 가 null 이면 거부하되, 빈 리스트는 허용한다")
        void rejectsNullPlacesButAllowsEmpty() {
            assertThatThrownBy(() -> new RouteRequest(1, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatCode(() -> RouteRequest.of(1, List.of()))
                .as("장소가 없는 day 도 유효한 입력이다")
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("places 를 복사해 보관한다 — 넘긴 리스트를 나중에 바꿔도 영향이 없다")
        void copiesPlaces() {
            List<RoutePlace> mutable = new ArrayList<>();
            mutable.add(place("대릉원", SlotType.ATTRACTION));

            RouteRequest request = RouteRequest.of(1, mutable);
            mutable.add(place("첨성대", SlotType.ATTRACTION));

            assertThat(request.places()).hasSize(1);
            assertThatThrownBy(() -> request.places().add(place("월정교", SlotType.WALK)))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("TravelMode")
    class TravelModeValues {

        @Test
        @DisplayName("속도와 오버헤드는 설계 문서 §5-2 그대로다")
        void matchesDesignDocument() {
            assertThat(TravelMode.WALK.getEffectiveSpeedKmh()).isEqualTo(12.0);
            assertThat(TravelMode.WALK.getFixedOverheadMinutes()).isEqualTo(10);

            assertThat(TravelMode.CAR.getEffectiveSpeedKmh()).isEqualTo(25.0);
            assertThat(TravelMode.CAR.getFixedOverheadMinutes()).isEqualTo(5);

            assertThat(TravelMode.UNSPECIFIED.getEffectiveSpeedKmh()).isEqualTo(15.0);
            assertThat(TravelMode.UNSPECIFIED.getFixedOverheadMinutes()).isEqualTo(8);
        }

        @ParameterizedTest
        @EnumSource(TravelMode.class)
        @DisplayName("속도는 양수다 — 0이면 이동시간이 무한대가 된다")
        void speedsArePositive(TravelMode mode) {
            assertThat(mode.getEffectiveSpeedKmh()).isPositive();
            assertThat(mode.getFixedOverheadMinutes()).isPositive();
        }

        @Test
        @DisplayName("미지정은 뚜벅이와 자차 사이의 값이다")
        void unspecifiedSitsBetween() {
            assertThat(TravelMode.UNSPECIFIED.getEffectiveSpeedKmh())
                .isBetween(TravelMode.WALK.getEffectiveSpeedKmh(),
                    TravelMode.CAR.getEffectiveSpeedKmh());
            assertThat(TravelMode.UNSPECIFIED.getFixedOverheadMinutes())
                .isBetween(TravelMode.CAR.getFixedOverheadMinutes(),
                    TravelMode.WALK.getFixedOverheadMinutes());
        }
    }
}
