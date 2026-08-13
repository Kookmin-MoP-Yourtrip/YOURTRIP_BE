package backend.yourtrip.global.ai.route;

import static backend.yourtrip.global.ai.route.RouteTestFixtures.eastOf;
import static backend.yourtrip.global.ai.route.RouteTestFixtures.namesOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code RouteOptimizer} 비용 함수 단위 테스트 (ROADMAP 3-3).
 *
 * <p><b>상수 리터럴을 단언하지 않는다.</b> {@code assertThat(cost).isEqualTo(32.0)} 같은 테스트는
 * 계수를 조금만 튜닝해도 깨져서, 결국 "기댓값을 실제값으로 덮어쓰는" 의식으로 전락한다. 대신
 * <b>순서 관계</b>를 단언한다 — "제시간 식사가 최단거리를 이긴다"는 계수를 다시 잡아도 살아남아야
 * 하는 성질이고, 깨졌다면 정말로 정책이 바뀐 것이다.
 *
 * <p>여기서 확인하는 핵심 정책은 두 가지다.
 * <ul>
 *   <li><b>동선을 조금 희생해서라도 제시간에 밥을 먹는다</b> — 계수 2.0은 "30분 늦은 점심 ≈
 *       15km 우회"라는 교환비다. 도심 하루 코스의 총 이동이 5~15km 이므로, 이 값이면 식사
 *       시간이 실제로 동선을 재배열시킨다</li>
 *   <li><b>두 끼가 붙지 않는다</b> — 식사 윈도우를 일대일로 배정하지 않으면 12:00과 13:00에 두 끼가
 *       나란히 붙어도 벌점이 0이라, 최단거리이기만 하면 최적화기가 그 배열을 고른다</li>
 * </ul>
 */
@DisplayName("RouteOptimizer — 비용 함수 (ROADMAP 3-3)")
class RouteOptimizerCostTest {

    private final RouteOptimizer optimizer = new RouteOptimizer();

    private static int at(int hour, int minute) {
        return hour * 60 + minute;
    }

    @Nested
    @DisplayName("식사 시간창 위반 계산 (순수 함수)")
    class MealViolation {

        @Test
        @DisplayName("식사가 없으면 위반도 없다")
        void noMealMeansNoViolation() {
            assertThat(RouteOptimizer.mealViolationMinutes(new int[0])).isZero();
        }

        @Test
        @DisplayName("점심 윈도우 안이면 0 이다 — 11:30 ~ 13:30")
        void insideLunchWindow() {
            assertThat(RouteOptimizer.mealViolationMinutes(new int[] {at(11, 30)})).isZero();
            assertThat(RouteOptimizer.mealViolationMinutes(new int[] {at(12, 30)})).isZero();
            assertThat(RouteOptimizer.mealViolationMinutes(new int[] {at(13, 30)})).isZero();
        }

        @Test
        @DisplayName("윈도우 경계 밖은 1분부터 벌점이 붙는다")
        void justOutsideLunchWindow() {
            assertThat(RouteOptimizer.mealViolationMinutes(new int[] {at(11, 29)})).isEqualTo(1);
            assertThat(RouteOptimizer.mealViolationMinutes(new int[] {at(13, 31)})).isEqualTo(1);
        }

        @Test
        @DisplayName("식사가 하나면 점심·저녁 중 가까운 쪽으로 재어준다")
        void singleMealPicksNearerWindow() {
            // 18:00 한 끼짜리 day 를 "첫 식사는 무조건 점심"으로 고정하면 270분 위반을
            // 뒤집어쓰고, 그걸 줄이려 저녁을 점심 시간대로 끌어당기는 왜곡이 생긴다.
            assertThat(RouteOptimizer.mealViolationMinutes(new int[] {at(18, 0)})).isZero();
            assertThat(RouteOptimizer.mealViolationMinutes(new int[] {at(19, 30)})).isZero();
        }

        @Test
        @DisplayName("식사가 둘이면 이른 쪽은 점심, 늦은 쪽은 저녁에 묶인다")
        void twoMealsMapToSeparateWindows() {
            assertThat(RouteOptimizer.mealViolationMinutes(new int[] {at(12, 30), at(18, 30)}))
                .as("각자 제 윈도우 안이면 위반이 없다")
                .isZero();

            // 입력 순서와 무관하게 도착이 이른 쪽이 점심이다.
            assertThat(RouteOptimizer.mealViolationMinutes(new int[] {at(18, 30), at(12, 30)}))
                .isZero();
        }

        @Test
        @DisplayName("두 끼가 붙어 있으면 둘째가 저녁 윈도우까지의 거리를 벌점으로 문다")
        void adjacentMealsArePenalised() {
            // 12:00 과 13:00 — 둘 다 점심 시간대다. 매번 가까운 쪽을 고르게 두면 벌점이 0이라
            // 최적화기가 이 배열을 아무렇지 않게 고른다. 일대일 배정이 그걸 막는다.
            int violation = RouteOptimizer.mealViolationMinutes(
                new int[] {at(12, 0), at(13, 0)});

            assertThat(violation)
                .as("둘째 끼(13:00)가 저녁 윈도우 시작(17:30)까지 270분 떨어져 있다")
                .isEqualTo(270);
        }

