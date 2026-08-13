package backend.yourtrip.global.ai.route;

import static backend.yourtrip.global.ai.route.RouteTestFixtures.eastOf;
import static backend.yourtrip.global.ai.route.RouteTestFixtures.place;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code RouteOptimizer} 시간 모델 단위 테스트 (ROADMAP 3-4).
 *
 * <p>여기서 검증하는 것은 순서가 아니라 <b>시각</b>이다. 순서를 고르는 완전탐색은 다음 커밋에서
 * 얹히고, 이 커밋 시점의 최적화기는 입력 순서를 그대로 둔다 — 그 상태 자체가
 * {@code n >= 8}일 때의 폴백 경로이므로 반쪽 구현이 아니다.
 *
 * <p>두 가지 부동소수점 함정을 회귀 테스트로 고정한다. 둘 다 <b>예외를 던지지 않고 결과만
 * 조용히 틀리는</b> 종류다.
 * <ul>
 *   <li>{@code 1.0 × 60 / 15.0}이 double 에서 {@code 4.000000000000001}이 되어
 *       {@code Math.ceil}이 5를 주는 것 — 구간마다 1분씩 붙어 하루 초과 판정을 바꾼다</li>
 *   <li>5분 올림을 계산 중에 적용해 하루 종료가 최대 28분 밀리는 것 — 넘치지 않는 코스가
 *       넘친 것으로 판정돼 장소가 삭제된다</li>
 * </ul>
 */
@DisplayName("RouteOptimizer — 시간 모델 (ROADMAP 3-4)")
class RouteOptimizerTimeModelTest {

    private final RouteOptimizer optimizer = new RouteOptimizer();

    @Nested
    @DisplayName("이동시간 계산")
    class TravelMinutes {

        @Test
        @DisplayName("이동수단별 유효속도와 고정 오버헤드를 함께 적용한다")
        void appliesSpeedAndOverhead() {
            // 뚜벅이: 1km / 12km/h = 5분, + 환승 대기 10분
            assertThat(RouteOptimizer.travelMinutes(1.0, TravelMode.WALK)).isEqualTo(15);
            // 자차: 1km / 25km/h = 2.4분 -> 올림 3분, + 주차 5분
            assertThat(RouteOptimizer.travelMinutes(1.0, TravelMode.CAR)).isEqualTo(8);
            // 미지정: 1km / 15km/h = 4분, + 8분
            assertThat(RouteOptimizer.travelMinutes(1.0, TravelMode.UNSPECIFIED)).isEqualTo(12);
        }

        @Test
        @DisplayName("나누어떨어지는 거리에 1분이 덧붙지 않는다 — 부동소수점 올림 함정 회귀")
        void doesNotOverCeilExactDivisions() {
            // 1 x 60 / 15 = 정확히 4.0 이지만, double 연산은 4.000000000000001 을 낼 수 있다.
            // 그대로 Math.ceil 하면 5가 되고, 하루 여섯 구간이면 근거 없는 6분이 붙는다.
            assertThat(RouteOptimizer.travelMinutes(1.0, TravelMode.UNSPECIFIED))
                .as("4분이어야 한다 (+오버헤드 8)")
                .isEqualTo(12);
            assertThat(RouteOptimizer.travelMinutes(2.0, TravelMode.UNSPECIFIED)).isEqualTo(16);
            assertThat(RouteOptimizer.travelMinutes(3.0, TravelMode.UNSPECIFIED)).isEqualTo(20);
            assertThat(RouteOptimizer.travelMinutes(2.5, TravelMode.CAR))
                .as("2.5 x 60 / 25 = 정확히 6.0")
                .isEqualTo(11);
        }

        @Test
        @DisplayName("거리가 0이어도 고정 오버헤드는 붙는다 — 같은 건물이어도 이동에는 시간이 든다")
        void chargesOverheadEvenAtZeroDistance() {
            assertThat(RouteOptimizer.travelMinutes(0.0, TravelMode.WALK)).isEqualTo(10);
            assertThat(RouteOptimizer.travelMinutes(0.0, TravelMode.CAR)).isEqualTo(5);
            assertThat(RouteOptimizer.travelMinutes(0.0, TravelMode.UNSPECIFIED)).isEqualTo(8);
        }

