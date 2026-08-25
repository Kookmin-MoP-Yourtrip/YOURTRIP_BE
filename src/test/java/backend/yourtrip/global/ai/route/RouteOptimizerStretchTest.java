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
 * {@code RouteOptimizer} 탄력 체류 단위 테스트 (이슈 #135).
 *
 * <p><b>이 테스트가 지키는 것은 "하루가 저녁까지 이어진다"이다.</b> 8단계 E2E 에서 만든 코스가
 * 전부 오후 1~4시에 끝났는데, 시간 모델 {@code t += 체류 + 이동}에는 시간을 뒤로 미는 장치가 없어
 * 슬롯이 다섯인 날은 저녁 식사가 15시에 도착했다. 최적화기는 순서를 바꿀 수만 있지 시간을 밀 수는
 * 없으므로, 벌점을 아무리 매겨도 이 위반은 고쳐지지 않는다.
 *
 * <p><b>대부분의 케이스가 좌표를 같은 점에 둔다.</b> 이동이 고정 오버헤드(자차 5분)만 남아
 * 기댓값을 손으로 검산할 수 있기 때문이다 — 탄력 체류는 "몇 분을 어디에 얹었는가"가 전부라
 * 거리 항이 섞이면 무엇을 검증하는지 흐려진다.
 *
 * <p>{@code RouteOptimizerOverrunTest}와 <b>정확히 반대 상황</b>을 다룬다. 저쪽은 하루가 넘칠 때
 * 줄이는 이야기이고 이쪽은 하루가 남을 때 늘리는 이야기다. 두 장치가 한 판에서 같이 돌지 않는다는
 * 것 자체가 아래 {@code ShrinkInteraction}의 주제다.
 */
@DisplayName("RouteOptimizer — 탄력 체류 (이슈 #135)")
class RouteOptimizerStretchTest {

    private final RouteOptimizer optimizer = new RouteOptimizer();

    /** 이동을 고정 오버헤드(5분)만 남긴다. 자차 + 같은 좌표. */
    private static RouteRequest sameSpot(List<RoutePlace> places) {
        return new RouteRequest(1, places, LocalTime.of(9, 30), null, TravelMode.CAR);
    }

    private static RoutedPlace at(RoutedDay day, int position) {
        return day.places().get(position);
    }

    private static RoutedPlace mealAt(RoutedDay day, int ordinal) {
        return day.places().stream()
            .filter(routed -> routed.place().slotType() == SlotType.MEAL)
            .toList()
            .get(ordinal);
    }

    @Nested
    @DisplayName("식사를 시간창까지 밀어 올린다")
    class PushesMealsIntoWindow {

        @Test
        @DisplayName("이슈의 최악 케이스 — 5슬롯 하루가 저녁 17:30에 닿는다")
        void fiveSlotDayReachesDinnerWindow() {
            // 탄력 체류가 없으면 저녁이 15:30 에 도착하고 하루가 16:45 에 끝난다.
            // 이 테스트가 이슈 #135 의 "저녁이 없는 하루"를 그대로 재현한 회귀 가드다.
            RoutedDay day = optimizer.optimize(sameSpot(List.of(
                place("관광1", SlotType.ATTRACTION),
                place("점심", SlotType.MEAL),
                place("카페", SlotType.CAFE),
                place("관광2", SlotType.ATTRACTION),
                place("저녁", SlotType.MEAL))));

            assertThat(mealAt(day, 1).startTime())
                .as("저녁이 시간창 시작에 닿아야 한다")
                .isEqualTo(LocalTime.of(17, 30));
            assertThat(day.endTime())
                .as("하루가 저녁 식사를 마치는 시각까지 이어진다")
                .isEqualTo(LocalTime.of(18, 45));
        }

        @Test
        @DisplayName("점심도 시간창까지 밀린다 — 식사가 하나뿐이어도 동작한다")
        void singleMealIsPushedToLunchWindow() {
            // 관광(90) + 이동(5) 이면 식사 도착이 11:05 로 창(11:30)보다 25분 이르다.
            RoutedDay day = optimizer.optimize(sameSpot(List.of(
                place("관광", SlotType.ATTRACTION),
                place("점심", SlotType.MEAL),
                place("카페", SlotType.CAFE))));

            assertThat(mealAt(day, 0).startTime()).isEqualTo(LocalTime.of(11, 30));
            assertThat(at(day, 0).stayMinutes())
                .as("25분이 앞 관광의 체류로 흡수된다")
                .isEqualTo(115);
        }

        @Test
        @DisplayName("늘린 체류는 슬롯 상한을 넘지 않는다")
        void neverExceedsSlotMaximum() {
            RoutedDay day = optimizer.optimize(sameSpot(List.of(
                place("관광1", SlotType.ATTRACTION),
                place("점심", SlotType.MEAL),
                place("카페", SlotType.CAFE),
                place("관광2", SlotType.ATTRACTION),
                place("저녁", SlotType.MEAL))));

            assertThat(day.places()).allSatisfy(routed ->
                assertThat(routed.stayMinutes())
                    .as("%s 의 체류", routed.place().name())
                    .isBetween(
                        routed.place().slotType().getDefaultStayMinutes(),
                        routed.place().slotType().getMaxStayMinutes()));
        }

        @Test
        @DisplayName("여력이 모자라면 당길 수 있는 만큼만 당기고 나머지는 벌점으로 남긴다")
        void absorbsWhatItCanWhenHeadroomRunsOut() {
            // 전망대는 여력이 15분씩(45 → 60), 점심은 15분(75 → 90)뿐이다. 셋을 전부 상한까지
            // 늘려도 저녁 도착이 13:15 라 창(17:30)에는 한참 못 미친다. 이때 남는 격차는
            // 억지로 메우지 않고 <b>기존 식사 벌점으로 넘긴다</b> — 대기를 넣지 않기로 한 결정이
            // 여기서 드러난다. 빈 시간을 만들어 17:30 을 맞추는 대신 이른 저녁을 감수한다.
            RoutedDay day = optimizer.optimize(sameSpot(List.of(
                place("전망1", SlotType.VIEWPOINT),
                place("전망2", SlotType.VIEWPOINT),
                place("점심", SlotType.MEAL),
                place("저녁", SlotType.MEAL))));

            assertThat(mealAt(day, 1).startTime())
                .as("창에는 못 닿지만 흡수한 만큼(12:30 → 13:15)은 뒤로 밀려야 한다")
                .isEqualTo(LocalTime.of(13, 15));
            assertThat(day.places()).extracting(RoutedPlace::stayMinutes)
                .as("앞 슬롯이 전부 상한까지 찼는데도 모자란다 — 여력의 바닥이 보이는 배치다")
                .containsExactly(60, 60, 90, 75);
        }
    }

    @Nested
    @DisplayName("타임라인에 빈 시간을 만들지 않는다")
    class NoIdleGaps {

        @Test
        @DisplayName("다음 도착 = 이전 도착 + 체류 + 이동이 그대로 성립한다")
        void arrivalsRemainContiguous() {
            // 대기(빈 시간) 대신 체류를 늘리기로 한 이유가 이 성질이다 — 구멍이 없으면 FE 가
            // "빈 시간"과 "긴 체류"를 구분해 그릴 필요가 없다.
            RoutedDay day = optimizer.optimize(sameSpot(List.of(
                place("관광1", SlotType.ATTRACTION),
                place("점심", SlotType.MEAL),
                place("카페", SlotType.CAFE),
                place("관광2", SlotType.ATTRACTION),
                place("저녁", SlotType.MEAL))));

            for (int i = 0; i < day.places().size() - 1; i++) {
                LocalTime expected = at(day, i).startTime()
                    .plusMinutes(at(day, i).stayMinutes())
                    .plusMinutes(TravelMode.CAR.getFixedOverheadMinutes());

                assertThat(at(day, i + 1).startTime())
                    .as("%d번째와 %d번째 사이에 빈 시간이 없어야 한다", i, i + 1)
                    .isEqualTo(expected);
            }
        }
    }

    @Nested
    @DisplayName("하루 종료를 넘기지 않는다")
    class RespectsDayEnd {

        @Test
        @DisplayName("남은 시간이 격차보다 적으면 남은 만큼만 늘린다")
        void stretchesOnlyWithinRemainingSlack() {
            // 종료를 15:00 로 좁히면 저녁 창(17:30)까지 늘릴 수 없다. 늘리다 하루를 넘기면
            // 축소·드롭이 되살아나 장소가 사라지므로, 늘리는 총량은 남은 시간으로 잘린다.
            RoutedDay day = optimizer.optimize(new RouteRequest(1, List.of(
                place("관광", SlotType.ATTRACTION),
                place("카페", SlotType.CAFE),
                place("저녁", SlotType.MEAL)),
                LocalTime.of(9, 30), LocalTime.of(15, 0), TravelMode.CAR));

            assertThat(day.endTime())
                .as("탄력 체류가 하루 종료를 새로 넘기면 안 된다")
                .isBeforeOrEqualTo(LocalTime.of(15, 0));
            assertThat(day.droppedPlaces())
                .as("늘리기 때문에 드롭이 발동해서는 안 된다")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("체류 축소와 함께 돌지 않는다")
    class ShrinkInteraction {

        @Test
        @DisplayName("축소가 발동한 판에서는 늘리지 않는다 — 줄인 체류를 되돌리지 않는다")
        void doesNotStretchAfterShrink() {
            // 종료를 12:30 으로 조이면 세 곳(90+60+75 + 이동 10)이 넘쳐 축소가 발동한다.
            // 이때 탄력 체류가 살아 있으면 0.8배로 줄인 체류를 상한까지 되늘려 축소가 헛돈다.
            RoutedDay day = optimizer.optimize(new RouteRequest(1, List.of(
                place("관광", SlotType.ATTRACTION),
                place("카페", SlotType.CAFE),
                place("점심", SlotType.MEAL)),
                LocalTime.of(9, 30), LocalTime.of(12, 30), TravelMode.CAR));

            assertThat(day.places()).extracting(RoutedPlace::stayMinutes)
                .as("0.8배로 줄인 값(72 / 48 / 60)이 그대로 남아야 한다")
                .containsExactlyInAnyOrder(72, 48, 60);
        }
    }

    @Nested
    @DisplayName("탐색이 흡수 여력까지 보고 순서를 고른다")
    class SearchIsStretchAware {

        @Test
        @DisplayName("여력이 있는 배치가 없는 배치를 이긴다")
        void prefersOrderThatCanAbsorbTheGap() {
            // 거리는 0으로 같고, 늘리기 <b>전</b> 비용도 225분으로 동점인 배치가 셋이다
            // (체험·점심 순서만 다르다). 늘리기를 탐색 뒤 후처리로 두면 이 동점을 사전순이
            // 갈라 [전망, 체험, 점심, 저녁]을 고르는데, 그 배치는 점심이 12:25 에 앉아
            // 점심 창의 남은 폭이 65분뿐이라 저녁을 15:05 까지밖에 못 민다.
            //
            // 흡수 여력은 <b>순열의 함수</b>다. 늘리기가 비용 안에 있어야 탐색이 그걸 보고
            // [체험, 점심, 전망, 저녁]을 고른다 — 점심을 체험 바로 뒤에 두면 점심 창을
            // 넓게 남겨 저녁이 15:15 까지 간다. 이 10분이 buildSchedule 통합의 근거다.
            RoutedDay day = optimizer.optimize(sameSpot(List.of(
                place("전망", SlotType.VIEWPOINT),
                place("점심", SlotType.MEAL),
                place("체험", SlotType.EXPERIENCE),
                place("저녁", SlotType.MEAL))));

            assertThat(day.places()).extracting(routed -> routed.place().name())
                .as("여력이 큰 체험(120 → 180)을 점심 앞에 두는 배치라야 저녁을 가장 멀리 민다")
                .containsExactly("체험", "점심", "전망", "저녁");
            assertThat(mealAt(day, 1).startTime())
                .as("사전순 승자였다면 15:05 에 그친다")
                .isEqualTo(LocalTime.of(15, 15));
        }
    }

    @Nested
    @DisplayName("결정론")
    class Determinism {

        @Test
        @DisplayName("같은 입력이면 늘린 체류까지 언제나 같다 — 분배에 부동소수점을 쓰지 않는다")
        void distributionIsRepeatable() {
            RouteRequest request = sameSpot(List.of(
                eastOf("관광", SlotType.ATTRACTION, 0),
                eastOf("점심", SlotType.MEAL, 1),
                eastOf("카페", SlotType.CAFE, 2),
                eastOf("쇼핑", SlotType.SHOPPING, 3),
                eastOf("저녁", SlotType.MEAL, 4)));

            RoutedDay first = optimizer.optimize(request);
            for (int i = 0; i < 50; i++) {
                assertThat(optimizer.optimize(request))
                    .as("%d번째 실행", i)
                    .isEqualTo(first);
            }
        }
    }
}
