package backend.yourtrip.global.ai.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.ai.route.RouteInputCsv.PlaceRow;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 식사 벌점 계수 스윕 (ROADMAP 3-8).
 *
 * <p>3-7이 답하지 못한 질문 하나를 잇는다 — <b>{@code MEAL_PENALTY_PER_MIN = 2.0}이 적정한가.</b>
 * 3-7은 계수를 고정한 채 완전탐색만 껐다 켰으므로, "식사 2개짜리 day 의 양보율 15.7%가 비싼지
 * 싼지"에는 답이 없었다. 여기서는 <b>계수를 실제로 움직여</b> 거리와 식사 시각이 어떤 비율로
 * 맞바뀌는지를 잰다.
 *
 * <h2>이 스윕이 답할 수 없는 것부터 못 박는다</h2>
 *
 * <p>거리와 식사 시각은 맞바꾸는 관계라, 어느 쪽이 나은지는 측정이 아니라 <b>선호</b>가 정한다.
 * λ를 아무리 촘촘히 쓸어도 "최적 λ"는 나오지 않는다. 그래서 <b>바꿀 근거로 인정할 것을 두
 * 가지로 미리 한정한다</b> — 이 한정이 없으면 결과를 본 뒤에 유리한 λ를 고르게 된다.
 *
 * <ul>
 *   <li><b>(a) 지배</b> — 어떤 λ가 현행 2.0보다 거리와 식사 위반이 <b>둘 다</b> 작다</li>
 *   <li><b>(b) 포화</b> — 어느 지점 위로는 위반이 더 줄지 않는데 거리만 늘어난다. 그 포화점이
 *       2.0보다 <b>아래</b>면 2.0은 낭비다</li>
 * </ul>
 *
 * <p>둘 다 아니면 <b>"이 표본으로는 바꿀 근거가 없다"로 기각</b>한다. 기각도 결론이다.
 * 판정이 눈대중에 맡겨지지 않도록 <b>지배·포화는 이 하네스가 직접 계산해 출력한다.</b>
 *
 * <p>채택 여부와 무관하게 반드시 남기는 산출물은 <b>인접 λ 사이의 {@code Δkm / Δ위반분}</b>,
 * 즉 "1분 일찍 밥을 먹으려고 몇 km 를 더 걷는가"다. 계수를 정할 때 손으로 어림잡은 환산
 * ("30분 늦은 점심 ≈ 15km 우회")을 실제 코스 90 day 에서 검증하는 값이다.
 *
 * <h2>축이 하나로 충분한 이유</h2>
 *
 * <p>순열 선택은 {@code 거리×4.0 + 위반×λ + 초과×3.0}의 <b>비</b>에만 의존하는데, 이 표본은
 * 초과가 0건이다(기본 {@code dayEndTime} 23:59). 따라서 {@code DISTANCE_WEIGHT}를 함께 쓸 필요가
 * 없고 <b>λ 하나로 비 공간 전체가 덮인다.</b> 초과가 정말 0건인지는 λ 마다 확인한다 — 드롭이
 * 생긴 day 는 장소 집합이 달라져 비교가 깨지므로 표본에서 통째로 뺀다.
 *
 * <h2>외부 호출이 없다</h2>
 *
 * <p>3-7과 같은 CSV 를 읽고 같은 자로 잰다({@link RouteInputCsv}, {@link RouteGeometry}).
 * 네트워크를 쓰지 않으므로 <b>같은 CSV 면 몇 번을 돌려도 산출물이 바이트 단위로 같고</b>, 계수를
 * 아홉 번 쓸어도 LLM 비용이 0이다. 3-7이 채집과 계산을 갈라 둔 덕이다.
 *
 * <pre>
 * ./gradlew benchmarkTest --tests '*RouteMealPenaltySweepTest*' --rerun
 * </pre>
 */
@Tag("benchmark")
@DisplayName("식사 벌점 계수 스윕 (ROADMAP 3-8)")
class RouteMealPenaltySweepTest {

    /** {@code AiCourseRouteInputProbeTest}가 남긴 산출물. 3-7과 같은 입력이다. */
    private static final String DEFAULT_SOURCE =
        "docs/tasks/ai-course-create/route/artifacts/route-pipeline-places-20260826.csv";

