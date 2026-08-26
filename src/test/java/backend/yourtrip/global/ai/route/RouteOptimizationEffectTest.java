package backend.yourtrip.global.ai.route;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 동선 최적화 효과 실측 (ROADMAP 3-7).
 *
 * <p>3-6이 잰 것은 <b>완전탐색이 얼마나 빠른가</b>였다. 이 하네스가 재는 것은 <b>그래서 동선이
 * 얼마나 좋아졌는가</b>다 — ROADMAP 목표 3("동선·시간 배치를 LLM 추측에서 실좌표 계산으로
 * 옮긴다")이 실제로 달성됐는지에 숫자를 붙인다.
 *
 * <h2>두 팔은 같은 장소 집합을 쓰고 순서만 다르다</h2>
 *
 * <pre>
 * before = optimize(request, 1)   완전탐색을 끈다 → 입력 순서 그대로
 * after  = optimize(request)      완전탐색이 고른 최적 순열
 * </pre>
 *
 * <p>{@code optimize(request, 1)}은 {@code n >= 8}에서 탐색을 건너뛰는 <b>가드 경로를 강제로
 * 태우는 것</b>이고, 그 경로는 순서를 유지한 채 시각만 계산한다. 그래서 두 팔은 같은 클래스·같은
 * 시간 모델·같은 탄력 체류를 지나며 <b>달라지는 변수가 "탐색을 했는가" 하나뿐</b>이다.
 * 프로덕션 코드는 한 줄도 바꾸지 않는다.
 *
 * <p>이 클래스가 {@code global/benchmark}가 아니라 여기 있는 이유는
 * {@code RouteOptimizerBenchmarkTest}와 같다 — 위 이음매가 package-private 이다.
 *
 * <h2>슬롯 종류가 권위 있는 값이라는 것이 이 입력의 조건이다</h2>
 *
 * <p>입력은 {@code AiCourseRouteInputProbeTest}가 파이프라인 30요청을 실제로 태워 남긴
 * 산출물이다. 거기서는 슬롯 종류를 Planner 가 정하고 후보 공급이 그 타입으로만 검색하며
 * 5-3 카테고리 하드 제약이 강제하므로, <b>슬롯을 추론할 필요가 없다.</b>
 *
 * <p>이 조건이 중요한 이유는 슬롯이 <b>식사 시간창 벌점을 통해 최적 순열 선택에 개입</b>하기
 * 때문이다. MEAL 이 하나 잘못 붙으면 있어서는 안 될 벌점이 생기고, 최적화기는 그 가짜 식사를
 * 점심창에 맞추려 순서를 크게 흔든다. 그래서 슬롯을 사후에 추론해야 하는 산출물은 이 측정의
 * 입력이 될 수 없다.
 *
 * <p><b>좌표는 반드시 있다.</b> {@link RoutePlace}가 좌표를 primitive {@code double}로 못 박아
 * 그라운딩을 통과하지 못한 장소는 최적화기에 들어가는 것 자체가 불가능하다(ROADMAP 5-2).
 * 즉 캡처된 입력에 실린 장소는 모두 좌표를 가진다.
 *
 * <h2>외부 호출이 없다</h2>
 *
 * <p>좌표·슬롯·시작 시각·이동수단이 모두 CSV 에 실려 오므로 이 하네스는 네트워크를 쓰지
 * 않는다. <b>같은 CSV 로 몇 번을 돌려도 산출물이 바이트 단위로 같다.</b> 그럼에도
 * {@code @Tag("benchmark")}인 것은 입력이 커밋된 산출물에 의존해 일반 빌드의 회귀 테스트로
 * 삼기에 적절하지 않기 때문이다. 덕분에 <b>계수를 바꿔 다시 재는 데 LLM 비용이 들지 않는다.</b>
 *
 * <pre>
 * ./gradlew benchmarkTest --tests '*RouteOptimizationEffectTest*' --rerun
 * </pre>
 */
@Tag("benchmark")
@DisplayName("RouteOptimizer 동선 개선 효과 실측 (ROADMAP 3-7)")
class RouteOptimizationEffectTest {

    /** {@code AiCourseRouteInputProbeTest}가 남긴 산출물. {@code ROUTE_EFFECT_FROM}으로 덮어쓴다. */
    private static final String DEFAULT_SOURCE =
        "docs/tasks/ai-course-create/route/artifacts/route-pipeline-places-20260826.csv";

    private static final Path RESULTS_DIR = Path.of("results");

    /**
     * day 하나가 표본이 되기 위한 최소 장소 수.
     *
     * <p>2개짜리 day 를 빼는 이유는 표본을 고르는 취향이 아니라 <b>측정 대상이 없기 때문</b>이다 —
     * 점이 둘이면 순서를 뒤집어도 거리가 같다. 남겨 두면 "개선 0%"인 행이 분모만 늘려 전체
     * 감소율을 희석한다.
     */
    private static final int MIN_PLACES = 3;

    /**
     * 민감도 축에서 제외할 출처.
     *
     * <p>{@code SEEDED}(네이버)와 {@code LISTED}(TourAPI)는 소스가 좌표를 직접 주지만
     * {@code SUGGESTED}는 카카오 검색으로 실존을 확인하며 좌표를 얻는다 — <b>좌표가 원안 장소가
     * 아닌 인근 업소의 것일 수 있는 유일한 자리</b>다. 5-3 카테고리 하드 제약이 슬롯 타입을
     * 강제하므로 관광 슬롯에 식당이 앉지는 못하고, 남는 위험은 좌표 정밀도뿐이다.
     */
    private static final String KAKAO_MATCHED_SOURCE = "SUGGESTED";

    private final RouteOptimizer optimizer = new RouteOptimizer();

    @Test
    @DisplayName("완전탐색을 끈 대조군 대비 이동거리·역주행 개선을 잰다")
    void measureRouteOptimizationEffect() throws IOException {
        Path source = Path.of(System.getenv().getOrDefault("ROUTE_EFFECT_FROM", DEFAULT_SOURCE));
        assumeTrue(Files.exists(source),
            "캡처된 최적화 직전 입력이 필요하다: " + source
                + " (AiCourseRouteInputProbeTest 를 먼저 돌려 채집한다)");

        List<PlaceRow> rows = RouteInputCsv.readPlaces(source);
        System.out.printf("%n=== 입력: %s (%d행) ===%n", source, rows.size());

        // 전량 팔에는 필터가 없다 — 산출물이 최적화기에 실제로 들어간 장소만 담고 있어
        // 거를 것이 없다. 민감도 팔만 카카오 매칭이 개입한 장소를 뺀다.
        List<DayResult> full = measure(rows, row -> true);
        List<DayResult> sensitivity =
            measure(rows, row -> !KAKAO_MATCHED_SOURCE.equals(row.source()));

        writeCsv(full);
        reportInput(rows);
        reportSummary("B. 요약 — 전량", full);
        reportBreakdown("C. 지역 티어별", full, DayResult::regionTier);
        reportBreakdown("D. day 장소 수별", full, result -> "n=" + result.placeCount());
        // 표 F 가 이 측정의 진단이다 — 악화한 day 와 교차가 남은 day 를 하나씩 열어 보면 전부
        // 식사 슬롯이 얽혀 있는데, 사례 몇 건으로는 "그렇게 보인다"까지밖에 못 간다. 표본
        // 전체를 식사 개수로 갈라 두면 시간창 항이 거리를 얼마나 밀어내는지가 드러난다.
        reportBreakdown("F. day 내 식사 슬롯 수별", full, result -> "meal=" + result.mealCount());
        reportSummary("E. 민감도 — 카카오 매칭이 개입하지 않은 장소만(SEEDED·LISTED)",
            sensitivity);

        assertThat(full).as("유효 표본이 하나도 없다 — 입력 CSV의 좌표 열을 확인하라").isNotEmpty();
    }

    // ── 측정 ──────────────────────────────────────────────────────────────────

    /**
     * {@code (requestId, day)}마다 두 팔을 돌린다.
     *
     * @param keep 표본에 넣을 장소. 좁히면 민감도 분석이 된다 — 방언마다 축이 다르다
     */
    private List<DayResult> measure(List<PlaceRow> rows, Predicate<PlaceRow> keep) {
        Map<String, List<PlaceRow>> byDay = new LinkedHashMap<>();
        for (PlaceRow row : rows) {
            if (!keep.test(row)) {
                continue;
            }
            byDay.computeIfAbsent(row.requestId() + "/" + row.day(), key -> new ArrayList<>())
                .add(row);
        }

        List<DayResult> results = new ArrayList<>();
        for (List<PlaceRow> dayRows : byDay.values()) {
            // placeIndex 오름차순이 곧 최적화 전 순서다 — Planner 가 낸 슬롯 순서이고,
            // RouteOptimizer 가 없었다면 그대로 저장됐을 순서다.
            dayRows.sort(Comparator.comparingInt(PlaceRow::placeIndex));
            if (dayRows.size() < MIN_PLACES) {
                continue;
            }

            List<RoutePlace> places = new ArrayList<>(dayRows.size());
            for (PlaceRow row : dayRows) {
                places.add(new RoutePlace(
                    row.requestId() + "-" + row.day() + "-" + row.placeIndex(),
                    row.placeName(), slotTypeOf(row), row.latitude(), row.longitude()));
            }

            // 시작 시각과 이동수단은 CSV 가 실어 온 것을 그대로 쓴다 — 둘 다 최적화 입력이라
            // (시작이 밀리면 식사 벌점이 달라진다) 기본값으로 덮으면 운영에서 나온 순서를
            // 재현하지 못한다.
            PlaceRow first = dayRows.get(0);
            RouteRequest request = new RouteRequest(
                first.day(), places, first.dayStartTime(), null, first.travelMode());

            RoutedDay before = optimizer.optimize(request, 1);
            RoutedDay after = optimizer.optimize(request);

            // 두 팔의 장소 집합이 같다는 것이 이 측정의 전제다. 하루 초과로 장소가 빠지면 그
            // 전제가 깨지므로 세어서 제외한다(기본 dayEndTime 23:59 라 0건이 정상이다).
            if (!before.droppedPlaces().isEmpty() || !after.droppedPlaces().isEmpty()) {
                System.out.printf(
                    "  [제외] req %d %s day %d — 하루 초과로 장소가 빠졌다 (before %d, after %d)%n",
                    dayRows.get(0).requestId(), dayRows.get(0).location(), dayRows.get(0).day(),
                    before.droppedPlaces().size(), after.droppedPlaces().size());
                continue;
            }

            List<RoutePlace> beforeOrder = orderOf(before);
            List<RoutePlace> afterOrder = orderOf(after);

            // 이음매가 실제로 탐색을 껐는지 — 값이 아니라 전제를 단언한다.
            assertThat(beforeOrder)
                .as("before 팔은 입력 순서를 그대로 유지해야 한다")
                .containsExactlyElementsOf(places);
            assertThat(afterOrder)
                .as("after 팔은 같은 장소 집합을 재배열한 것이어야 한다")
                .containsExactlyInAnyOrderElementsOf(places);

            PlaceRow head = dayRows.get(0);
            results.add(new DayResult(
                head.requestId(), head.location(), head.regionTier(), head.keywordSet(),
                head.day(), places.size(),
                (int) places.stream().filter(p -> p.slotType() == SlotType.MEAL).count(),
                RouteGeometry.totalDistanceKm(beforeOrder),
                RouteGeometry.totalDistanceKm(afterOrder),
                RouteGeometry.shortestPathKm(places),
                RouteGeometry.selfIntersections(beforeOrder),
                RouteGeometry.selfIntersections(afterOrder),
                RouteGeometry.backtrackVertices(beforeOrder),
                RouteGeometry.backtrackVertices(afterOrder),
                before.endTime(), after.endTime()));
        }
        return results;
    }

    private static List<RoutePlace> orderOf(RoutedDay routed) {
        return routed.places().stream().map(RoutedPlace::place).toList();
    }

    /** Planner 가 정한 값을 그대로 쓴다 — 추론하지 않는다는 것이 이 입력의 조건이다. */
    private static SlotType slotTypeOf(PlaceRow row) {
        return row.slotType();
    }

    // ── 보고 ──────────────────────────────────────────────────────────────────

    private void reportInput(List<PlaceRow> rows) {
        System.out.printf("%n=== A. 입력 분포 ===%n");

        Map<String, Integer> bySlot = new TreeMap<>();
        Map<String, Integer> bySource = new TreeMap<>();
        for (PlaceRow row : rows) {
            bySlot.merge(row.slotType().name(), 1, Integer::sum);
            bySource.merge(row.source().isBlank() ? "(드롭)" : row.source(), 1, Integer::sum);
        }
        System.out.printf("  [슬롯 종류 — Planner 가 정한 값]%n");
        bySlot.forEach((slot, n) -> System.out.printf("    %-14s %4d%n", slot, n));
        System.out.printf("  [출처 — 민감도 축의 재료]%n");
        bySource.forEach((src, n) -> System.out.printf("    %-14s %4d%n", src, n));
    }

    private void reportSummary(String title, List<DayResult> results) {
        System.out.printf("%n=== %s ===%n", title);
        if (results.isEmpty()) {
            System.out.printf("  표본 없음%n");
            return;
        }

        double beforeSum = results.stream().mapToDouble(DayResult::beforeKm).sum();
        double afterSum = results.stream().mapToDouble(DayResult::afterKm).sum();
        double[] perDay = results.stream().mapToDouble(DayResult::reductionPct).sorted().toArray();

        long improved = results.stream().filter(r -> r.afterKm() < r.beforeKm() - 1e-9).count();
        long worsened = results.stream().filter(r -> r.afterKm() > r.beforeKm() + 1e-9).count();

        System.out.printf("  표본 day                  %d%n", results.size());
        double shortestSum = results.stream().mapToDouble(DayResult::shortestKm).sum();

        System.out.printf("  표본 요청                 %d%n",
            results.stream().map(DayResult::requestId).distinct().count());
        System.out.printf("  총 이동거리 before        %.2f km   (완전탐색 OFF — Planner 슬롯 순서)%n",
            beforeSum);
        System.out.printf("  총 이동거리 after         %.2f km   (프로덕션 RouteOptimizer)%n",
            afterSum);
        System.out.printf("  총 이동거리 shortest      %.2f km   (거리만 최소화한 참조값)%n",
            shortestSum);
        System.out.printf("  전체 합 기준 감소율       %.1f%%   <- 대표값%n",
            (beforeSum - afterSum) / beforeSum * 100);
        System.out.printf("  거리만 최소화했다면       %.1f%%   (달성 가능한 상한)%n",
            (beforeSum - shortestSum) / beforeSum * 100);
        System.out.printf("  시간창에 지불한 거리      %.2f km (after 의 %.1f%%)%n",
            afterSum - shortestSum, (afterSum - shortestSum) / afterSum * 100);
        int legs = results.stream().mapToInt(DayResult::legs).sum();
        System.out.printf("  구간당 평균 before/after   %.2f / %.2f km  (구간 %d개)%n",
            beforeSum / legs, afterSum / legs, legs);
        System.out.printf("  day 감소율 중앙값         %.1f%%%n", median(perDay));
        System.out.printf("  day 감소율 평균           %.1f%%%n",
            results.stream().mapToDouble(DayResult::reductionPct).average().orElse(0));
        System.out.printf("  개선 / 동일 / 악화        %d / %d / %d   (부호검정 p %s)%n",
            improved, results.size() - improved - worsened, worsened,
            formatP(signTestP(improved, worsened)));
        System.out.printf("  자기교차 before -> after   %d -> %d%n",
            results.stream().mapToInt(DayResult::beforeCrossings).sum(),
            results.stream().mapToInt(DayResult::afterCrossings).sum());
        System.out.printf("  역행꼭짓점 before -> after %d -> %d%n",
            results.stream().mapToInt(DayResult::beforeBacktracks).sum(),
            results.stream().mapToInt(DayResult::afterBacktracks).sum());

        // 판정 기준 2 — 악화된 day 는 반드시 사례로 드러낸다. 완전탐색이 최소화하는 것은 거리가
        // 아니라 (거리 + 식사 시간창 위반 + 하루 초과) 비용이므로 나올 수 있는 결과이고,
        // 나왔다면 그건 결함이 아니라 설계대로 동작한다는 증거다.
        if (worsened > 0) {
            System.out.printf("  [악화 사례 — 거리를 양보하고 식사 시간창을 맞춘 경우]%n");
            results.stream()
                .filter(r -> r.afterKm() > r.beforeKm() + 1e-9)
                .sorted(Comparator.comparingDouble(DayResult::reductionPct))
                .forEach(r -> System.out.printf(
                    "    req %d %s day %d (n=%d, meal=%d) : %.2f -> %.2f km (%+.1f%%), "
                        + "거리최소 %.2f km, 종료 %s -> %s%n",
                    r.requestId(), r.location(), r.day(), r.placeCount(), r.mealCount(),
                    r.beforeKm(), r.afterKm(), -r.reductionPct(), r.shortestKm(),
                    r.beforeEnd(), r.afterEnd()));
        }

        // 판정 기준 3 — after 자기교차가 0이 아니면 그대로 드러낸다.
        List<DayResult> stillCrossing = results.stream()
            .filter(r -> r.afterCrossings() > 0)
            .sorted(Comparator.comparingInt(DayResult::afterCrossings).reversed())
            .toList();
        if (!stillCrossing.isEmpty()) {
            System.out.printf("  [최적화 후에도 교차가 남은 day]%n");
            stillCrossing.forEach(r -> System.out.printf(
                "    req %d %s day %d (n=%d, meal=%d) : 교차 %d -> %d, %.2f -> %.2f km%n",
                r.requestId(), r.location(), r.day(), r.placeCount(), r.mealCount(),
                r.beforeCrossings(), r.afterCrossings(), r.beforeKm(), r.afterKm()));
        }
    }

    private void reportBreakdown(String title, List<DayResult> results,
        Function<DayResult, String> key) {

        System.out.printf("%n=== %s ===%n", title);
        System.out.printf("  %-10s %5s %10s %10s %10s %8s %8s %14s %12s %12s%n",
            "구분", "day", "before km", "after km", "최소 km", "감소율", "양보율",
            "구간당 b->a", "교차 b->a", "역행 b->a");

        Map<String, List<DayResult>> grouped = new TreeMap<>();
        for (DayResult result : results) {
            grouped.computeIfAbsent(key.apply(result), k -> new ArrayList<>()).add(result);
        }
        grouped.forEach((group, list) -> {
            double before = list.stream().mapToDouble(DayResult::beforeKm).sum();
            double after = list.stream().mapToDouble(DayResult::afterKm).sum();
            double shortest = list.stream().mapToDouble(DayResult::shortestKm).sum();
            int legs = list.stream().mapToInt(DayResult::legs).sum();
            System.out.printf(
                "  %-10s %5d %10.2f %10.2f %10.2f %7.1f%% %7.1f%% %6.2f->%-6.2f %5d -> %-4d %5d -> %-4d%n",
                group, list.size(), before, after, shortest, (before - after) / before * 100,
                (after - shortest) / after * 100, before / legs, after / legs,
                list.stream().mapToInt(DayResult::beforeCrossings).sum(),
                list.stream().mapToInt(DayResult::afterCrossings).sum(),
                list.stream().mapToInt(DayResult::beforeBacktracks).sum(),
                list.stream().mapToInt(DayResult::afterBacktracks).sum());
        });
    }

    /**
     * 부호검정(양측)의 p 값. 감소율이 정확히 0인 day 는 제외하고 개선·악화 두 방향만 센다.
     *
     * <p>t 검정이 아니라 부호검정을 쓰는 이유는 <b>day 별 감소율의 분포가 정규가 아니기</b>
     * 때문이다 — 개선폭은 위로 100%에 막혀 있는데 악화폭은 막혀 있지 않아 오른쪽으로 길게
     * 꼬리를 끈다. 여기서 묻는 것은 "평균이 얼마나 다른가"가 아니라 "개선 쪽이 더 자주
     * 나오는가"이므로, 크기를 버리고 부호만 세는 편이 질문에 맞다.
     */
    private static double signTestP(long improved, long worsened) {
        long n = improved + worsened;
        if (n == 0) {
            return 1.0;
        }
        long extreme = Math.min(improved, worsened);
        double tail = 0.0;
        for (long k = 0; k <= extreme; k++) {
            tail += binomial(n, k) * Math.pow(0.5, n);
        }
        return Math.min(1.0, 2 * tail);
    }

    private static double binomial(long n, long k) {
        double result = 1.0;
        for (long i = 0; i < k; i++) {
            result = result * (n - i) / (i + 1);
        }
        return result;
    }

    private static String formatP(double p) {
        return p < 1e-6 ? "< 1e-6" : "%.2e".formatted(p);
    }

    private static double median(double[] sorted) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    private void writeCsv(List<DayResult> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("requestId,location,regionTier,keywordSet,day,placeCount,mealCount,")
            .append("beforeKm,afterKm,shortestKm,reductionPct,concessionKm,")
            .append("beforeKmPerLeg,afterKmPerLeg,")
            .append("beforeCrossings,afterCrossings,beforeBacktracks,afterBacktracks,")
            .append("beforeEndTime,afterEndTime\n");

        for (DayResult r : results) {
            sb.append(r.requestId()).append(',')
                .append(r.location()).append(',')
                .append(r.regionTier()).append(',')
                .append(r.keywordSet()).append(',')
                .append(r.day()).append(',')
                .append(r.placeCount()).append(',')
                .append(r.mealCount()).append(',')
                .append("%.4f".formatted(r.beforeKm())).append(',')
                .append("%.4f".formatted(r.afterKm())).append(',')
                .append("%.4f".formatted(r.shortestKm())).append(',')
                .append("%.2f".formatted(r.reductionPct())).append(',')
                .append("%.4f".formatted(r.concessionKm())).append(',')
                .append("%.4f".formatted(r.beforeKm() / r.legs())).append(',')
                .append("%.4f".formatted(r.afterKm() / r.legs())).append(',')
                .append(r.beforeCrossings()).append(',')
                .append(r.afterCrossings()).append(',')
                .append(r.beforeBacktracks()).append(',')
                .append(r.afterBacktracks()).append(',')
                .append(r.beforeEnd()).append(',')
                .append(r.afterEnd()).append('\n');
        }

        Files.createDirectories(RESULTS_DIR);
        String runTag = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path out = RESULTS_DIR.resolve("route-optimization-effect-" + runTag + ".csv");
        // 엑셀이 UTF-8 을 자동 인식하지 못해 한글 지역명이 깨진다. 기존 하네스와 같은 처리다.
        Files.writeString(out, '﻿' + sb.toString(), StandardCharsets.UTF_8);
        System.out.printf("%n산출물: %s%n", out.toAbsolutePath());
    }

    // ── 결과 타입 ──────────────────────────────────────────────────────────────

    private record DayResult(
        int requestId, String location, String regionTier, String keywordSet,
        int day, int placeCount, int mealCount,
        double beforeKm, double afterKm, double shortestKm,
        int beforeCrossings, int afterCrossings,
        int beforeBacktracks, int afterBacktracks,
        LocalTime beforeEnd, LocalTime afterEnd
    ) {
        double reductionPct() {
            return beforeKm == 0.0 ? 0.0 : (beforeKm - afterKm) / beforeKm * 100;
        }

        /** 프로덕션이 식사 시간창·하루 초과를 맞추느라 <b>거리에서 양보한 양</b>(km). */
        double concessionKm() {
            return afterKm - shortestKm;
        }

        /**
         * 이동 구간 수. 열린 경로라 장소 {@code n}개면 구간은 {@code n-1}개다.
         *
         * <p>총합만 보면 <b>장소가 많은 day 가 결과를 지배한다</b> — 파이프라인은 day 당 슬롯이
         * 5~7로 흔들리므로 그 편차가 작지 않다. 구간 수로 나눈 값을 함께 봐야 "한 번 움직일 때
         * 얼마나 걷는가"라는, 사용자가 실제로 체감하는 축이 드러난다.
         */
        int legs() {
            return placeCount - 1;
        }
    }
}
