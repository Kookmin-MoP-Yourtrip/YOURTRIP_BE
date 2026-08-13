package backend.yourtrip.global.ai.route;

import static backend.yourtrip.global.ai.route.RouteTestFixtures.droppedNamesOf;
import static backend.yourtrip.global.ai.route.RouteTestFixtures.eastOf;
import static backend.yourtrip.global.ai.route.RouteTestFixtures.namesOf;
import static backend.yourtrip.global.ai.route.RouteTestFixtures.place;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code RouteOptimizer} 하루 초과 처리 단위 테스트 (ROADMAP 3-5).
 *
 * <p><b>테스트마다 종료 시각을 좁혀서 만든다.</b> 기본값 23:59는 하루 예산이 869분이라 장소 일곱
 * 개(체류 약 525분 + 이동 약 120분)를 넣어도 200분 넘게 남는다 — 기본값 그대로는 이 코드가
 * 실행되는 경로에 도달하지 못한다. 그 사실 자체가 설계의 일부다: 축소·드롭은 6단계에서 Planner 가
 * 이른 종료 시각을 넘길 때 켜지는 안전장치다.
 *
 * <p>가장 중요한 회귀 대상은 <b>드롭 서열</b>이다. {@code popularityWeight}는 블로그 언급량을
 * 랭킹에 얼마나 반영할지 정하는 <b>신호의 신뢰도</b>이지 중요도가 아닌데, 이름만 보면 중요도로
 * 읽힌다. 그걸 드롭 기준으로 쓰면 관광명소(0.2)가 카페(1.0)보다 먼저 버려진다 — "대릉원을 빼고
 * 카페를 남긴다". 아래 {@code dropsByValueNotByPopularityWeight}가 그 오독을 막는다.
 */
@DisplayName("RouteOptimizer — 하루 초과 처리 (ROADMAP 3-5)")
class RouteOptimizerOverrunTest {

    private final RouteOptimizer optimizer = new RouteOptimizer();

    /** 종료 시각을 좁혀 초과 경로를 실제로 지나게 한다. */
    private static RouteRequest until(LocalTime dayEnd, List<RoutePlace> places) {
        return new RouteRequest(1, places, LocalTime.of(9, 30), dayEnd, TravelMode.CAR);
    }

    @Nested
    @DisplayName("여유가 있으면 아무것도 하지 않는다")
    class NoOverrun {

        @Test
        @DisplayName("체류시간이 슬롯 기본값 그대로다")
        void keepsDefaultStayMinutes() {
            RoutedDay day = optimizer.optimize(until(LocalTime.of(23, 0), List.of(
                eastOf("관광", SlotType.ATTRACTION, 0),
                eastOf("카페", SlotType.CAFE, 1),
                eastOf("전망", SlotType.VIEWPOINT, 2))));

            assertThat(day.places()).extracting(RoutedPlace::stayMinutes)
                .as("90 / 60 / 45 가 줄지 않아야 한다")
                .containsExactlyInAnyOrder(90, 60, 45);
            assertThat(day.droppedPlaces()).isEmpty();
        }

