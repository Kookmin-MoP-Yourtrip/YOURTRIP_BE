package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.config.NaverConfig;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.config.TourApiConfig;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 후보 공급 실측 프로브 (ROADMAP 5-9). <b>외부 API를 실제로 호출한다.</b>
 *
 * <p>재는 것은 셋이다.
 * <ol>
 *   <li><b>슬롯당 확보 건수와 빈 결과 비율 — 지역 티어별로.</b> {@code empty}가 잦은 지역·슬롯이
 *       있으면 그때 "0건일 때만 카카오" 폴백을 붙인다(지금 미리 넣지 않는다)</li>
 *   <li><b>관광 슬롯의 시더↔TourAPI 겹침 — 거리 분포 × 이름 유사 여부.</b> 4-5의 300m 임계값을
 *       조정할 근거다. 4-7의 표본은 <b>"좌표만으로는 안 된다"를 보였을 뿐 "300m가 옳다"를 보인
 *       것이 아니다</b></li>
 *   <li>오매칭 표본 — 거리는 가까운데 다른 장소, 이름은 같은데 먼 곳</li>
 * </ol>
 *
 * <p><b>지역 세트는 하네스({@code AiHallucinationBaselineTest})의 것을 그대로 쓴다.</b> 3점 비교와
 * 지역 축이 어긋나면 "무인지 지역에서 시더가 후보를 주는가"라는 질문의 답을 환각률 쪽 결과와
 * 나란히 놓을 수 없다.
 *
 * <p><b>판정용이지 회귀 테스트가 아니다.</b> 외부 응답에 의존하므로 단언은 "이 설계가 성립하려면
 * 반드시 참이어야 하는 것"에만 걸고, 나머지는 CSV와 콘솔로 덤프해 사람이 읽는다
 * ({@code NaverLocalProbeTest}·{@code TourApiProbeTest}가 세운 형태).
 *
 * <p>Spring 컨텍스트를 쓰지 않으므로 spring-dotenv가 동작하지 않는다 — 실제 환경변수 → 레포 루트
 * {@code .env} 순으로 키를 찾고, 없으면 {@code assumeTrue}로 스킵한다.
 *
 * <pre>{@code
 * ./gradlew benchmarkTest --tests '*CandidateRetrievalProbeTest*' --rerun
 * }</pre>
 */
@Tag("benchmark")
@DisplayName("후보 공급 실측 (ROADMAP 5-9)")
class CandidateRetrievalProbeTest {

    private static final Path RESULTS_DIR = Path.of("results");

    /** 하네스와 같은 지역 세트. 유명 5 + 무인지 5. */
    private enum Tier {FAMOUS, MINOR}

    private record Region(String name, Tier tier) {}

    private static final List<Region> REGIONS = List.of(
        new Region("경주", Tier.FAMOUS),
        new Region("부산", Tier.FAMOUS),
        new Region("제주", Tier.FAMOUS),
        new Region("서울", Tier.FAMOUS),
        new Region("강릉", Tier.FAMOUS),
        new Region("순천", Tier.MINOR),
        new Region("영주", Tier.MINOR),
        new Region("공주", Tier.MINOR),
        new Region("통영", Tier.MINOR),
        new Region("삼척", Tier.MINOR));

    /**
     * 전 슬롯. <b>키워드는 한 조합으로 고정한다</b> — 하네스처럼 3조합을 돌리면 호출이 세 배가
     * 되는데, 이 측정의 질문("지역·슬롯별로 후보가 실제로 모이는가")은 키워드 축과 무관하다.
     */
    private static final List<KeywordType> KEYWORDS =
        List.of(KeywordType.COUPLE, KeywordType.HEALING);

    private CandidateRetrievalStage stage;