        @Test
        @DisplayName("소수 거리는 올림한다 — 이동시간을 낙관적으로 잡지 않는다")
        void roundsUpFractionalMinutes() {
            // 1.1 x 60 / 15 = 4.4 -> 5분
            assertThat(RouteOptimizer.travelMinutes(1.1, TravelMode.UNSPECIFIED)).isEqualTo(13);
        }
    }

    @Nested
    @DisplayName("표시 시각 정규화 — 5분 올림")
    class DisplayTime {

        @Test
        @DisplayName("이미 5분 단위면 그대로 둔다")
        void keepsValuesOnGrid() {
            assertThat(RouteOptimizer.toDisplayTime(9 * 60 + 30)).isEqualTo(LocalTime.of(9, 30));
            assertThat(RouteOptimizer.toDisplayTime(9 * 60 + 35)).isEqualTo(LocalTime.of(9, 35));
        }

        @Test
        @DisplayName("1분만 지나도 다음 눈금으로 올린다 — 내림하면 실제보다 이르게 표시된다")
        void ceilsToNextUnit() {
            assertThat(RouteOptimizer.toDisplayTime(9 * 60 + 31)).isEqualTo(LocalTime.of(9, 35));
            assertThat(RouteOptimizer.toDisplayTime(9 * 60 + 36)).isEqualTo(LocalTime.of(9, 40));
            assertThat(RouteOptimizer.toDisplayTime(9 * 60 + 39)).isEqualTo(LocalTime.of(9, 40));
        }

        @Test
        @DisplayName("자정을 넘긴 값은 23:55 로 자른다 — LocalTime 은 24:00 을 표현할 수 없다")
        void clampsPastMidnight() {
            assertThat(RouteOptimizer.toDisplayTime(24 * 60)).isEqualTo(LocalTime.of(23, 55));
            assertThat(RouteOptimizer.toDisplayTime(24 * 60 + 200)).isEqualTo(LocalTime.of(23, 55));
            // 23:56 은 올리면 24:00 이므로 여기서도 잘린다.
            assertThat(RouteOptimizer.toDisplayTime(23 * 60 + 56)).isEqualTo(LocalTime.of(23, 55));
        }
    }

    @Nested
    @DisplayName("하루 배치")
    class DailySchedule {

