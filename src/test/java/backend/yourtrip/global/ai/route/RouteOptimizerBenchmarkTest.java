package backend.yourtrip.global.ai.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 완전탐색 소요시간 실측 (ROADMAP 3-6).
 *
 * <p>{@code MAX_BRUTE_FORCE_PLACES = 7}이라는 임계값이 <b>감이 아니라 측정</b>이라는 근거를
 * 남기기 위한 것이다. 순열 수는 {@code n!}이라 6개(720)에서 9개(362,880)까지 500배 넘게
 * 벌어지는데, 그 구간 어디서 "1ms 미만"이 깨지는지를 숫자로 확인한다.
 *
 * <p><b>이 클래스가 {@code global/benchmark}가 아니라 여기 있는 이유.</b> 재야 하는 것이
 * {@code n=8,9}인데 공개 진입점은 그 구간에서 탐색을 하지 않는다(가드에 걸린다). 임계값을
 * 우회하려면 {@code RouteOptimizer}의 package-private 이음매를 불러야 하고, 그러려면 같은
 * 패키지에 있어야 한다. 임계값을 공개 파라미터로 노출하는 대안은 테스트 때문에 프로덕션 API가
 * 하나 늘어나므로 택하지 않았다. 실행 여부를 정하는 것은 패키지가 아니라 {@code @Tag}라
 * 기능적 손실은 없다.
 *
 * <p>JMH가 아니라 워밍업 후 반복 측정하는 수동 루프다 — 여기서 필요한 것은 정밀한 ns가 아니라
 * "마이크로초인지 밀리초인지 초인지"라는 자릿수이므로 JMH의 격리된 JVM과 통계적 오차범위까지는
 * 필요 없다고 판단했다. 이 한계는 결과 문서에도 명시한다.
 *
 * <p><b>소요시간에 상한을 단언하지 않는다.</b> 머신과 부하에 따라 몇 배씩 흔들리는 값이라
 * 단언을 걸면 간헐 실패하는 테스트가 된다. 측정하고 출력만 한다({@code SigningBenchmarkTest}와
 * 같은 방침).
 *
 * <p>일반 빌드(`./gradlew test`)에서는 실행되지 않는다 — build.gradle 의
 * `test { useJUnitPlatform { excludeTags 'benchmark' } }` 참고.
 */
@Tag("benchmark")
@DisplayName("RouteOptimizer 완전탐색 벤치마크 (ROADMAP 3-6)")
class RouteOptimizerBenchmarkTest {

    /**
     * 측정 반복 횟수를 순열 수에 반비례하게 잡는다.
     *
     * <p>고정 횟수로는 재지 못한다 — {@code n=9}는 {@code n=6}보다 순열이 500배 많아서, 2,000회를
     * 그대로 돌리면 순열 평가가 7억 번이 되어 측정에만 수십 초가 걸린다. 반복을 줄이면 편차가
     * 커지지만 여기서 필요한 것은 자릿수라 문제되지 않는다.
     */
    private static int measuredIterations(long permutations) {
        return (int) Math.max(20, Math.min(2_000, 4_000_000 / permutations));
    }

    private static int warmupIterations(long permutations) {
        return Math.max(5, measuredIterations(permutations) / 10);
    }

    /** 하네스 관례와 같은 시드. 케이스가 실행마다 달라지면 비교가 무의미해진다. */
    private static final long FIXED_SEED = 42L;

    /** 경주 시내 중심. 반경 5km 안에 장소를 흩는다 — 실제 하루 코스의 스케일이다. */
    private static final double CENTRE_LAT = 35.8347;
    private static final double CENTRE_LON = 129.2094;
    private static final double SPREAD_KM = 5.0;

    private final RouteOptimizer optimizer = new RouteOptimizer();