        @Test
        @DisplayName("기본 종료 시각(23:59)에서는 일곱 개를 넣어도 초과하지 않는다")
        void defaultDayWindowFitsSevenPlaces() {
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                eastOf("a", SlotType.ATTRACTION, 0),
                eastOf("b", SlotType.CAFE, 1),
                eastOf("c", SlotType.MEAL, 2),
                eastOf("d", SlotType.VIEWPOINT, 3),
                eastOf("e", SlotType.ATTRACTION, 4),
                eastOf("f", SlotType.MEAL, 5),
                eastOf("g", SlotType.WALK, 6))));

            assertThat(day.places()).hasSize(7);
            assertThat(day.droppedPlaces())
                .as("기본값에서는 축소·드롭이 발동하지 않는다")
                .isEmpty();
            assertThat(day.places()).extracting(RoutedPlace::stayMinutes)
                .doesNotContain(72, 48, 36);
        }
    }

    @Nested
    @DisplayName("① 체류시간 축소")
    class Shrink {

        @Test
        @DisplayName("살짝 넘치면 체류시간을 0.8배로 줄여 해소한다 — 장소는 빼지 않는다")
        void shrinksBeforeDropping() {
            // 관광 90 + 카페 60 + 전망 45 = 195분에 자차 이동 2회(8분씩)를 더하면 13:01 에
            // 끝난다. 종료를 12:40 으로 두면 넘치고, 0.8배(72/48/36 = 156분)로 줄이면
            // 12:22 가 되어 안쪽으로 들어온다.
            RoutedDay day = optimizer.optimize(until(LocalTime.of(12, 40), List.of(
                eastOf("관광", SlotType.ATTRACTION, 0),
                eastOf("카페", SlotType.CAFE, 1),
                eastOf("전망", SlotType.VIEWPOINT, 2))));

            assertThat(day.droppedPlaces())
                .as("축소로 해결됐으면 드롭은 없어야 한다")
                .isEmpty();
            assertThat(day.places()).extracting(RoutedPlace::stayMinutes)
                .as("90->72, 60->48, 45->36")
                .containsExactlyInAnyOrder(72, 48, 36);
            assertThat(day.endTime()).isBeforeOrEqualTo(LocalTime.of(12, 40));
        }

        @Test
        @DisplayName("축소는 한 번뿐이다 — 0.64배까지 내려가지 않는다")
        void shrinksOnlyOnce() {
            // 아무리 좁혀도 해소되지 않는 종료 시각. 축소가 반복된다면 체류시간이
            // 72 아래(0.8^2 = 57)로 내려갔을 것이다.
            RoutedDay day = optimizer.optimize(until(LocalTime.of(10, 0), List.of(
                place("관광A", SlotType.ATTRACTION),
                place("관광B", SlotType.ATTRACTION),
                place("관광C", SlotType.ATTRACTION))));

            assertThat(day.places()).extracting(RoutedPlace::stayMinutes)
                .as("90 -> 72 에서 멈춘다")
                .containsOnly(72);
        }
    }

    @Nested
    @DisplayName("② 후순위 드롭")
    class Drop {

        @Test
        @DisplayName("축소로 부족하면 장소를 빼고, 뺀 것을 droppedPlaces 에 남긴다")
        void recordsDroppedPlaces() {
            RoutedDay day = optimizer.optimize(until(LocalTime.of(14, 0), List.of(
                place("관광", SlotType.ATTRACTION),
                place("체험", SlotType.ACTIVITY),
                place("식당", SlotType.MEAL),
                place("쇼핑", SlotType.SHOPPING),
                place("산책", SlotType.WALK))));

            assertThat(day.droppedPlaces()).isNotEmpty();
            assertThat(day.places().size() + day.droppedPlaces().size())
                .as("장소가 증발하지 않는다 — 남은 것과 뺀 것의 합이 입력과 같다")
                .isEqualTo(5);
        }

        @Test
        @DisplayName("쇼핑·산책로부터 빠지고 관광명소는 끝까지 남는다")
        void dropsLowValueSlotsFirst() {
            RoutedDay day = optimizer.optimize(until(LocalTime.of(13, 30), List.of(
                place("관광", SlotType.ATTRACTION),
                place("쇼핑", SlotType.SHOPPING),
                place("산책", SlotType.WALK),
                place("식당", SlotType.MEAL))));

            assertThat(droppedNamesOf(day)).contains("쇼핑");
            assertThat(namesOf(day))
                .as("그날의 목적인 관광명소는 남는다")
                .contains("관광");
        }

        @Test
        @DisplayName("popularityWeight 를 드롭 기준으로 쓰지 않는다 — 카페가 관광명소보다 먼저 빠진다")
        void dropsByValueNotByPopularityWeight() {
            // popularityWeight 는 카페 1.0 > 관광명소 0.2 다. 그걸 중요도로 오독하면
            // 관광명소가 먼저 버려진다. 여기서는 반대가 나와야 한다.
            RoutedDay day = optimizer.optimize(until(LocalTime.of(13, 0), List.of(
                place("대릉원", SlotType.ATTRACTION),
                place("첨성대", SlotType.ATTRACTION),
                place("카페", SlotType.CAFE),
                place("식당", SlotType.MEAL))));

            assertThat(droppedNamesOf(day))
                .as("카페(1.0)가 관광명소(0.2)보다 먼저 빠져야 한다")
                .contains("카페");
            assertThat(namesOf(day)).contains("대릉원", "첨성대");
        }

        @Test
        @DisplayName("식사는 드롭 대상이 아니다 — day 당 최소 한 끼가 보장된다")
        void neverDropsMeal() {
            RoutedDay day = optimizer.optimize(until(LocalTime.of(12, 30), List.of(
                place("식당", SlotType.MEAL),
                place("쇼핑", SlotType.SHOPPING),
                place("산책", SlotType.WALK),
                place("전망", SlotType.VIEWPOINT),
                place("카페", SlotType.CAFE))));

            assertThat(droppedNamesOf(day)).doesNotContain("식당");
            assertThat(namesOf(day)).contains("식당");
        }

        @Test
        @DisplayName("같은 종류가 여럿이면 뒤에 있는 것부터 뺀다")
        void dropsLaterOfSameSlotType() {
            RoutedDay day = optimizer.optimize(until(LocalTime.of(13, 0), List.of(
                place("식당", SlotType.MEAL),
                place("관광", SlotType.ATTRACTION),
                place("첫쇼핑", SlotType.SHOPPING),
                place("둘째쇼핑", SlotType.SHOPPING))));

            assertThat(droppedNamesOf(day)).containsExactly("둘째쇼핑");
        }
    }

    @Nested
    @DisplayName("③ 최소 세 개에서 중단")
    class MinimumPlaces {

        @Test
        @DisplayName("세 개가 남으면 초과인 채로 반환한다 — 코스의 형태를 지킨다")
        void stopsAtThreePlaces() {
            RoutedDay day = optimizer.optimize(until(LocalTime.of(10, 30), List.of(
                place("관광A", SlotType.ATTRACTION),
                place("관광B", SlotType.ATTRACTION),
                place("쇼핑", SlotType.SHOPPING),
                place("산책", SlotType.WALK),
                place("전망", SlotType.VIEWPOINT))));

            assertThat(day.places())
                .as("두 개까지 줄이지 않는다")
                .hasSize(3);
            assertThat(day.endTime())
                .as("맞추지 못한 채로 끝난다 — 시간보다 코스 형태를 택했다")
                .isAfter(LocalTime.of(10, 30));
        }

        @Test
        @DisplayName("입력이 처음부터 세 개 이하면 드롭 없이 축소만 한다")
        void neverDropsWhenInputIsAlreadySmall() {
            RoutedDay day = optimizer.optimize(until(LocalTime.of(10, 0), List.of(
                place("관광", SlotType.ATTRACTION),
                place("카페", SlotType.CAFE))));

            assertThat(day.places()).hasSize(2);
            assertThat(day.droppedPlaces()).isEmpty();
            assertThat(day.places()).extracting(RoutedPlace::stayMinutes)
                .as("축소는 적용된다")
                .containsExactlyInAnyOrder(72, 48);
        }
    }

    @Nested
    @DisplayName("임계값을 넘긴 입력에서도 초과 처리는 동작한다")
    class BeyondBruteForceThreshold {

        @Test
        @DisplayName("여덟 개 입력은 순서를 유지한 채로 축소·드롭된다")
        void keepsOrderWhileDropping() {
            List<RoutePlace> places = List.of(
                place("a", SlotType.ATTRACTION),
                place("b", SlotType.ATTRACTION),
                place("c", SlotType.ATTRACTION),
                place("d", SlotType.SHOPPING),
                place("e", SlotType.SHOPPING),
                place("f", SlotType.SHOPPING),
                place("g", SlotType.SHOPPING),
                place("h", SlotType.SHOPPING));

            RoutedDay day = optimizer.optimize(until(LocalTime.of(12, 0), places));

            assertThat(day.droppedPlaces()).isNotEmpty();
            assertThat(namesOf(day))
                .as("남은 장소의 상대 순서가 입력과 같다 — 드롭으로 7개가 돼도 탐색을 재개하지 않는다")
                .isSorted();
        }
    }
}