    @Test
    @DisplayName("지역 10곳 × 전 슬롯의 후보 확보 건수와 소스 간 겹침을 실측한다")
    void measure() throws IOException {
        String naverId = env("NAVER_CLIENT_ID");
        String naverSecret = env("NAVER_CLIENT_SECRET");
        String tourKey = env("TOUR_API_KEY");
        String kakaoKey = env("KAKAO_API_KEY");
        assumeTrue(naverId != null && naverSecret != null && tourKey != null && kakaoKey != null,
            "네이버·TourAPI·카카오 키가 모두 있어야 실측할 수 있다");

        KakaoLocalClient kakaoClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));
        NaverLocalClient naverClient = new NaverLocalClient(NaverConfig.buildNaverWebClient(
            "https://naverapihub.apigw.ntruss.com", naverId, naverSecret));
        TourApiClient tourClient = new TourApiClient(TourApiConfig.buildTourApiWebClient(
            "https://apis.data.go.kr/B551011/KorService2"), tourKey);

        stage = new CandidateRetrievalStage(new AreaGeocoder(kakaoClient),
            new NaverLocalSeedSource(naverClient), new TourApiSource(tourClient),
            new AiCourseMetrics(new SimpleMeterRegistry()), Runnable::run);

        ListAppender<ILoggingEvent> collapseLog = captureCollapseLog();

        List<SlotRow> slotRows = new ArrayList<>();
        List<PairRow> pairRows = new ArrayList<>();
        List<CollapsedRow> collapsedRows = new ArrayList<>();
        for (Region region : REGIONS) {
            int before = collapseLog.list.size();
            CandidatePool pool = retrieve(region);
            collapsedRows.addAll(collapsedSince(region, collapseLog, before));

            for (CandidateSlot slot : pool.slots()) {
                slotRows.add(SlotRow.of(region, slot));
                pairRows.addAll(pairsOf(region, slot));
            }
        }

        writeCsv("candidate-supply-slots", SlotRow.HEADER,
            slotRows.stream().map(SlotRow::toCsv).toList());
        writeCsv("candidate-supply-pairs", PairRow.HEADER,
            pairRows.stream().map(PairRow::toCsv).toList());
        writeCsv("subordinate-collapsed", CollapsedRow.HEADER,
            collapsedRows.stream().map(CollapsedRow::toCsv).toList());
        printSlotSummary(slotRows);
        printPairSummary(pairRows);
        printSubordinateSummary(collapsedRows, pairRows);

        // 이 설계가 성립하려면 반드시 참이어야 하는 것 — "시더가 무인지 지역에서도 후보를
        // 준다"가 깨지면 후보 공급 층 자체의 전제가 무너진다(4-2 판정 5의 재확인).
        assertThat(slotRows).isNotEmpty();
        assertThat(slotRows.stream().filter(row -> row.tier() == Tier.MINOR)
            .mapToInt(SlotRow::total).sum())
            .as("무인지 지역에서 후보가 하나도 모이지 않으면 이 층의 전제가 무너진다")
            .isPositive();

        // 이슈 #106 — 조립을 마친 목록에 부속 쌍이 남아 있으면 규칙이 새고 있다는 뜻이다.
        // 오합침 여부(합치면 안 될 것을 합쳤는가)는 자동으로 판정할 수 없어 CSV 를 사람이 읽지만,
        // "잡아야 할 것을 놓쳤는가"는 여기서 걸린다.
        assertThat(pairRows.stream().filter(PairRow::subordinatePair).toList())
            .as("300m 이내 진포함 쌍은 collapseSubordinates 가 이미 접었어야 한다")
            .isEmpty();
    }

    private CandidatePool retrieve(Region region) {
        // anchor 를 지역명 그대로 둔다 — Planner 가 없으므로 결정론적 기본 플랜과 같은 조건이고,
        // 그래야 지역 간 비교에서 anchor 품질이 변수로 끼어들지 않는다.
        PlannerPlan plan = new PlannerPlan(region.name(), "실측",
            List.of(PlannerDayPlan.of(1, region.name(), region.name(),
                List.of(SlotType.values()))));
        return stage.retrieve(region.name(), plan, KEYWORDS, CourseDeadline.unbounded());
    }

    /** 한 슬롯 안에서 시더 후보와 TourAPI 후보의 모든 쌍. 병합 전 원본이 아니라 병합 결과를 본다. */
    private static List<PairRow> pairsOf(Region region, CandidateSlot slot) {
        List<PairRow> rows = new ArrayList<>();
        List<PlaceCandidate> candidates = slot.candidates();
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                PlaceCandidate left = candidates.get(i);
                PlaceCandidate right = candidates.get(j);
                double distanceKm = CandidateMatcher.distanceKm(
                    left.latitude(), left.longitude(), right.latitude(), right.longitude());
                boolean similar = PlaceNameNormalizer.similar(left.name(), right.name());
                boolean proper = PlaceNameNormalizer.properlyContains(left.name(), right.name());
                // 임계값 조정에 쓸 구간만 남긴다 — 1km 밖 쌍은 어떤 임계값에서도 합쳐지지 않는다.
                if (distanceKm <= 1.0 || similar) {
                    rows.add(new PairRow(region, slot.slotType(), left, right, distanceKm, similar,
                        proper));
                }
            }
        }
        return rows;
    }

    // ── 부속 병합 검수 (이슈 #106) ────────────────────────────────────────────

    /**
     * 스테이지가 남기는 부속 병합 로그를 가로챈다.
     *
     * <p><b>메시지를 파싱하지 않는다.</b> 포맷 문자열은 {@code CandidateRetrievalStage}의 상수를
     * 그대로 대조하고, 값은 SLF4J가 보존하는 <b>인자 배열</b>에서 꺼낸다. 로그 문구가 바뀌어도
     * 상수를 함께 쓰므로 조용히 0건이 되지 않는다.
     */
    private static ListAppender<ILoggingEvent> captureCollapseLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(CandidateRetrievalStage.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        return appender;
    }

    private static List<CollapsedRow> collapsedSince(Region region,
        ListAppender<ILoggingEvent> appender, int from) {
        List<CollapsedRow> rows = new ArrayList<>();
        List<ILoggingEvent> events = List.copyOf(appender.list);
        for (int i = from; i < events.size(); i++) {
            ILoggingEvent event = events.get(i);
            if (!CandidateRetrievalStage.SUBORDINATE_COLLAPSE_LOG.equals(event.getMessage())) {
                continue;
            }
            Object[] args = event.getArgumentArray();
            rows.add(new CollapsedRow(region, String.valueOf(args[1]), String.valueOf(args[3]),
                String.valueOf(args[2]), ((Number) args[4]).longValue()));
        }
        return rows;
    }

    // ── 집계 출력 ─────────────────────────────────────────────────────────────

    private static void printSlotSummary(List<SlotRow> rows) {
        System.out.printf("%n=== 슬롯당 후보 확보 (지역 %d곳 × 슬롯 %d종) ===%n",
            REGIONS.size(), SlotType.values().length);
        for (Tier tier : Tier.values()) {
            List<SlotRow> tierRows = rows.stream().filter(row -> row.tier() == tier).toList();
            long empty = tierRows.stream().filter(row -> row.total() == 0).count();
            double average = tierRows.stream().mapToInt(SlotRow::total).average().orElse(0);
            System.out.printf("%-7s 슬롯 %3d개 · 평균 %5.1f건 · 빈 슬롯 %d개(%.1f%%)%n",
                tier, tierRows.size(), average, empty, 100.0 * empty / tierRows.size());
        }

        System.out.printf("%n--- 슬롯 타입별 평균 확보 건수 ---%n");
        Map<SlotType, List<SlotRow>> bySlot = new EnumMap<>(SlotType.class);
        rows.forEach(row -> bySlot.computeIfAbsent(row.slotType(), key -> new ArrayList<>())
            .add(row));
        bySlot.forEach((slotType, slotRows) -> System.out.printf(
            "%-11s 평균 %5.1f건 (시드 %4.1f · 관광공사 %4.1f) · 빈 슬롯 %d개%n",
            slotType,
            slotRows.stream().mapToInt(SlotRow::total).average().orElse(0),
            slotRows.stream().mapToInt(SlotRow::seeded).average().orElse(0),
            slotRows.stream().mapToInt(SlotRow::official).average().orElse(0),
            slotRows.stream().filter(row -> row.total() == 0).count()));
    }

    private static void printPairSummary(List<PairRow> rows) {
        System.out.printf("%n=== 근접 쌍 분포 — 4-5 임계값(현재 %.0fm)의 근거 ===%n",
            CandidateMatcher.PROXIMITY_THRESHOLD_KM * 1000);
        double[] bands = {0.05, 0.1, 0.2, 0.3, 0.5, 1.0};
        for (double band : bands) {
            long within = rows.stream().filter(row -> row.distanceKm() <= band).count();
            long similar = rows.stream()
                .filter(row -> row.distanceKm() <= band && row.nameSimilar()).count();
            System.out.printf("≤%4.0fm: 쌍 %4d개 · 이름까지 유사 %3d개(%5.1f%%)%n",
                band * 1000, within, similar, within == 0 ? 0.0 : 100.0 * similar / within);
        }

        System.out.printf("%n--- 오매칭 후보 표본 (가까운데 이름이 다른 쌍 상위 10) ---%n");
        rows.stream()
            .filter(row -> !row.nameSimilar())
            .sorted((a, b) -> Double.compare(a.distanceKm(), b.distanceKm()))
            .limit(10)
            .forEach(row -> System.out.printf("%6.0fm  %-20s ↔ %-20s (%s/%s)%n",
                row.distanceKm() * 1000, row.leftName(), row.rightName(),
                row.region(), row.slotType()));

        System.out.printf("%n--- 이름은 유사한데 먼 쌍 상위 10 (동명이소) ---%n");
        rows.stream()
            .filter(PairRow::nameSimilar)
            .sorted((a, b) -> Double.compare(b.distanceKm(), a.distanceKm()))
            .limit(10)
            .forEach(row -> System.out.printf("%6.0fm  %-20s ↔ %-20s (%s/%s)%n",
                row.distanceKm() * 1000, row.leftName(), row.rightName(),
                row.region(), row.slotType()));
    }

    /**
     * 부속 병합 검수 출력 (이슈 #106).
     *
     * <p><b>오합침 여부는 기계가 판정할 수 없다.</b> "갑사 철당간을 갑사에 합치는 것이 옳은가"는
     * 사람이 답해야 하므로, 접힌 쌍을 <b>전부</b> 찍고 {@code results/subordinate-collapsed.csv}에
     * 남긴다. 표본을 잘라 요약하면 남은 오합침이 그 뒤에 숨는다.
     */
    private static void printSubordinateSummary(List<CollapsedRow> collapsed,
        List<PairRow> pairs) {
        System.out.printf("%n=== 부속 병합 — 접힌 쌍 전부 (%d건) ===%n", collapsed.size());
        System.out.printf("%n오합침 검수: 아래 각 행이 '합쳐야 맞는 쌍'인지 직접 판정한다.%n");
        collapsed.forEach(row -> System.out.printf("%6dm  %-24s ← %-24s (%s/%s)%n",
            row.distanceM(), row.primary(), row.subordinate(), row.region().name(), row.slotType()));

        // 규칙을 넓히면 무엇이 더 합쳐지는지. 남는 이유가 둘이라 함께 찍는다 — 거리가 임계값
        // 밖이거나(판정 10 이 (196m, 440m) 에서 고른 300m), TourAPI 를 쓰지 않는 슬롯이거나.
        // 어느 쪽이든 "합치면 안 되는 것"으로 남아 있어야 정상이다.
        List<PairRow> nearMiss = pairs.stream()
            .filter(PairRow::properlyContains)
            .sorted((a, b) -> Double.compare(a.distanceKm(), b.distanceKm()))
            .toList();
        System.out.printf("%n--- 진포함이지만 접지 않고 남은 쌍 (%d건) ---%n", nearMiss.size());
        nearMiss.forEach(row -> System.out.printf("%6.0fm  %-24s ↔ %-24s (%s/%s, %s)%n",
            row.distanceKm() * 1000, row.leftName(), row.rightName(),
            row.region().name(), row.slotType(),
            TourApiSource.contentTypeIdsFor(row.slotType()).isEmpty()
                ? "규칙 미적용 슬롯" : "임계값 밖"));
    }

    // ── CSV ───────────────────────────────────────────────────────────────────

    private static void writeCsv(String prefix, String header, List<String> lines)
        throws IOException {
        Files.createDirectories(RESULTS_DIR);
        Path path = RESULTS_DIR.resolve(prefix + ".csv");
        List<String> all = new ArrayList<>();
        all.add(header);
        all.addAll(lines);
        Files.write(path, String.join("\n", all).getBytes(StandardCharsets.UTF_8));
        System.out.printf("산출물: %s (%d행)%n", path.toAbsolutePath(), lines.size());
    }

    private record SlotRow(Region region, SlotType slotType, int total, int seeded, int official,
                           int merged) {

        static final String HEADER = "region,tier,slot,total,seeded,official,merged";

        static SlotRow of(Region region, CandidateSlot slot) {
            List<PlaceCandidate> candidates = slot.candidates();
            return new SlotRow(region, slot.slotType(), candidates.size(),
                (int) candidates.stream().filter(PlaceCandidate::seeded).count(),
                (int) candidates.stream().filter(PlaceCandidate::official).count(),
                (int) candidates.stream()
                    .filter(candidate -> candidate.seeded() && candidate.official()).count());
        }

        Tier tier() {
            return region.tier();
        }

        String toCsv() {
            return "%s,%s,%s,%d,%d,%d,%d".formatted(
                region.name(), region.tier(), slotType, total, seeded, official, merged);
        }
    }

    private record PairRow(Region region, SlotType slotType, PlaceCandidate left,
                           PlaceCandidate right, double distanceKm, boolean nameSimilar,
                           boolean properlyContains) {

        static final String HEADER =
            "region,tier,slot,left,right,distanceM,nameSimilar,properlyContains";

        String leftName() {
            return left.name();
        }

        String rightName() {
            return right.name();
        }

        /**
         * 접혔어야 하는데 목록에 남아 있는 쌍 — 이슈 #106 규칙이 새는지 보는 지표다.
         *
         * <p><b>TourAPI 를 쓰지 않는 슬롯은 세지 않는다.</b> 거기는 규칙을 걸지 않기로 했으므로
         * (상호명은 같은 지명을 여러 가게가 나눠 쓴다) 진포함 쌍이 남는 것이 정상이다.
         */
        boolean subordinatePair() {
            return properlyContains
                && distanceKm <= CandidateMatcher.PROXIMITY_THRESHOLD_KM
                && !TourApiSource.contentTypeIdsFor(slotType).isEmpty();
        }

        String toCsv() {
            return "%s,%s,%s,\"%s\",\"%s\",%.0f,%s,%s".formatted(
                region.name(), region.tier(), slotType,
                left.name(), right.name(), distanceKm * 1000, nameSimilar, properlyContains);
        }
    }

    /** 접힌 쌍 하나. 스테이지 로그의 인자 배열에서 만든다. */
    private record CollapsedRow(Region region, String slotType, String primary,
                                String subordinate, long distanceM) {

        static final String HEADER = "region,tier,slot,primary,subordinate,distanceM";

        String toCsv() {
            return "%s,%s,%s,\"%s\",\"%s\",%d".formatted(
                region.name(), region.tier(), slotType, primary, subordinate, distanceM);
        }
    }

    // ── 키 조회 ───────────────────────────────────────────────────────────────

    private static String env(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return fromDotEnv(key);
    }

    private static String fromDotEnv(String key) {
        Path dotEnv = Path.of(".env");
        if (!Files.exists(dotEnv)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(key + "=")) {
                    String value = trimmed.substring(key.length() + 1).trim();
                    return value.isEmpty() ? null : value;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}
