package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code SeedDistanceLimit} 단위 테스트 (이슈 #134).
 *
 * <p><b>경계를 좌표가 아니라 값으로 고정한다.</b> 45km 지점을 위경도로 만들면
 * {@code 0.404697...°} 같은 수가 되어 손으로 검산할 수 없고, 상한을 바꾸는 순간 그 좌표가 조용히
 * 무의미해진다. {@code Math.nextUp}이면 경계가 정확히 한 점으로 못 박힌다.
 */
@DisplayName("SeedDistanceLimit — 권역 밖 후보 판정 (이슈 #134)")
class SeedDistanceLimitTest {

    @Nested
    @DisplayName("경계")
    class Boundary {

        @Test
        @DisplayName("상한과 정확히 같으면 살린다 — > 이지 >= 가 아니다")
        void distanceAtTheLimitIsKept() {
            assertThat(SeedDistanceLimit.isOutOfRegion(
                SeedDistanceLimit.MAX_ANCHOR_DISTANCE_KM)).isFalse();
        }

        @Test
        @DisplayName("상한을 조금이라도 넘으면 탈락이다")
        void justBeyondTheLimitIsDropped() {
            assertThat(SeedDistanceLimit.isOutOfRegion(
                Math.nextUp(SeedDistanceLimit.MAX_ANCHOR_DISTANCE_KM))).isTrue();
        }

        @Test
        @DisplayName("같은 자리의 후보는 당연히 살린다")
        void zeroIsKept() {
            assertThat(SeedDistanceLimit.isOutOfRegion(0.0)).isFalse();
        }
    }

    @Nested
    @DisplayName("모르는 것을 이탈로 판정하지 않는다")
    class FailOpen {

        @Test
        @DisplayName("거리가 null 이면 통과시킨다 — '가깝다'가 아니라 '잴 수 없었다'는 뜻이다")
        void nullDistanceIsNotOutOfRegion() {
            // 지오코딩이 실패해 앵커 좌표가 없는 day 다. 여기서 거르면 파이프라인의
            // fail-open 원칙을 어기고, 못 거른 후보보다 잘못 거른 후보의 손해가 크다.
            assertThat(SeedDistanceLimit.isOutOfRegion(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("실측이 정한 값 (2026-08-25)")
    class MeasuredValue {

        /**
         * <b>상수의 값이 아니라 근거를 고정하는 테스트다.</b> 나중에 누가 "튜닝"으로 30km나 150km를
         * 넣으면 이 단언이 막는다 — 어느 쪽이든 두 실패 모드 중 하나를 확실히 틀리게 만든다.
         */
        @Test
        @DisplayName("상한은 실측이 연 무인지대 안에 있다 — 정상 최대 35.76km, 차단 최소 48.15km")
        void limitSitsInsideTheObservedDeadZone() {
            assertThat(SeedDistanceLimit.MAX_ANCHOR_DISTANCE_KM)
                .as("35.76km 아래로 조이면 제주 델문도 같은 정상 후보가 잘리고, "
                    + "48.15km 위로 풀면 공주 주말농장 질의가 부른 청주 후보가 통과한다. "
                    + "근거: docs/tasks/ai-course-create/decisions/시더-거리-상한.md")
                .isGreaterThan(35.76)
                .isLessThan(48.15);
        }

        @ParameterizedTest
        @ValueSource(doubles = {102.0, 138.0, 177.0})
        @DisplayName("알려진 사고 거리는 전부 탈락한다 — 순천 102 / 공주 138 / 통영 177km")
        void incidentDistancesAreAllDropped(double incidentDistanceKm) {
            assertThat(SeedDistanceLimit.isOutOfRegion(incidentDistanceKm)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(doubles = {35.76, 30.23, 29.99, 15.60, 1.07})
        @DisplayName("실측된 정상 후보 거리는 전부 살아남는다 — 제주 35.76 / 영주 부석사 30.23km 등")
        void observedLegitimateDistancesSurvive(double legitimateDistanceKm) {
            assertThat(SeedDistanceLimit.isOutOfRegion(legitimateDistanceKm)).isFalse();
        }
    }
}