        @Test
        @DisplayName("첫 장소는 하루 시작 시각에 배치된다")
        void firstPlaceStartsAtDayStart() {
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                place("대릉원", SlotType.ATTRACTION))));

            assertThat(day.places()).hasSize(1);
            assertThat(day.places().get(0).startTime()).isEqualTo(LocalTime.of(9, 30));
            assertThat(day.startTime()).isEqualTo(LocalTime.of(9, 30));
        }

        @Test
        @DisplayName("도착 시각은 앞 장소의 체류시간과 이동시간을 누적한 값이다")
        void accumulatesStayAndTravel() {
            // 09:30 관광(90분) -> 1km 이동(12분) -> 11:12 카페(60분) -> 1km(12분) -> 12:24 식사(75분)
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                eastOf("대릉원", SlotType.ATTRACTION, 0),
                eastOf("카페", SlotType.CAFE, 1),
                eastOf("식당", SlotType.MEAL, 2))));

            assertThat(day.places()).extracting(RoutedPlace::startTime)
                .containsExactly(LocalTime.of(9, 30), LocalTime.of(11, 15), LocalTime.of(12, 25));
            assertThat(day.endTime())
                .as("마지막 식사 75분이 끝나는 13:39 를 올린 값")
                .isEqualTo(LocalTime.of(13, 40));
        }

        @Test
        @DisplayName("체류시간은 슬롯 기본값이 그대로 실려 나간다")
        void reportsSlotStayMinutes() {
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                eastOf("대릉원", SlotType.ATTRACTION, 0),
                eastOf("카페", SlotType.CAFE, 1))));

            assertThat(day.places()).extracting(RoutedPlace::stayMinutes)
                .containsExactly(90, 60);
        }

        @Test
        @DisplayName("표시 시각은 5분 올림 뒤에도 항상 순증가한다 — 겹치거나 역전되지 않는다")
        void displayTimesStrictlyIncrease() {
            RoutedDay day = optimizer.optimize(new RouteRequest(1, List.of(
                eastOf("a", SlotType.VIEWPOINT, 0),
                eastOf("b", SlotType.VIEWPOINT, 0.3),
                eastOf("c", SlotType.VIEWPOINT, 0.6),
                eastOf("d", SlotType.VIEWPOINT, 0.9),
                eastOf("e", SlotType.VIEWPOINT, 1.2)),
                LocalTime.of(9, 32), null, TravelMode.CAR));

            List<LocalTime> times = day.places().stream().map(RoutedPlace::startTime).toList();
            for (int i = 1; i < times.size(); i++) {
                assertThat(times.get(i))
                    .as("%d번째(%s)가 앞(%s)보다 늦어야 한다", i, times.get(i), times.get(i - 1))
                    .isAfter(times.get(i - 1));
            }
        }

        @Test
        @DisplayName("이동수단을 바꾸면 전체 시각이 함께 당겨진다")
        void travelModeShiftsSchedule() {
            List<RoutePlace> places = List.of(
                eastOf("a", SlotType.ATTRACTION, 0),
                eastOf("b", SlotType.CAFE, 3));

            RoutedDay onFoot = optimizer.optimize(
                new RouteRequest(1, places, null, null, TravelMode.WALK));
            RoutedDay byCar = optimizer.optimize(
                new RouteRequest(1, places, null, null, TravelMode.CAR));

            assertThat(byCar.places().get(1).startTime())
                .as("자차가 뚜벅이보다 먼저 도착한다")
                .isBefore(onFoot.places().get(1).startTime());
        }

        @Test
        @DisplayName("입력 순서를 그대로 유지한다 — 순서 선택은 다음 커밋의 일이다")
        void keepsInputOrderForNow() {
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                eastOf("멀리", SlotType.ATTRACTION, 10),
                eastOf("가까이", SlotType.CAFE, 0))));

            assertThat(RouteTestFixtures.namesOf(day)).containsExactly("멀리", "가까이");
        }

        @Test
        @DisplayName("장소가 없으면 빈 결과를 준다 — 예외가 아니다")
        void emptyInputProducesEmptyDay() {
            RoutedDay day = optimizer.optimize(RouteRequest.of(3, List.of()));

            assertThat(day.day()).isEqualTo(3);
            assertThat(day.places()).isEmpty();
            assertThat(day.droppedPlaces()).isEmpty();
            assertThat(day.startTime()).isEqualTo(day.endTime());
        }

        @Test
        @DisplayName("자정을 넘겨도 랩어라운드하지 않고 23:55 로 잘린다")
        void doesNotWrapAroundMidnight() {
            // 23:00 시작 + 관광 90분이면 다음 장소 도착이 자정을 넘는다.
            // LocalTime.plusMinutes 로 계산했다면 00:12 가 되어 순서가 뒤집혀 보였을 것이다.
            RoutedDay day = optimizer.optimize(new RouteRequest(1, List.of(
                eastOf("늦은관광", SlotType.ATTRACTION, 0),
                eastOf("심야카페", SlotType.CAFE, 1)),
                LocalTime.of(23, 0), LocalTime.of(23, 59), TravelMode.UNSPECIFIED));

            assertThat(day.places().get(1).startTime())
                .as("자정을 넘긴 시각은 23:55 로 잘린다")
                .isEqualTo(LocalTime.of(23, 55));
            assertThat(day.places().get(1).startTime())
                .as("00:12 로 랩어라운드하면 첫 장소보다 이른 시각이 된다")
                .isAfter(day.places().get(0).startTime());
        }
    }
}