    private static final Path RESULTS_DIR = Path.of("results");

    /** day 하나가 표본이 되기 위한 최소 장소 수. 점이 둘이면 순서를 뒤집어도 거리가 같다. */
    private static final int MIN_PLACES = 3;

    /** 현행 프로덕션 계수. 이 값이 기준선이고, 채택 판정은 전부 이것과의 비교다. */
    private static final double PRODUCTION_LAMBDA = 2.0;

    /**
     * 쓸어볼 계수들.
     *
     * <p><b>0.0을 넣는 것이 중요하다.</b> 비용이 사실상 거리만 남는 극단이라
     * {@link RouteGeometry#shortestPathKm}와 값이 일치해야 하고(판정 기준 4), 동시에
     * <b>판정 8의 인과 검증</b>이 된다 — "남은 자기교차의 원인이 식사 시간창"이라는 주장이
     * 옳다면 여기서 교차가 사라져야 한다.
     *
     * <p>위쪽으로 16.0까지 벌린 것은 <b>포화를 확인하기 위해서</b>다. 위반이 더는 줄지 않는
     * 구간이 보여야 "2.0이 포화 안쪽인가 바깥인가"를 말할 수 있다. 계산에 외부 호출이 없어
     * 팔을 늘리는 비용이 사실상 0이다.
     *
     * <p><b>1.0~2.0 사이 셋은 1차 실행 뒤에 더했다.</b> 처음 격자
     * ({@code 0, 0.25, 0.5, 1, 2, 3, 4, 8, 16})로는 위반이 0이 되는 지점이 1.0과 2.0 <b>사이
     * 어딘가</b>라는 것까지만 보여, 판정 기준 (b)를 풀 수 없었다. 바꾼 것은 <b>판정 기준이 아니라
     * 해상도</b>다 — 유리한 값을 찾으려고 축을 옮긴 것이 아니라, 미리 정해 둔 질문에 답할 수
     * 있을 만큼 같은 축을 촘촘하게 했다. 1차 결과는 STEP-3 에 그대로 남겼다.
     */
    private static final double[] LAMBDAS =
        {0.0, 0.25, 0.5, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0, 8.0, 16.0};

    /** 두 값이 같다고 볼 허용 오차. 거리 규모가 수백 km 라 1e-6은 상대오차 1e-9 안쪽이다. */
    private static final double EPSILON = 1e-6;

    private final RouteOptimizer optimizer = new RouteOptimizer();

    @Test
    @DisplayName("식사 벌점을 쓸어 거리와 식사 시각의 교환비를 잰다")
    void sweepMealPenalty() throws IOException {
        Path source = Path.of(System.getenv().getOrDefault("ROUTE_EFFECT_FROM", DEFAULT_SOURCE));
        assumeTrue(Files.exists(source),
            "캡처된 최적화 직전 입력이 필요하다: " + source
                + " (AiCourseRouteInputProbeTest 를 먼저 돌려 채집한다)");

        List<PlaceRow> rows = RouteInputCsv.readPlaces(source);
        List<DaySpec> days = buildDays(rows);
        System.out.printf("%n=== 입력: %s (%d행 → day %d개) ===%n", source, rows.size(), days.size());

        List<DaySample> samples = measure(days);
        writeCsv(samples);

        reportSweep("A. λ 스윕 — 전량", samples, sample -> true);
        reportExchangeRate(samples);
        reportSweep("B. λ 스윕 — FAMOUS", samples, sample -> "FAMOUS".equals(sample.regionTier()));
        reportSweep("C. λ 스윕 — MINOR", samples, sample -> "MINOR".equals(sample.regionTier()));
        reportMealBreakdown(samples);
        reportVerdict(samples);

        assertThat(samples).as("유효 표본이 하나도 없다").isNotEmpty();
    }

    // ── 표본 확정 ─────────────────────────────────────────────────────────────

