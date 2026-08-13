package backend.yourtrip.global.ai.route;

import static backend.yourtrip.global.ai.route.RouteTestFixtures.eastOf;
import static backend.yourtrip.global.ai.route.RouteTestFixtures.namesOf;
import static backend.yourtrip.global.ai.route.RouteTestFixtures.place;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code RouteOptimizer} 결정성 단위 테스트 (ROADMAP 3-3).
 *
 * <p><b>이 파일이 깨지면 다른 route 테스트가 전부 간헐 실패로 바뀐다.</b> 최적화기는 같은 입력에
 * 항상 같은 답을 내야 하는데, 여러 순열의 비용이 정확히 같은 상황(일직선 배치, 동일 좌표)이
 * 실제로 흔하기 때문이다. 그때 승자를 정하는 것이 무엇인지가 이 테스트의 주제다.
 *
 * <p>동점 규칙은 두 가지가 맞물려 만들어진다.
 * <ul>
 *   <li><b>사전순 백트래킹</b> — 순열을 인덱스 사전순으로 생성하므로, 먼저 만난 것이 이긴다.
 *       Heap's algorithm 은 더 빠르지만 사전순이 아니라 "왜 저 순서가 이겼는지"를 설명할 수 없다</li>
 *   <li><b>비용 비교의 epsilon</b> — 논리적으로 같은 비용도 합산 순서가 달라 마지막 비트가
 *       다를 수 있다. epsilon 이 없으면 승자를 부동소수점 잡음이 정하고, 좌표 리터럴을 조금만
 *       건드려도 뒤집힌다</li>
 * </ul>
 *
 * <p>결과적으로 <b>전부 동점이면 입력 순서가 그대로 유지된다.</b> 이건 {@code n >= 8} 폴백 경로가
 * 하는 일과 정확히 같아서, 두 경로가 같은 원칙을 따르게 된다.
 */
@DisplayName("RouteOptimizer — 결정성과 동점 처리 (ROADMAP 3-3)")
class RouteOptimizerDeterminismTest {

    private final RouteOptimizer optimizer = new RouteOptimizer();

    @Nested
    @DisplayName("같은 입력은 항상 같은 결과")
    class Repeatability {

        @Test
        @DisplayName("100번 반복해도 완전히 같은 결과가 나온다")
        void isRepeatable() {
            RouteRequest request = RouteRequest.of(1, List.of(
                eastOf("a", SlotType.ATTRACTION, 0),
                eastOf("b", SlotType.MEAL, 2.5),
                eastOf("c", SlotType.CAFE, 1.2),
                eastOf("d", SlotType.VIEWPOINT, 4.1),
                eastOf("e", SlotType.MEAL, 3.3)));

            RoutedDay first = optimizer.optimize(request);
            for (int i = 0; i < 100; i++) {
                assertThat(optimizer.optimize(request))
                    .as("%d번째 실행", i)
                    .isEqualTo(first);
            }
        }

        @Test
        @DisplayName("입력 순서를 섞어도 같은 방문 순서를 고른다 — 최적해가 유일한 배치에서")
        void isIndependentOfInputOrderWhenCostsDiffer() {
            // 경로 비용은 뒤집어도 같으므로, 거리만으로는 정방향과 역방향이 항상 동점이다.
            // 대칭을 깨는 것은 식사 시간창이다 — 식당을 한쪽 끝에 두면 역방향에서 09:30 점심이
            // 되어 큰 벌점을 물고, 그 결과 최적해가 유일해진다.
            List<RoutePlace> places = new ArrayList<>(List.of(
                eastOf("서쪽관광", SlotType.ATTRACTION, 0),
                eastOf("전망", SlotType.VIEWPOINT, 1),
                eastOf("카페", SlotType.CAFE, 2),
                eastOf("동쪽관광", SlotType.ATTRACTION, 3),
                eastOf("식당", SlotType.MEAL, 4)));

            List<String> expected = namesOf(optimizer.optimize(RouteRequest.of(1, places)));

            Random random = new Random(42);
            for (int i = 0; i < 20; i++) {
                List<RoutePlace> shuffled = new ArrayList<>(places);
                Collections.shuffle(shuffled, random);

                assertThat(namesOf(optimizer.optimize(RouteRequest.of(1, shuffled))))
                    .as("%d번째 셔플", i)
                    .isEqualTo(expected);
            }
        }
    }

