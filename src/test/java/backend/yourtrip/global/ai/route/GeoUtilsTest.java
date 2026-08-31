package backend.yourtrip.global.ai.route;

import static backend.yourtrip.global.ai.route.GeoUtils.haversineKm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code GeoUtils} 단위 테스트 (ROADMAP 3-2).
 *
 * <p>기댓값은 되도록 <b>공식에서 직접 유도되는 값</b>으로 잡았다. "서울에서 부산까지 대략 325km"
 * 같은 상식값만으로 테스트를 채우면 부호를 뒤집거나 반지름을 잘못 넣어도 오차 범위 안에 들어와
 * 통과해버린다. 적도 1도가 {@code R × π/180}이라는 것은 구현과 무관하게 참이므로, 그쪽이 회귀를
 * 훨씬 잘 잡는다.
 *
 * <p>특히 두 가지를 노린다.
 * <ul>
 *   <li><b>대척점에서 NaN이 나오지 않는다</b> — {@code a}를 [0,1]로 클램프하지 않으면
 *       {@code sqrt(1 - a)}가 {@code sqrt(음수)}가 된다. NaN 거리는 예외를 던지지 않고 비교
 *       연산만 전부 false로 만들어, {@code RouteOptimizer}의 최적 순열 선택을 <b>조용히</b>
 *       망가뜨린다. 국내 좌표만 다루는 한 실제로 밟히지는 않지만, 밟히면 원인을 찾기 어려운
 *       종류라 테스트로 고정해 둔다.</li>
 *   <li><b>대칭성이 비트 단위로 성립한다</b> — {@code RouteOptimizer}가 동점 순열을 사전순으로
 *       가르는데, {@code d(a,b)}와 {@code d(b,a)}의 마지막 비트가 다르면 그 규칙 대신 부동소수점
 *       잡음이 승자를 정한다. 결정성 테스트가 간헐 실패로 바뀌는 경로다.</li>
 * </ul>
 */
@DisplayName("GeoUtils — haversine 거리 (ROADMAP 3-2)")
class GeoUtilsTest {

    /** 적도에서 위도 1도에 해당하는 거리(km). {@code R × π/180} 으로 구현과 무관하게 유도된다. */
    private static final double ONE_DEGREE_KM = GeoUtils.EARTH_RADIUS_KM * Math.PI / 180.0;

    @Nested
    @DisplayName("공식에서 유도되는 값")
    class DerivedValues {

        @Test
        @DisplayName("적도에서 위도 1도는 약 111.19km 다")
        void oneDegreeOfLatitudeAtEquator() {
            assertThat(haversineKm(0.0, 0.0, 1.0, 0.0))
                .isCloseTo(ONE_DEGREE_KM, within(1e-6));
            assertThat(ONE_DEGREE_KM)
                .as("유도값 자체가 상식과 맞는지도 확인한다")
                .isCloseTo(111.195, within(0.001));
        }

        @Test
        @DisplayName("적도에서는 경도 1도도 위도 1도와 같은 거리다")
        void oneDegreeOfLongitudeAtEquator() {
            assertThat(haversineKm(0.0, 0.0, 0.0, 1.0))
                .isCloseTo(ONE_DEGREE_KM, within(1e-6));
        }

        @Test
        @DisplayName("위도 60도에서 경도 1도는 적도의 절반이다 — cos(60°) = 0.5")
        void longitudeShrinksWithLatitude() {
            assertThat(haversineKm(60.0, 0.0, 60.0, 1.0))
                .isCloseTo(ONE_DEGREE_KM * 0.5, within(0.01));
        }

        @Test
        @DisplayName("위도 방향 거리는 위도가 달라져도 일정하다 — 경선은 어디서나 같은 간격이다")
        void latitudeSpacingIsConstant() {
            assertThat(haversineKm(0.0, 0.0, 1.0, 0.0))
                .isCloseTo(haversineKm(60.0, 0.0, 61.0, 0.0), within(1e-6));
        }
    }