    @Test
    @DisplayName("n=6,7,8,9 의 1일치 소요시간을 재고 3일 코스 환산치를 함께 낸다")
    void benchmarkBruteForceScaling() {
        System.out.printf("%n=== RouteOptimizer 완전탐색 소요시간 (n = 장소 수) ===%n");
        System.out.printf("%3s %10s %14s %14s %14s%n",
            "n", "순열 수", "1일 (us)", "3일 (ms)", "순열당 (ns)");

        for (int size = 6; size <= 9; size++) {
            final int n = size;
            RouteRequest request = randomDay(n);
            long permutations = factorial(n);

            // 케이스 생성은 측정 루프 밖에서 한 번만 한다 — 좌표 생성 비용이 섞이면
            // 재는 대상이 흐려진다.
            long nsPerOp = measure(() -> optimizer.optimize(request, n), permutations);

            System.out.printf("%3d %10d %14.1f %14.2f %14.1f%n",
                n,
                permutations,
                nsPerOp / 1_000.0,
                nsPerOp * 3 / 1_000_000.0,
                (double) nsPerOp / permutations);

            assertThat(nsPerOp).isPositive();
        }

        System.out.printf("%n임계값 MAX_BRUTE_FORCE_PLACES = %d%n",
            RouteOptimizer.MAX_BRUTE_FORCE_PLACES);
        System.out.printf("순열당 비용이 n 에 무관하게 일정하면 n! 이 지배한다는 뜻이다.%n");
    }

    @Test
    @DisplayName("가드가 걸린 경로는 다항식으로만 늘어난다 — 폴백이 실제로 싼지 확인")
    void benchmarkGuardedPath() {
        // 순열을 돌지 않으므로 n! 이 사라지지만 상수 시간은 아니다 — 거리행렬이 O(n^2) 이고
        // haversine 은 삼각함수를 네 번 부른다. 장소가 스무 개여도 마이크로초 단위에 머무는지가
        // 확인 대상이다.
        System.out.printf("%n=== 가드 경로(입력 순서 유지, 시각만 계산) ===%n");

        for (int n : new int[] {8, 12, 20}) {
            RouteRequest request = randomDay(n);

            // 임계값을 1로 낮춰 항상 가드에 걸리게 한다.
            long nsPerOp = measure(() -> optimizer.optimize(request, 1), 1);

            System.out.printf("n=%2d : %8.1f us/op%n", n, nsPerOp / 1_000.0);
            assertThat(nsPerOp).isPositive();
        }
    }

    /**
     * 고정 시드로 경주 시내에 장소를 흩는다.
     *
     * <p>슬롯 종류를 섞는 것이 중요하다 — 식사가 하나도 없으면 비용 함수의 시간창 항이 통째로
     * 건너뛰어져 실제보다 싸게 측정된다.
     */
    private static RouteRequest randomDay(int n) {
        Random random = new Random(FIXED_SEED);
        SlotType[] rotation = {
            SlotType.ATTRACTION, SlotType.MEAL, SlotType.CAFE,
            SlotType.VIEWPOINT, SlotType.ACTIVITY, SlotType.MEAL, SlotType.WALK};

        // 위도 1도는 약 111km, 경도 1도는 이 위도에서 약 90km 다.
        double latSpread = SPREAD_KM / 111.0;
        double lonSpread = SPREAD_KM / (111.32 * Math.cos(Math.toRadians(CENTRE_LAT)));

        List<RoutePlace> places = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            places.add(new RoutePlace(
                "p" + i,
                "장소" + i,
                rotation[i % rotation.length],
                CENTRE_LAT + (random.nextDouble() - 0.5) * 2 * latSpread,
                CENTRE_LON + (random.nextDouble() - 0.5) * 2 * lonSpread));
        }
        return RouteRequest.of(1, places);
    }

    private long measure(Runnable op, long permutations) {
        int warmup = warmupIterations(permutations);
        int measured = measuredIterations(permutations);

        for (int i = 0; i < warmup; i++) {
            op.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < measured; i++) {
            op.run();
        }
        return (System.nanoTime() - start) / measured;
    }

    private static long factorial(int n) {
        long result = 1;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