    /**
     * {@code (requestId, day)}마다 하루치 요청을 만든다. {@code n < 3}은 여기서 걸러진다.
     *
     * <p>{@code placeIndex} 오름차순이 곧 최적화 전 순서다 — Planner 가 낸 슬롯 순서이고,
     * {@link RouteOptimizer}가 없었다면 그대로 저장됐을 순서다.
     */
    private static List<DaySpec> buildDays(List<PlaceRow> rows) {
        Map<String, List<PlaceRow>> byDay = new LinkedHashMap<>();
        for (PlaceRow row : rows) {
            byDay.computeIfAbsent(row.requestId() + "/" + row.day(), key -> new ArrayList<>())
                .add(row);
        }

        List<DaySpec> days = new ArrayList<>();
        int tooSmall = 0;
        for (List<PlaceRow> dayRows : byDay.values()) {
            dayRows.sort(Comparator.comparingInt(PlaceRow::placeIndex));
            if (dayRows.size() < MIN_PLACES) {
                tooSmall++;
                continue;
            }

            List<RoutePlace> places = new ArrayList<>(dayRows.size());
            for (PlaceRow row : dayRows) {
                places.add(new RoutePlace(
                    row.requestId() + "-" + row.day() + "-" + row.placeIndex(),
                    row.placeName(), row.slotType(), row.latitude(), row.longitude()));
            }

            // 시작 시각과 이동수단은 CSV 가 실어 온 것을 그대로 쓴다 — 둘 다 최적화 입력이라
            // 기본값으로 덮으면 운영에서 나온 순서를 재현하지 못한다.
            PlaceRow head = dayRows.get(0);
            days.add(new DaySpec(head, new RouteRequest(
                head.day(), places, head.dayStartTime(), null, head.travelMode())));
        }
        System.out.printf("  [제외] n < %d 인 day %d개%n", MIN_PLACES, tooSmall);
        return days;
    }

    /**
     * day 마다 λ 전부를 돌린다.
     *
     * <p><b>표본은 λ 전체에 공통이어야 한다</b>(판정 기준 3). 어느 한 λ 에서라도 하루 초과로
     * 장소가 빠지면 그 day 는 λ 마다 장소 집합이 달라져, 그리는 것이 곡선이 아니라 표본 변화가
     * 된다. 그래서 <b>한 팔이라도 드롭이 생기면 그 day 를 통째로 뺀다.</b>
     */
    private List<DaySample> measure(List<DaySpec> days) {
        List<DaySample> samples = new ArrayList<>();
        int dropped = 0;

        for (DaySpec day : days) {
            RouteRequest request = day.request();
            List<RoutePlace> places = request.places();

            RoutedDay before = optimizer.optimize(request, 1);
            Map<Double, ArmResult> arms = new LinkedHashMap<>();
            boolean usable = before.droppedPlaces().isEmpty();

            for (double lambda : LAMBDAS) {
                RoutedDay after =
                    optimizer.optimize(request, RouteOptimizer.MAX_BRUTE_FORCE_PLACES, lambda);
                if (!after.droppedPlaces().isEmpty()) {
                    usable = false;
                    break;
                }

                List<RoutePlace> order = orderOf(after);
                assertThat(order)
                    .as("λ=%.2f 팔이 같은 장소 집합을 재배열한 것이어야 한다", lambda)
                    .containsExactlyInAnyOrderElementsOf(places);

                // 판정 기준 5 — before 는 탐색을 끄므로 비용 함수를 부르지 않는다. λ 가 달라도
                // 같은 순서·같은 거리가 나와야 하고, 아니면 실험 설계 자체가 틀린 것이다.
                assertThat(orderOf(optimizer.optimize(request, 1, lambda)))
                    .as("before 팔은 λ 와 무관해야 한다 (λ=%.2f)", lambda)
                    .containsExactlyElementsOf(orderOf(before));

                arms.put(lambda, new ArmResult(
                    RouteGeometry.totalDistanceKm(order),
                    mealViolationMinutes(after, request),
                    RouteGeometry.selfIntersections(order),
                    RouteGeometry.backtrackVertices(order)));
            }

            if (!usable) {
                dropped++;
                System.out.printf("  [제외] req %d %s day %d — 하루 초과로 장소가 빠졌다%n",
                    day.head().requestId(), day.head().location(), day.head().day());
                continue;
            }

            List<RoutePlace> beforeOrder = orderOf(before);
            assertThat(beforeOrder)
                .as("before 팔은 입력 순서를 그대로 유지해야 한다")
                .containsExactlyElementsOf(places);

            // 판정 기준 4 — λ=0 이면 비용에 거리 항만 남으므로(이 표본은 초과가 0이다) 프로덕션이
            // 고른 순열의 거리가 하네스가 직접 찾은 최소 거리와 같아야 한다. 두 경로가 서로를
            // 검산하는 자리다 — 어긋나면 곡선을 읽기 전에 둘 중 하나가 틀린 것이다.
            double shortestKm = RouteGeometry.shortestPathKm(places);
            assertThat(arms.get(0.0).afterKm())
                .as("λ=0 의 거리가 최소 거리와 같아야 한다 (req %d day %d)",
                    day.head().requestId(), day.head().day())
                .isCloseTo(shortestKm, within(EPSILON));

            PlaceRow head = day.head();
            samples.add(new DaySample(
                head.requestId(), head.location(), head.regionTier(), head.keywordSet(),
                head.day(), places.size(),
                (int) places.stream().filter(p -> p.slotType() == SlotType.MEAL).count(),
                RouteGeometry.totalDistanceKm(beforeOrder),
                shortestKm,
                RouteGeometry.selfIntersections(beforeOrder),
                RouteGeometry.backtrackVertices(beforeOrder),
                arms));
        }

        System.out.printf("  [제외] 하루 초과로 장소가 빠진 day %d개%n", dropped);
        System.out.printf("  → 공통 표본 day %d개 × λ %d개%n", samples.size(), LAMBDAS.length);
        return samples;
    }