    @Nested
    @DisplayName("경계값")
    class EdgeCases {

        @Test
        @DisplayName("같은 좌표는 정확히 0 이다 — 오차가 아니라 0")
        void identicalCoordinatesAreZero() {
            assertThat(haversineKm(35.8347, 129.2094, 35.8347, 129.2094)).isZero();
        }

        @Test
        @DisplayName("대척점에서도 NaN 이 아니라 지구 둘레의 절반이 나온다")
        void antipodeDoesNotProduceNaN() {
            double distance = haversineKm(0.0, 0.0, 0.0, 180.0);

            assertThat(distance).as("클램프가 없으면 여기서 NaN 이 된다").isNotNaN();
            assertThat(distance).isCloseTo(Math.PI * GeoUtils.EARTH_RADIUS_KM, within(1e-6));
        }

        @Test
        @DisplayName("극점을 지나는 대척점도 NaN 이 아니다")
        void poleToPoleDoesNotProduceNaN() {
            double distance = haversineKm(-90.0, 0.0, 90.0, 0.0);

            assertThat(distance).isNotNaN();
            assertThat(distance).isCloseTo(Math.PI * GeoUtils.EARTH_RADIUS_KM, within(1e-6));
        }

        @Test
        @DisplayName("아주 짧은 거리도 뭉개지지 않는다 — asin 대신 atan2 를 쓴 회귀 방지")
        void keepsPrecisionAtShortDistances() {
            // 위도 0.0001도 = 약 11.1m. 도심 코스에서 두 장소가 이만큼 붙어 있는 일은 흔하다.
            assertThat(haversineKm(35.8347, 129.2094, 35.8348, 129.2094))
                .isCloseTo(ONE_DEGREE_KM * 0.0001, within(1e-9));
        }

        @Test
        @DisplayName("음수 좌표(남반구·서반구)도 정상 처리한다")
        void handlesNegativeCoordinates() {
            assertThat(haversineKm(-33.8688, 151.2093, -37.8136, 144.9631))
                .as("시드니-멜버른은 약 713km 다")
                .isCloseTo(713.0, within(10.0));
        }
    }

    @Nested
    @DisplayName("대칭성 — RouteOptimizer 의 동점 처리가 여기에 기댄다")
    class Symmetry {

        @Test
        @DisplayName("인자 순서를 바꿔도 비트 단위로 같은 값이다")
        void isBitwiseSymmetric() {
            double forward = haversineKm(37.5663, 126.9779, 35.1798, 129.0750);
            double backward = haversineKm(35.1798, 129.0750, 37.5663, 126.9779);

            assertThat(forward)
                .as("근사가 아니라 완전히 같아야 한다 — 마지막 비트가 다르면 tie-break 가 잡음에 먹힌다")
                .isEqualTo(backward);
        }

        @Test
        @DisplayName("짧은 거리에서도 대칭이다")
        void isSymmetricAtShortDistances() {
            assertThat(haversineKm(35.8347, 129.2094, 35.8400, 129.2150))
                .isEqualTo(haversineKm(35.8400, 129.2150, 35.8347, 129.2094));
        }
    }

    @Nested
    @DisplayName("실제 스케일 확인")
    class RealWorldScale {

        @Test
        @DisplayName("서울시청 - 부산시청은 약 325km 다")
        void seoulToBusan() {
            assertThat(haversineKm(37.5663, 126.9779, 35.1798, 129.0750))
                .isCloseTo(325.0, within(5.0));
        }

        @Test
        @DisplayName("도심 두 지점은 1km 안쪽으로 나온다 — 코스 최적화가 다루는 실제 스케일")
        void withinCityCentre() {
            // 경주 대릉원 일대에서 약 700m 떨어진 두 지점.
            assertThat(haversineKm(35.8347, 129.2094, 35.8400, 129.2150))
                .isBetween(0.5, 1.0);
        }
    }
}
