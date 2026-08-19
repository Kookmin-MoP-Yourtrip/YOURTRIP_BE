package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link CandidateMatcher} 단위 테스트 (ROADMAP 4-5).
 *
 * <p>좌표는 <b>4-7 실호출로 받은 실제 값</b>을 쓴다. 지어낸 좌표로 임계값을 시험하면 "300m가
 * 무엇을 가르는지"가 테스트 안에서만 성립하는 이야기가 된다.
 */
@DisplayName("CandidateMatcher — 후보 중복 판정 (ROADMAP 4-5)")
class CandidateMatcherTest {

    // 4-7 실호출 값 (경주, contentTypeId=12)
    private static final double CHEONMACHONG_LAT = 35.8386877792;   // 천마총(대릉원)
    private static final double CHEONMACHONG_LON = 129.2104983997;
    private static final double SSAMBAP_LAT = 35.8392099471;        // 경주 쌈밥거리 (62m 거리)
    private static final double SSAMBAP_LON = 129.2107400311;
    private static final double NAEMUL_LAT = 35.8362047083;         // 경주 내물왕릉 (288m 거리)
    private static final double NAEMUL_LON = 129.2095707739;

    @Nested
    @DisplayName("같은 소스 안의 중복 — 등가 키")
    class WithinSource {

        @Test
        @DisplayName("기본 쿼리와 스타일 쿼리가 물어 온 같은 가게는 같은 키다")
        void sameStoreFromDifferentQueriesShareKey() {
            assertThat(CandidateMatcher.dedupeKey("커피플레이스", "경북 경주시 포석로 1080"))
                .isEqualTo(CandidateMatcher.dedupeKey("커피플레이스", "경북 경주시 포석로 1080"));
        }

        @Test
        @DisplayName("띄어쓰기·문장부호만 다른 표기는 같은 키다")
        void ignoresSpacingAndPunctuation() {
            assertThat(CandidateMatcher.dedupeKey("동궁과 월지", "경북 경주시 원화로 102"))
                .isEqualTo(CandidateMatcher.dedupeKey("동궁과월지", "경북 경주시 원화로102"));
        }

        @Test
        @DisplayName("같은 상호라도 지점이 다르면 다른 키다 — 이름만으로는 프랜차이즈가 뭉친다")
        void separatesBranchesOfSameBrand() {
            assertThat(CandidateMatcher.dedupeKey("스타벅스", "경북 경주시 포석로 1080"))
                .isNotEqualTo(CandidateMatcher.dedupeKey("스타벅스", "경북 경주시 보문로 424"));
        }

        @Test
        @DisplayName("한 건물의 다른 가게는 다른 키다 — 주소만으로는 뭉친다")
        void separatesDifferentStoresAtSameAddress() {
            assertThat(CandidateMatcher.dedupeKey("1층 카페", "경북 경주시 포석로 1080"))
                .isNotEqualTo(CandidateMatcher.dedupeKey("2층 식당", "경북 경주시 포석로 1080"));
        }

        @Test
        @DisplayName("null이 섞여도 키를 만든다 — dedupe가 예외로 죽으면 안 된다")
        void handlesNulls() {
            assertThat(CandidateMatcher.dedupeKey(null, null)).isEqualTo("|");
        }
    }

    @Nested
    @DisplayName("소스 간 같은 장소 — 거리 AND 이름")
    class AcrossSources {

        @Test
        @DisplayName("정식 명칭과 상호명이 달라도 가까우면 같은 장소다")
        void mergesSamePlaceAcrossSources() {
            // TourAPI "천마총(대릉원)" ↔ 네이버 "천마총". 좌표 차이는 소수점 수준이다.
            assertThat(CandidateMatcher.isSamePlace(
                "천마총(대릉원)", CHEONMACHONG_LAT, CHEONMACHONG_LON,
                "천마총", 35.8386, 129.2105))
                .isTrue();
        }

        @Test
        @DisplayName("62m 떨어진 서로 다른 장소는 합치지 않는다 — 좌표만 봤다면 합쳐졌다")
        void doesNotMergeNearbyDistinctPlaces() {
            // 4-7 표본에서 실제로 62m 떨어져 있던 쌍이다.
            assertThat(CandidateMatcher.distanceKm(
                CHEONMACHONG_LAT, CHEONMACHONG_LON, SSAMBAP_LAT, SSAMBAP_LON))
                .as("전제 확인 — 이 둘은 임계값 안에 있다")
                .isLessThan(CandidateMatcher.PROXIMITY_THRESHOLD_KM);

            assertThat(CandidateMatcher.isSamePlace(
                "천마총(대릉원)", CHEONMACHONG_LAT, CHEONMACHONG_LON,
                "경주 쌈밥거리", SSAMBAP_LAT, SSAMBAP_LON))
                .isFalse();
        }