    private static List<RoutePlace> orderOf(RoutedDay routed) {
        return routed.places().stream().map(RoutedPlace::place).toList();
    }

    // ── 식사 위반 지표 ────────────────────────────────────────────────────────

    /**
     * 결과 순서의 식사 시간창 위반 합(분) — <b>이 스윕의 y축이다.</b>
     *
     * <p><b>표시 시각을 그대로 쓰지 않는다.</b> {@link RoutedPlace#startTime()}은 5분 단위로
     * 올림된 표시용 값이라 식사마다 최대 4분씩 어긋나고, 그만큼 곡선이 흐려진다. 대신 시간
     * 모델을 되짚어 내부 분을 복원한다 — {@code t[i] = t[i-1] + 유효체류 + 이동}. 유효 체류는
     * 축소·탄력 반영 후 값으로 {@link RoutedPlace#stayMinutes()}가 실어 오고, 이동은 프로덕션
     * {@link RouteOptimizer#travelMinutes}를 그대로 부른다.
     *
     * <p><b>되짚기가 옳다는 것을 추정으로 두지 않는다.</b> 복원한 분을 표시 시각으로 되돌린 값이
     * 프로덕션 출력과 일치해야 한다고 단언한다 — 시간 모델이 나중에 갈라지면 곡선이 이상해지기
     * 전에 이 단언이 먼저 깨진다.
     *
     * <p>위반분 자체는 프로덕션 {@link RouteOptimizer#mealViolationMinutes}를 그대로 호출한다.
     * <b>재는 자와 최적화기가 같은 자를 쓴다</b> — 거리에 {@link GeoUtils#haversineKm}를 그대로
     * 쓰는 것과 같은 원칙이다.
     */
    private static int mealViolationMinutes(RoutedDay routed, RouteRequest request) {
        List<RoutedPlace> places = routed.places();
        int[] mealArrivals = new int[places.size()];
        int count = 0;
        int t = request.dayStartTime().getHour() * 60 + request.dayStartTime().getMinute();

        for (int i = 0; i < places.size(); i++) {
            assertThat(RouteOptimizer.toDisplayTime(t))
                .as("시간 모델 되짚기가 프로덕션 출력과 어긋난다 (day %d, %d번째)",
                    routed.day(), i)
                .isEqualTo(places.get(i).startTime());

            if (places.get(i).place().slotType() == SlotType.MEAL) {
                mealArrivals[count++] = t;
            }
            t += places.get(i).stayMinutes();
            if (i < places.size() - 1) {
                RoutePlace from = places.get(i).place();
                RoutePlace to = places.get(i + 1).place();
                t += RouteOptimizer.travelMinutes(
                    GeoUtils.haversineKm(
                        from.latitude(), from.longitude(), to.latitude(), to.longitude()),
                    request.travelMode());
            }
        }
        return RouteOptimizer.mealViolationMinutes(Arrays.copyOf(mealArrivals, count));
    }