    @Nested
    @DisplayName("동점 처리")
    class TieBreak {

        @Test
        @DisplayName("모든 장소가 같은 좌표·같은 종류면 입력 순서를 그대로 둔다")
        void keepsInputOrderWhenEverythingTies() {
            // 거리도 0, 슬롯도 같아 5! = 120 순열의 비용이 전부 같다.
            // 사전순으로 가장 앞선 순열은 항등 순열이므로 입력 순서가 살아남는다.
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                place("첫째", SlotType.ATTRACTION),
                place("둘째", SlotType.ATTRACTION),
                place("셋째", SlotType.ATTRACTION),
                place("넷째", SlotType.ATTRACTION),
                place("다섯째", SlotType.ATTRACTION))));

            assertThat(namesOf(day))
                .containsExactly("첫째", "둘째", "셋째", "넷째", "다섯째");
        }

        @Test
        @DisplayName("일직선 배치의 좌우 반전은 거리가 같지만 입력 순서 쪽이 이긴다")
        void prefersLexicographicallySmallestAmongTies() {
            // 0 / 2 / 4 km 일직선. 왼쪽에서 오른쪽(4km)과 오른쪽에서 왼쪽(4km)이 정확히 동점이다.
            // epsilon 이 없으면 승자를 부동소수점 마지막 비트가 정한다.
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                eastOf("서", SlotType.VIEWPOINT, 0),
                eastOf("중", SlotType.VIEWPOINT, 2),
                eastOf("동", SlotType.VIEWPOINT, 4))));

            assertThat(namesOf(day))
                .as("입력 순서(=사전순 최소)가 이긴다")
                .containsExactly("서", "중", "동");
        }

        @Test
        @DisplayName("입력을 뒤집으면 뒤집힌 순서가 이긴다 — 규칙이 좌표가 아니라 인덱스라는 증거")
        void tieBreakFollowsInputIndexNotGeography() {
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                eastOf("동", SlotType.VIEWPOINT, 4),
                eastOf("중", SlotType.VIEWPOINT, 2),
                eastOf("서", SlotType.VIEWPOINT, 0))));

            assertThat(namesOf(day)).containsExactly("동", "중", "서");
        }
    }

    @Nested
    @DisplayName("작은 입력")
    class SmallInputs {

        @Test
        @DisplayName("장소가 없으면 빈 결과다")
        void zeroPlaces() {
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of()));

            assertThat(day.places()).isEmpty();
            assertThat(day.droppedPlaces()).isEmpty();
        }

        @Test
        @DisplayName("장소가 하나면 순열이 하나뿐이다")
        void onePlace() {
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                place("대릉원", SlotType.ATTRACTION))));

            assertThat(namesOf(day)).containsExactly("대릉원");
            assertThat(day.startTime()).isEqualTo(day.places().get(0).startTime());
        }

        @Test
        @DisplayName("장소가 둘이면 두 순열의 거리가 같아 입력 순서가 유지된다")
        void twoPlacesTieOnDistance() {
            // 구간이 하나뿐이라 어느 쪽에서 출발해도 이동거리가 같다.
            RoutedDay day = optimizer.optimize(RouteRequest.of(1, List.of(
                eastOf("멀리", SlotType.ATTRACTION, 10),
                eastOf("가까이", SlotType.CAFE, 0))));

            assertThat(namesOf(day)).containsExactly("멀리", "가까이");
        }
    }
}
