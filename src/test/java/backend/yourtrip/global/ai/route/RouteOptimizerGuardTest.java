package backend.yourtrip.global.ai.route;

import static backend.yourtrip.global.ai.route.RouteTestFixtures.eastOf;
import static backend.yourtrip.global.ai.route.RouteTestFixtures.namesOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code RouteOptimizer}의 완전탐색 임계값 가드 테스트 (ROADMAP 3-3).
 *
 * <p>장소가 여덟 개를 넘으면 순서를 건드리지 않고 시각만 계산한다. 근사 알고리즘(NN + 2-opt)을
 * 붙이지 않은 것은 <b>현재 이 경로에 도달할 방법이 없기 때문</b>이다 — Planner 가 day 당 슬롯을
 * 3~6개로 제한한다. 도달하지 않는 코드를 만들면 검증되지 않은 채 썩고, 나중에 그 코드를 믿고
 * 임계값을 올렸다가 문제가 생긴다.
 *
 * <p>임계값 판정을 <b>진입 시 한 번만</b> 하는 것도 여기서 고정한다. 장소가 빠져 7개가 됐다고
 * 탐색을 재개하면 "8개짜리 날은 순서가 그대로인데, 초과된 8개짜리 날만 순서가 통째로 바뀐다"는
 * 설명하기 어려운 동작이 생긴다. 진입 시 1회면 규칙이 "입력이 8개 이상이면 순서를 건드리지
 * 않는다" 한 줄이다.
 */
@DisplayName("RouteOptimizer — 완전탐색 임계값 가드 (ROADMAP 3-3)")
class RouteOptimizerGuardTest {

    private final RouteOptimizer optimizer = new RouteOptimizer();

    /** 명백히 최적이 아닌 순서. 탐색이 돌면 반드시 재배열된다. */
    private static List<RoutePlace> zigzag(int count) {
        List<RoutePlace> places = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 0, 10, 1, 11, 2, 12 ... 로 좌우를 오가게 배치한다.
            double km = (i % 2 == 0) ? i / 2.0 : 10 + i / 2.0;
            places.add(eastOf("p" + i, SlotType.VIEWPOINT, km));
        }
        return places;
    }

    @Test
    @DisplayName("임계값은 7이다 — 벤치마크가 근거를 남기는 값")
    void thresholdIsSeven() {
        assertThat(RouteOptimizer.MAX_BRUTE_FORCE_PLACES).isEqualTo(7);
    }

    @Test
    @DisplayName("일곱 개까지는 지그재그를 펴준다")
    void optimisesUpToThreshold() {
        RoutedDay day = optimizer.optimize(RouteRequest.of(1, zigzag(7)));

        assertThat(namesOf(day))
            .as("탐색이 돌았다면 입력의 지그재그 순서가 남아 있지 않다")
            .isNotEqualTo(List.of("p0", "p1", "p2", "p3", "p4", "p5", "p6"));
    }

    @Test
    @DisplayName("여덟 개부터는 입력 순서를 그대로 둔다 — 지그재그도 펴지 않는다")
    void keepsInputOrderBeyondThreshold() {
        RoutedDay day = optimizer.optimize(RouteRequest.of(1, zigzag(8)));

        assertThat(namesOf(day))
            .containsExactly("p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7");
    }

    @Test
    @DisplayName("임계값을 넘겨도 시각 계산은 정상이다 — 순서만 손대지 않는다")
    void stillComputesTimesBeyondThreshold() {
        RoutedDay day = optimizer.optimize(RouteRequest.of(1, zigzag(8)));

        assertThat(day.places()).hasSize(8);
        List<java.time.LocalTime> times = day.places().stream()
            .map(RoutedPlace::startTime).toList();
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).isAfter(times.get(i - 1));
        }
    }

    @Test
    @DisplayName("벤치마크 이음매로 임계값을 올리면 여덟 개도 최적화된다")
    void seamAllowsRaisingThresholdForMeasurement() {
        RoutedDay guarded = optimizer.optimize(RouteRequest.of(1, zigzag(8)));
        RoutedDay searched = optimizer.optimize(RouteRequest.of(1, zigzag(8)), 8);

        assertThat(namesOf(guarded))
            .as("공개 진입점은 가드에 걸려 순서를 유지한다")
            .containsExactly("p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7");
        assertThat(namesOf(searched))
            .as("이음매로 부르면 탐색이 돌아 순서가 바뀐다 — 벤치마크가 재는 것이 이 경로다")
            .isNotEqualTo(namesOf(guarded));
    }
}