        @Test
        @DisplayName("288m 떨어진 다른 유적도 합치지 않는다")
        void doesNotMergeAdjacentHistoricSites() {
            assertThat(CandidateMatcher.isSamePlace(
                "천마총(대릉원)", CHEONMACHONG_LAT, CHEONMACHONG_LON,
                "경주 내물왕릉", NAEMUL_LAT, NAEMUL_LON))
                .isFalse();
        }

        @Test
        @DisplayName("이름이 같아도 멀면 합치지 않는다 — 전국의 향교가 하나가 되는 경우다")
        void doesNotMergeSameNameFarApart() {
            assertThat(CandidateMatcher.isSamePlace(
                "향교", 35.8386, 129.2105,
                "향교", 37.5665, 126.9780))
                .isFalse();
        }

        @Test
        @DisplayName("임계값 경계에서 안쪽은 포함한다")
        void includesThresholdBoundary() {
            // 위도 0.0027도 ≈ 300m 바로 안쪽.
            assertThat(CandidateMatcher.isSamePlace(
                "대릉원", 35.8386, 129.2105,
                "대릉원", 35.8386 + 0.0026, 129.2105))
                .isTrue();
            assertThat(CandidateMatcher.isSamePlace(
                "대릉원", 35.8386, 129.2105,
                "대릉원", 35.8386 + 0.0040, 129.2105))
                .isFalse();
        }

        @Test
        @DisplayName("좌표가 없으면 합치지 않는다 — 못 합치는 실수가 잘못 합치는 실수보다 낫다")
        void refusesToMergeWithoutCoordinates() {
            assertThat(CandidateMatcher.isSamePlace(
                "천마총", null, null, "천마총", CHEONMACHONG_LAT, CHEONMACHONG_LON))
                .isFalse();
            assertThat(CandidateMatcher.isSamePlace(
                "천마총", CHEONMACHONG_LAT, null, "천마총", CHEONMACHONG_LAT, CHEONMACHONG_LON))
                .isFalse();
        }

        @Test
        @DisplayName("이름이 비면 합치지 않는다")
        void refusesToMergeWithoutName() {
            assertThat(CandidateMatcher.isSamePlace(
                "", CHEONMACHONG_LAT, CHEONMACHONG_LON,
                "천마총", CHEONMACHONG_LAT, CHEONMACHONG_LON))
                .isFalse();
        }
    }

    @Nested
    @DisplayName("거리 계산은 RouteOptimizer와 같은 함수를 쓴다")
    class Distance {

        @Test
        @DisplayName("인자 순서를 바꿔도 같은 값이다")
        void isSymmetric() {
            assertThat(CandidateMatcher.distanceKm(
                CHEONMACHONG_LAT, CHEONMACHONG_LON, NAEMUL_LAT, NAEMUL_LON))
                .isEqualTo(CandidateMatcher.distanceKm(
                    NAEMUL_LAT, NAEMUL_LON, CHEONMACHONG_LAT, CHEONMACHONG_LON));
        }

        @Test
        @DisplayName("TourAPI가 준 dist와 같은 대역이다 — 우리 계산이 어긋나지 않았다는 확인")
        void agreesWithTourApiDistance() {
            // TourAPI가 조회 좌표(35.8347/129.2094)로부터 계산해 준 값: 내물왕릉 167.7m,
            // 천마총 453.3m. 우리 계산도 같은 값이어야 한다.
            assertThat(CandidateMatcher.distanceKm(35.8347, 129.2094, NAEMUL_LAT, NAEMUL_LON) * 1000)
                .isCloseTo(167.7, org.assertj.core.data.Offset.offset(2.0));
            assertThat(CandidateMatcher.distanceKm(
                35.8347, 129.2094, CHEONMACHONG_LAT, CHEONMACHONG_LON) * 1000)
                .isCloseTo(453.3, org.assertj.core.data.Offset.offset(2.0));
        }
    }
}