    // ── 보고 ──────────────────────────────────────────────────────────────────

    private void reportSweep(String title, List<DaySample> samples, Predicate<DaySample> keep) {
        List<DaySample> subset = samples.stream().filter(keep).toList();
        System.out.printf("%n=== %s (day %d) ===%n", title, subset.size());
        if (subset.isEmpty()) {
            System.out.printf("  표본 없음%n");
            return;
        }

        double beforeSum = subset.stream().mapToDouble(DaySample::beforeKm).sum();
        double shortestSum = subset.stream().mapToDouble(DaySample::shortestKm).sum();
        System.out.printf(
            "  before %.2f km · 교차 %d · 역행 %d   (완전탐색 OFF)%n",
            beforeSum,
            subset.stream().mapToInt(DaySample::beforeCrossings).sum(),
            subset.stream().mapToInt(DaySample::beforeBacktracks).sum());
        System.out.printf("  shortest %.2f km   (거리만 최소화한 참조값)%n", shortestSum);
        System.out.printf("  %6s %10s %8s %12s %10s %8s %8s %7s%n",
            "λ", "after km", "감소율", "식사위반(분)", "양보 km", "교차", "역행", "악화");

        for (double lambda : LAMBDAS) {
            Aggregate agg = aggregate(subset, lambda);
            System.out.printf("  %6.2f %10.2f %7.1f%% %12d %10.2f %8d %8d %7d%s%n",
                lambda, agg.afterKm(), (beforeSum - agg.afterKm()) / beforeSum * 100,
                agg.violationMinutes(), agg.afterKm() - shortestSum,
                agg.crossings(), agg.backtracks(), agg.worsened(),
                lambda == PRODUCTION_LAMBDA ? "   <- 현행" : "");
        }
    }

    /**
     * 인접 λ 사이의 교환비 — <b>채택 여부와 무관한 이 스윕의 본 산출물이다.</b>
     *
     * <p>"식사 위반 1분을 줄이려고 몇 km 를 더 걷는가"를 그대로 읽는다. 계수를 정할 때 손으로
     * 어림잡은 환산(1km ↔ 2분, 즉 0.5 km/분)과 대조할 값이다.
     */
    private void reportExchangeRate(List<DaySample> samples) {
        System.out.printf("%n=== D. 인접 λ 교환비 — 위반 1분을 줄이는 데 드는 거리 ===%n");
        System.out.printf("  %14s %10s %12s %12s%n", "구간", "Δkm", "Δ위반(분)", "km/분");

        for (int i = 0; i + 1 < LAMBDAS.length; i++) {
            Aggregate low = aggregate(samples, LAMBDAS[i]);
            Aggregate high = aggregate(samples, LAMBDAS[i + 1]);
            double deltaKm = high.afterKm() - low.afterKm();
            int deltaViolation = low.violationMinutes() - high.violationMinutes();
            System.out.printf("  %5.2f -> %-5.2f %10.2f %12d %12s%n",
                LAMBDAS[i], LAMBDAS[i + 1], deltaKm, deltaViolation,
                deltaViolation == 0 ? "—" : "%.3f".formatted(deltaKm / deltaViolation));
        }
    }

    /**
     * 판정 8의 인과 검증 — <b>남은 교차의 원인이 정말 식사 시간창인가.</b>
     *
     * <p>3-7은 "남은 자기교차 14건 중 13건이 식사 2개짜리 day"라는 <b>상관</b>을 보였을 뿐이다.
     * 식사 벌점을 0으로 내렸을 때 그 교차가 사라지면 상관이 인과가 되고, 사라지지 않으면
     * 판정 8을 그만큼 약화해 다시 써야 한다.
     */
    private void reportMealBreakdown(List<DaySample> samples) {
        System.out.printf("%n=== E. 식사 슬롯 수별 — 판정 8의 인과 확인 ===%n");
        System.out.printf("  %8s %6s %12s %12s %12s %12s%n",
            "구분", "day", "교차 λ=0", "교차 λ=2", "위반 λ=0", "위반 λ=2");

        int[] mealCounts = samples.stream().mapToInt(DaySample::mealCount).distinct().sorted()
            .toArray();
        for (int mealCount : mealCounts) {
            List<DaySample> subset =
                samples.stream().filter(s -> s.mealCount() == mealCount).toList();
            Aggregate zero = aggregate(subset, 0.0);
            Aggregate prod = aggregate(subset, PRODUCTION_LAMBDA);
            System.out.printf("  meal=%-3d %6d %12d %12d %12d %12d%n",
                mealCount, subset.size(), zero.crossings(), prod.crossings(),
                zero.violationMinutes(), prod.violationMinutes());
        }
    }