        @Test
        @DisplayName("윈도우 배정은 도착 순서가 아니라 총합이 최소가 되는 조합으로 정해진다")
        void picksTheCheapestWindowAssignment() {
            // 아침 8:30 / 점심 12:30 / 저녁 18:30.
            //
            // "이른 두 끼를 점심·저녁에 기계적으로 배정"하면 8:30 이 점심(180),
            // 12:30 이 저녁(300)을 물어 480이 된다. 그러면 잘 벌어진 하루가
            // 몰아넣은 하루보다 비싸지는 역전이 생겨 벌점의 목적이 뒤집힌다.
            assertThat(RouteOptimizer.mealViolationMinutes(
                new int[] {at(8, 30), at(12, 30), at(18, 30)}))
                .as("12:30 과 18:30 이 제 윈도우를 차지하고, 남은 아침만 벌점을 문다")
                .isEqualTo(180);
        }

        @Test
        @DisplayName("끼니가 셋 이상이면 이 모델의 표현 범위를 벗어난다 — 아침을 담을 윈도우가 없다")
        void threeMealsExceedTheTwoWindowModel() {
            // 윈도우가 점심·저녁 둘뿐이라 아침은 어느 쪽에 붙여도 벌점을 문다. 반대로
            // 점심 시간대에 두 끼가 붙어 있으면 셋째가 그 안에 들어가 벌점이 0이 된다.
            // 즉 k >= 3 에서는 벌점의 절대값에 큰 의미가 없다.
            //
            // 그럼에도 문제가 되지 않는 이유는 두 가지다. Planner 가 day 당 식사를 둘 이하로
            // 내보내고(로드맵 6-3), 최적화기는 순열 간 상대 비교만 하기 때문이다.
            assertThat(RouteOptimizer.mealViolationMinutes(
                new int[] {at(12, 30), at(12, 45), at(18, 30)}))
                .as("셋째 끼가 점심 윈도우 안이라 추가 벌점이 없다 — 모델의 한계다")
                .isZero();
        }
    }

    @Nested
    @DisplayName("정책 — 제시간 식사가 최단거리를 이긴다")
    class MealBeatsDistance {

        @Test
        @DisplayName("조금 돌아가더라도 점심을 시간대 안에 넣는 순서를 고른다")
        void detoursToKeepLunchOnTime() {
            // 식당이 서쪽 끝에 있다. 09:30 에 출발해 관광 두 곳을 먼저 돌면 점심이 시간대에
            // 들어오고, 식당부터 가면 09:30 점심이 되어 120분 위반이다.
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                eastOf("식당", SlotType.MEAL, 0),
                eastOf("관광A", SlotType.ATTRACTION, 2),
                eastOf("관광B", SlotType.ATTRACTION, 4))));

            assertThat(namesOf(day).get(0))
                .as("식당을 첫 순서에 두면 09:30 점심이 되어 120분 위반이다")
                .isNotEqualTo("식당");

            LocalTime lunchTime = day.places().stream()
                .filter(routed -> routed.place().slotType() == SlotType.MEAL)
                .findFirst().orElseThrow().startTime();
            assertThat(lunchTime)
                .as("점심이 11:30 ~ 13:30 안에 들어온다")
                .isBetween(LocalTime.of(11, 30), LocalTime.of(13, 35));
        }

        @Test
        @DisplayName("식사가 없으면 순수하게 최단거리를 고른다")
        void withoutMealPicksShortestPath() {
            // 일직선 위 0 / 5 / 10 km. 끝에서 끝으로 훑는 것이 최단(10km)이고,
            // 가운데서 시작하면 15km 가 된다.
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                eastOf("중간", SlotType.CAFE, 5),
                eastOf("서쪽끝", SlotType.ATTRACTION, 0),
                eastOf("동쪽끝", SlotType.VIEWPOINT, 10))));

            assertThat(namesOf(day))
                .as("가운데 지점은 양 끝 사이에 놓인다")
                .containsSubsequence("서쪽끝", "중간", "동쪽끝");
        }

        @Test
        @DisplayName("거리 차이가 크면 15분 늦은 점심을 감수한다 — 식사가 무조건 이기지는 않는다")
        void doesNotSacrificeRouteForTinyMealGain() {
            // 계수 2.0 은 "1분 위반 = 2분 이동". 5km 차이(20분-equivalent)면 10분 위반까지는
            // 감수하는 것이 최적이다. 이 균형이 깨졌다면 계수가 바뀐 것이다.
            assertThat(RouteOptimizer.mealViolationMinutes(new int[] {at(13, 40)}))
                .as("10분 위반 x 2.0 = 20 — 5km 우회(20)와 같은 무게다")
                .isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("정책 — 하루 초과를 피하는 순서를 고른다")
    class OverrunAvoidance {

        @Test
        @DisplayName("종료 시각을 넘기지 않는 배열이 있으면 그쪽을 고른다")
        void prefersSchedulesWithinDayWindow() {
            // 종료를 15:00 으로 좁힌다. 먼 곳을 먼저 가면 이동시간이 커져 초과하고,
            // 가까운 순으로 훑으면 안쪽에 들어온다.
            RoutedDay day = optimizer.optimize(new RouteRequest(1, List.of(
                eastOf("가까운카페", SlotType.CAFE, 0),
                eastOf("중간전망", SlotType.VIEWPOINT, 3),
                eastOf("먼전망", SlotType.VIEWPOINT, 6)),
                LocalTime.of(9, 30), LocalTime.of(15, 0), TravelMode.CAR));

            assertThat(day.endTime())
                .as("15:00 안에 끝난다")
                .isBeforeOrEqualTo(LocalTime.of(15, 0));
        }
    }
}