    /**
     * 사전 등록한 채택 조건 (a)·(b)를 <b>하네스가 직접 판정한다.</b>
     *
     * <p>눈으로 표를 보고 고르면 결과를 본 뒤에 기준이 흔들린다. 지배와 포화는 정의가 명확하므로
     * 계산으로 못 박고, 그 판정이 FAMOUS·MINOR 양쪽에서 같은지도 함께 낸다(판정 기준 2).
     * 두 부분표본은 구간당 거리가 크게 다른 <b>서로 다른 상권 밀도 체제</b>라, 여기서 결론이
     * 일치하면 무작위 반분보다 강한 증거다.
     *
     * <p><b>이 메서드는 아무것도 단언하지 않는다.</b> 채택·기각은 사람이 문서에 적는 결정이고,
     * 여기서 실패로 만들면 측정이 아니라 기대값 확인이 된다.
     */
    private void reportVerdict(List<DaySample> samples) {
        System.out.printf("%n=== F. 사전 등록한 판정 기준 적용 ===%n");
        verdictFor("전량", samples, sample -> true);
        verdictFor("FAMOUS", samples, sample -> "FAMOUS".equals(sample.regionTier()));
        verdictFor("MINOR", samples, sample -> "MINOR".equals(sample.regionTier()));
    }

    private void verdictFor(String label, List<DaySample> samples, Predicate<DaySample> keep) {
        List<DaySample> subset = samples.stream().filter(keep).toList();
        Aggregate baseline = aggregate(subset, PRODUCTION_LAMBDA);

        // (a) 지배 — 현행보다 거리·위반이 둘 다 작은 λ. 어느 한쪽이라도 크면 맞바꿈이지 지배가
        // 아니다. 부동소수점 잡음이 지배를 만들어 내지 않도록 거리에는 허용 오차를 둔다.
        List<String> dominating = new ArrayList<>();
        for (double lambda : LAMBDAS) {
            if (lambda == PRODUCTION_LAMBDA) {
                continue;
            }
            Aggregate agg = aggregate(subset, lambda);
            boolean noWorse = agg.afterKm() <= baseline.afterKm() + EPSILON
                && agg.violationMinutes() <= baseline.violationMinutes();
            boolean better = agg.afterKm() < baseline.afterKm() - EPSILON
                || agg.violationMinutes() < baseline.violationMinutes();
            if (noWorse && better) {
                dominating.add("%.2f".formatted(lambda));
            }
        }

        // (b) 포화 — 여기서부터 위로는 산출이 <b>더 이상 변하지 않는</b> 가장 작은 λ.
        //
        // 거리와 위반을 둘 다 봐야 한다. 위반만 보면 "위반은 같은데 거리는 계속 오르는" 구간을
        // 포화로 오독하는데, 그건 포화가 아니라 낭비이고 (a) 지배로 잡혀야 할 것이다. 반대로
        // 둘 다 고정이면 그 구간의 λ 들은 <b>같은 순열을 낸다</b> — 현행이 그 위에 있어도
        // 바꿔서 얻을 것이 없다.
        Aggregate top = aggregate(subset, LAMBDAS[LAMBDAS.length - 1]);
        double plateauStart = Double.NaN;
        for (double lambda : LAMBDAS) {
            Aggregate agg = aggregate(subset, lambda);
            if (Math.abs(agg.afterKm() - top.afterKm()) <= EPSILON
                && agg.violationMinutes() == top.violationMinutes()) {
                plateauStart = lambda;
                break;
            }
        }

        System.out.printf("  [%s] (a) 현행 %.1f 을 지배하는 λ: %s%n", label, PRODUCTION_LAMBDA,
            dominating.isEmpty() ? "없음" : String.join(", ", dominating));
        System.out.printf("  [%s] (b) 산출이 고정되는 포화 시작 λ: %.2f  → 현행은 %s%n",
            label, plateauStart,
            plateauStart < PRODUCTION_LAMBDA - EPSILON
                ? "포화 안쪽 (여유 %.0f%% — 거리·위반이 모두 같아 낭비가 아니다)"
                    .formatted((PRODUCTION_LAMBDA / plateauStart - 1) * 100)
                : "포화가 시작되는 바로 그 지점이다");
    }

    // ── 집계 ──────────────────────────────────────────────────────────────────

    private static Aggregate aggregate(List<DaySample> samples, double lambda) {
        double afterKm = 0.0;
        int violation = 0;
        int crossings = 0;
        int backtracks = 0;
        int worsened = 0;

        for (DaySample sample : samples) {
            ArmResult arm = sample.arms().get(lambda);
            afterKm += arm.afterKm();
            violation += arm.violationMinutes();
            crossings += arm.crossings();
            backtracks += arm.backtracks();
            if (arm.afterKm() > sample.beforeKm() + EPSILON) {
                worsened++;
            }
        }
        return new Aggregate(afterKm, violation, crossings, backtracks, worsened);
    }

    // ── 산출물 ────────────────────────────────────────────────────────────────

    /**
     * day × λ 한 행씩 남긴다. 집계된 표만 남기면 나중에 다른 축으로 다시 묶을 수 없다 —
     * 재집계가 되려면 가장 잘게 쪼갠 형태여야 한다.
     */
    private void writeCsv(List<DaySample> samples) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("requestId,location,regionTier,keywordSet,day,placeCount,mealCount,")
            .append("lambda,beforeKm,afterKm,shortestKm,mealViolationMinutes,")
            .append("crossings,backtracks\n");

        for (DaySample sample : samples) {
            for (double lambda : LAMBDAS) {
                ArmResult arm = sample.arms().get(lambda);
                sb.append(sample.requestId()).append(',')
                    .append(sample.location()).append(',')
                    .append(sample.regionTier()).append(',')
                    .append(sample.keywordSet()).append(',')
                    .append(sample.day()).append(',')
                    .append(sample.placeCount()).append(',')
                    .append(sample.mealCount()).append(',')
                    .append("%.2f".formatted(lambda)).append(',')
                    .append("%.4f".formatted(sample.beforeKm())).append(',')
                    .append("%.4f".formatted(arm.afterKm())).append(',')
                    .append("%.4f".formatted(sample.shortestKm())).append(',')
                    .append(arm.violationMinutes()).append(',')
                    .append(arm.crossings()).append(',')
                    .append(arm.backtracks()).append('\n');
            }
        }

        Files.createDirectories(RESULTS_DIR);
        String runTag = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path out = RESULTS_DIR.resolve("route-meal-penalty-sweep-" + runTag + ".csv");
        // 엑셀이 UTF-8 을 자동 인식하지 못해 한글 지역명이 깨진다. 기존 하네스와 같은 처리다.
        Files.writeString(out, '﻿' + sb.toString(), StandardCharsets.UTF_8);
        System.out.printf("%n산출물: %s%n", out.toAbsolutePath());
    }

    // ── 결과 타입 ─────────────────────────────────────────────────────────────

    /** 하루치 입력 한 벌. {@code head}는 지역·키워드 같은 표시용 메타를 나른다. */
    private record DaySpec(PlaceRow head, RouteRequest request) {}

    /** 한 day 를 한 λ 로 돌린 결과. */
    private record ArmResult(
        double afterKm, int violationMinutes, int crossings, int backtracks
    ) {}

    /**
     * 한 day 의 전체 결과. {@code beforeKm}과 {@code shortestKm}이 λ 바깥에 있는 것은 <b>둘 다
     * λ 와 무관하기 때문</b>이다 — before 는 탐색을 끄므로 비용 함수를 부르지 않고, shortest 는
     * 애초에 거리만 본다.
     */
    private record DaySample(
        int requestId, String location, String regionTier, String keywordSet,
        int day, int placeCount, int mealCount,
        double beforeKm, double shortestKm,
        int beforeCrossings, int beforeBacktracks,
        Map<Double, ArmResult> arms
    ) {}

    private record Aggregate(
        double afterKm, int violationMinutes, int crossings, int backtracks, int worsened
    ) {}
}
