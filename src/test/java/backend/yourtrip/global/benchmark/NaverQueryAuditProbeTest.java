package backend.yourtrip.global.benchmark;

import static backend.yourtrip.global.benchmark.BenchmarkEnv.loadDotEnv;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.resolve;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.setting;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.agent.PlannerAgent;
import backend.yourtrip.global.ai.candidate.AreaGeocoder;
import backend.yourtrip.global.ai.candidate.CandidateBatch;
import backend.yourtrip.global.ai.candidate.CandidateOutcome;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateRetrievalStage;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.NaverLocalSeedSource;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.candidate.StyleModifierDictionary;
import backend.yourtrip.global.ai.candidate.StyleTag;
import backend.yourtrip.global.ai.candidate.TourApiSource;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.benchmark.BaselineInputSet.RequestSpec;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.NaverLocalResult;
import backend.yourtrip.global.naver.config.NaverConfig;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.config.TourApiConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 네이버 지역검색 <b>질의 구성 감사</b> (이슈 #179). <b>OpenAI(Planner)·네이버·카카오·TourAPI를 실제로
 * 호출한다.</b>
 *
 * <h2>왜 재는가</h2>
 * 요청 1건이 네이버를 약 45번 부르고, 그 호출이 1초 안에 몰려 키당 50 RPS 한도에 걸린다. 429 를
 * 재시도로 덮기 전에 <b>그 45번이 다 필요한가</b>를 먼저 묻는다. 동시 2명 측정의 지표를 다시 집계해
 * 보니 시더 호출의 50%가 쓸 후보 0건({@code empty})으로 끝났고, 받은 후보 중 297건이 업종 불일치로
 * 버려졌다. 수식어가 슬롯 종류와 무관하게 전부 곱해져 {@code "경주 루프탑 산책로"} 같은 질의가
 * 나가는 것이 원인이라는 가설을 세웠다 — 지표에 질의 종류 축이 없어 그 가설을 여기서 확인한다.
 *
 * <h2>어떻게 재는가 — 운영 코드 경로 그대로</h2>
 * 질의를 프로브가 재구성하지 않는다. Planner 계획을 받아 <b>운영 {@link CandidateRetrievalStage}를
 * 그대로</b> 돌리고, 네이버 클라이언트와 시더를 상속해 지나가는 호출만 기록한다. 재구성하면 운영
 * 코드가 바뀔 때 감사가 다른 질의를 재게 된다.
 *
 * <p><b>실행기는 동기({@code Runnable::run})다.</b> 운영과 다른 지점이지만 여기서 재는 것은 지연이
 * 아니라 질의별 결과라 영향이 없고, 오히려 두 가지를 위해 필요하다 — ① 순차라서 429 가 섞이지 않고
 * ② 시더 호출마다 탈락 지표 증분을 그 호출에 귀속할 수 있다.
 *
 * <h2>판정 기준 — 결과를 보기 전에 못 박는다</h2>
 * <ol>
 *   <li>(수식어 × 슬롯) 조합마다 <b>남은 후보 0건 비율</b>과 <b>순기여</b>(같은 day·슬롯의 기본 질의에
 *       없던 새 후보 수)를 낸다</li>
 *   <li>0건 비율이 기본 질의보다 뚜렷이 높고 순기여 평균이 1건 미만인 조합을 <b>제거 후보</b>로 둔다</li>
 *   <li>순기여는 후보 수일 뿐 품질이 아니다. 줄인 뒤 채택·폴백 지표로 전후를 다시 본다 — 이 프로브는
 *       "무엇을 줄일지"까지만 답한다</li>
 * </ol>
 *
 * <p><b>판정용이지 회귀 테스트가 아니다.</b> 단언 없이 콘솔 표와 CSV 로 남긴다.
 *
 * <p>호출 규모(30요청 전량): Planner 30회 · 네이버 약 1,500회(월 775,000건의 0.2%) · 카카오 약 90회 ·
 * TourAPI 약 270회(일 1,000건).
 *
 * <pre>{@code
 * ./gradlew benchmarkTest --tests '*NaverQueryAuditProbeTest*' --rerun
 * # 일부만: NAVER_AUDIT_REQUEST_FROM=1 NAVER_AUDIT_REQUEST_LIMIT=2
 * }</pre>
 */
@Tag("benchmark")
@DisplayName("네이버 지역검색 질의 구성 감사 (이슈 #179)")
class NaverQueryAuditProbeTest {

    private static final Path CSV = HallucinationArtifacts.RESULTS_DIR.resolve("naver-query-audit.csv");

    /** 기본 질의 행의 수식어 칸. */
    private static final String BASE = "(기본)";

    /** 운영 예산·LLM 상한. Planner 만 부르므로 슬롯 수는 의미가 없다. */
    private static final PipelineBenchmarkWiring.Limits LIMITS =
        new PipelineBenchmarkWiring.Limits(30_000, 20_000, 4);

    /** HTTP 호출 1회. */
    private record Call(String query, String outcome, int raw) {}

    /**
     * 시더 호출 1회(스테이지의 {@code SeedSpec} 하나) = CSV 한 행.
     *
     * @param kept          필터와 단계 병합을 거쳐 남은 후보 이름(정규화)
     * @param newVsBase     같은 day·슬롯의 기본 질의에 없던 후보 수. 기본 질의 행은 -1
     * @param inPool        이 질의에서만 나온 후보 중 최종 풀(Curator 가 보는 목록)에 오른 수.
     *                      기본 질의 행은 -1
     */
    private record SpecRow(int requestId, String region, String tier, String keywordSet, int day,
                           String area, SlotType slotType, String modifier, List<Call> calls,
                           String outcome, int categoryMismatch, int outOfRegion,
                           int noCoordinates, Set<String> kept, int newVsBase, int inPool) {

        boolean isBase() {
            return BASE.equals(modifier);
        }

        int raw() {
            return calls.stream().mapToInt(Call::raw).sum();
        }

        SpecRow withContribution(int newVsBase, int inPool) {
            return new SpecRow(requestId, region, tier, keywordSet, day, area, slotType, modifier,
                calls, outcome, categoryMismatch, outOfRegion, noCoordinates, kept, newVsBase,
                inPool);
        }
    }

    /** 지나가는 HTTP 호출을 기록한다. 결과는 그대로 돌려준다. */
    private static final class RecordingNaverClient extends NaverLocalClient {

        private final List<Call> calls = new ArrayList<>();

        RecordingNaverClient(String id, String secret) {
            super(NaverConfig.buildNaverWebClient("https://naverapihub.apigw.ntruss.com", id,
                secret));
        }

        @Override
        public NaverLocalResult search(String query, int display) {
            NaverLocalResult result = super.search(query, display);
            calls.add(switch (result) {
                case NaverLocalResult.Found found -> new Call(query, "found", found.places().size());
                case NaverLocalResult.Empty ignored -> new Call(query, "empty", 0);
                case NaverLocalResult.Failed failed ->
                    new Call(query, "failed:" + failed.cause(), 0);
            });
            return result;
        }
    }

    /**
     * 시더 호출 하나를 그 호출이 낸 HTTP 호출·탈락 증분·남은 후보와 묶는다. 동기 실행기 위에서만
     * 귀속이 성립한다(클래스 설명 참고).
     */
    private static final class AuditingSeedSource extends NaverLocalSeedSource {

        private final RecordingNaverClient client;
        private final SimpleMeterRegistry registry;
        private final List<SpecRow> rows = new ArrayList<>();
        private RequestSpec request;
        private Map<String, List<Integer>> daysByArea = Map.of();
        private final Map<String, Integer> consumed = new HashMap<>();

        AuditingSeedSource(RecordingNaverClient client, SimpleMeterRegistry registry) {
            super(client, new AiCourseMetrics(registry));
            this.client = client;
            this.registry = registry;
        }

        void startRequest(RequestSpec request, PlannerPlan plan) {
            this.request = request;
            this.daysByArea = plan.days().stream().collect(Collectors.groupingBy(
                PlannerDayPlan::area, LinkedHashMap::new,
                Collectors.mapping(PlannerDayPlan::day, Collectors.toList())));
            this.consumed.clear();
        }

        /** 기본 질의는 스테이지가 이쪽으로 부른다(첫 응답이 꽉 찼는지가 함께 온다). */
        @Override
        public BaseSeed fetchBase(String area, List<Fallback> fallbacks, SlotType slotType,
            Double anchorLatitude, Double anchorLongitude) {
            BaseSeed[] seed = new BaseSeed[1];
            audit(area, slotType, null, () -> {
                seed[0] = super.fetchBase(area, fallbacks, slotType, anchorLatitude,
                    anchorLongitude);
                return seed[0].batch();
            });
            return seed[0];
        }

        @Override
        public CandidateBatch fetch(String area, List<Fallback> fallbacks, SlotType slotType,
            StyleTag modifier, Double anchorLatitude, Double anchorLongitude) {
            return audit(area, slotType, modifier, () -> super.fetch(area, fallbacks, slotType,
                modifier, anchorLatitude, anchorLongitude));
        }

        private CandidateBatch audit(String area, SlotType slotType, StyleTag modifier,
            java.util.function.Supplier<CandidateBatch> call) {
            int from = client.calls.size();
            double mismatch = dropped("category_mismatch");
            double outOfRegion = dropped("out_of_region");
            double noCoordinates = dropped("no_coordinates");

            CandidateBatch batch = call.get();

            String modifierLabel = modifier == null ? BASE : modifier.name();
            rows.add(new SpecRow(request.requestId(), request.region().name(),
                request.region().tier().name(), request.keywordSet().id(),
                dayOf(area, slotType, modifierLabel), area, slotType, modifierLabel,
                List.copyOf(client.calls.subList(from, client.calls.size())),
                batch.outcome() == CandidateOutcome.FAILED
                    ? "failed:" + batch.cause() : batch.outcome().name().toLowerCase(Locale.ROOT),
                (int) (dropped("category_mismatch") - mismatch),
                (int) (dropped("out_of_region") - outOfRegion),
                (int) (dropped("no_coordinates") - noCoordinates),
                batch.candidates().stream().map(c -> key(c.name()))
                    .collect(Collectors.toCollection(HashSet::new)),
                -1, -1));
            return batch;
        }

        /** 두 day 가 같은 권역이면 같은 질의가 두 번 온다 — 들어온 순서대로 day 를 배정한다. */
        private int dayOf(String area, SlotType slotType, String modifier) {
            List<Integer> days = daysByArea.getOrDefault(area, List.of(0));
            int seen = consumed.merge(area + "|" + slotType + "|" + modifier, 1, Integer::sum);
            return days.get(Math.min(seen - 1, days.size() - 1));
        }

        private double dropped(String reason) {
            Counter counter = registry.find(AiCourseMetrics.CANDIDATE_DROPPED)
                .tag("source", AiCourseMetrics.SOURCE_NAVER_LOCAL).tag("reason", reason).counter();
            return counter == null ? 0 : counter.count();
        }
    }

    @Test
    @DisplayName("기준선 30요청의 Planner 계획으로 운영 후보 공급을 돌려 질의별 결과를 남긴다")
    void audit() throws IOException {
        Map<String, String> dotEnv = loadDotEnv(Path.of(".env"));
        String openAiKey = resolve(dotEnv, "OPENAI_API_KEY");
        String naverId = resolve(dotEnv, "NAVER_CLIENT_ID");
        String naverSecret = resolve(dotEnv, "NAVER_CLIENT_SECRET");
        String tourKey = resolve(dotEnv, "TOUR_API_KEY");
        String kakaoKey = resolve(dotEnv, "KAKAO_API_KEY");
        assumeTrue(openAiKey != null && naverId != null && naverSecret != null && tourKey != null
            && kakaoKey != null, "OpenAI·네이버·TourAPI·카카오 키가 모두 있어야 감사할 수 있다");

        List<RequestSpec> all = BaselineInputSet.buildInputSet();
        int from = (int) setting("naver.audit.requestFrom", "NAVER_AUDIT_REQUEST_FROM", 1);
        int limit = (int) setting("naver.audit.requestLimit", "NAVER_AUDIT_REQUEST_LIMIT",
            all.size());
        int start = Math.min(Math.max(0, from - 1), all.size());
        List<RequestSpec> inputs = all.subList(start, Math.min(start + limit, all.size()));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiCourseMetrics metrics = new AiCourseMetrics(registry);
        KakaoLocalClient kakaoClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));
        RecordingNaverClient naverClient = new RecordingNaverClient(naverId, naverSecret);
        AuditingSeedSource seedSource = new AuditingSeedSource(naverClient, registry);
        TourApiSource tourSource = new TourApiSource(new TourApiClient(
            TourApiConfig.buildTourApiWebClient("https://apis.data.go.kr/B551011/KorService2"),
            tourKey));
        CandidateRetrievalStage stage = new CandidateRetrievalStage(new AreaGeocoder(kakaoClient),
            seedSource, tourSource, metrics, Runnable::run);
        PlannerAgent planner = PipelineBenchmarkWiring.planner(registry, openAiKey, LIMITS,
            Runnable::run);

        List<SpecRow> rows = new ArrayList<>();
        try {
            for (RequestSpec request : inputs) {
                List<KeywordType> keywords = request.keywordSet().keywords();
                PlannerPlan plan = planner.plan(request.region().name(),
                    BaselineInputSet.TRIP_DAYS, keywords, CourseDeadline.unbounded());
                seedSource.startRequest(request, plan);
                int rowsBefore = seedSource.rows.size();
                int callsBefore = naverClient.calls.size();

                CandidatePool pool = stage.retrieve(request.region().name(), plan, keywords,
                    CourseDeadline.unbounded());

                List<SpecRow> requestRows = withContribution(
                    seedSource.rows.subList(rowsBefore, seedSource.rows.size()), pool);
                rows.addAll(requestRows);
                System.out.printf("#%02d %s-%s 수식어=%s 시더 %d · HTTP %d%n",
                    request.requestId(), request.region().name(), request.keywordSet().id(),
                    StyleModifierDictionary.modifiersFor(keywords), requestRows.size(),
                    naverClient.calls.size() - callsBefore);
                for (PlannerDayPlan day : plan.days()) {
                    System.out.printf("    day %d area=%s anchor=%s slots=%s%n", day.day(),
                        day.area(), day.anchor(), day.slots());
                }
            }
        } finally {
            // 중간에 끊겨도 모은 만큼은 남긴다 — 다시 돌리면 Planner 비용을 또 낸다.
            writeCsv(rows);
            printSummary(rows);
        }
    }

    /**
     * 수식어 질의마다 순기여를 채운다.
     *
     * <p><b>최종 풀 반영은 {@code matchedModifier}로 본다.</b> 병합은 기본 질의를 먼저 넣어 "먼저
     * 만난 쪽이 이긴다"이므로, 풀에서 이 값이 남은 후보는 <b>수식어 질의로만 들어온 후보</b>다.
     */
    private static List<SpecRow> withContribution(List<SpecRow> requestRows, CandidatePool pool) {
        Map<String, Set<String>> baseKept = new HashMap<>();
        for (SpecRow row : requestRows) {
            if (row.isBase()) {
                baseKept.put(row.day() + "|" + row.slotType(), row.kept());
            }
        }
        List<SpecRow> result = new ArrayList<>(requestRows.size());
        for (SpecRow row : requestRows) {
            if (row.isBase()) {
                result.add(row);
                continue;
            }
            Set<String> base = baseKept.getOrDefault(row.day() + "|" + row.slotType(), Set.of());
            Set<String> fresh = new HashSet<>(row.kept());
            fresh.removeAll(base);
            CandidateSlot slot = pool.findOrEmpty(row.day(), row.slotType());
            int inPool = (int) slot.candidates().stream()
                .filter(c -> c.matchedModifier() != null
                    && c.matchedModifier().name().equals(row.modifier()))
                .map(PlaceCandidate::name).map(NaverQueryAuditProbeTest::key)
                .filter(fresh::contains)
                .count();
            result.add(row.withContribution(fresh.size(), inPool));
        }
        return result;
    }

    // ── 집계 ────────────────────────────────────────────────────────────────

    private static void printSummary(List<SpecRow> rows) {
        if (rows.isEmpty()) {
            System.out.println("집계할 행이 없다");
            return;
        }
        long requests = rows.stream().map(SpecRow::requestId).distinct().count();
        long http = rows.stream().mapToLong(r -> r.calls().size()).sum();
        System.out.printf("%n== 요청 %d건 · 시더 호출 %d (요청당 %.1f) · HTTP %d (요청당 %.1f)%n",
            requests, rows.size(), rows.size() / (double) requests, http, http / (double) requests);

        System.out.println("\n== 질의 종류별");
        printTable(rows, r -> r.isBase() ? "기본" : "수식어");

        System.out.println("\n== 슬롯 × 질의 종류");
        printTable(rows, r -> r.slotType() + " / " + (r.isBase() ? "기본" : "수식어"));

        System.out.println("\n== 수식어 × 슬롯 (수식어 질의만)");
        printTable(rows.stream().filter(r -> !r.isBase()).toList(),
            r -> r.modifier() + " / " + r.slotType());
    }

    private static void printTable(List<SpecRow> rows, Function<SpecRow, String> groupBy) {
        Map<String, List<SpecRow>> groups = rows.stream()
            .collect(Collectors.groupingBy(groupBy, TreeMap::new, Collectors.toList()));
        System.out.printf("%-34s %5s %5s %7s %7s %7s %7s %7s %7s%n", "그룹", "질의", "HTTP",
            "0건%", "원본", "업종X", "권역X", "남음", "순기여");
        for (Map.Entry<String, List<SpecRow>> group : groups.entrySet()) {
            List<SpecRow> g = group.getValue();
            double n = g.size();
            long empty = g.stream().filter(r -> r.kept().isEmpty()).count();
            List<SpecRow> modifierRows = g.stream().filter(r -> !r.isBase()).toList();
            String contribution = modifierRows.isEmpty() ? "-" : "%.2f".formatted(
                modifierRows.stream().mapToInt(SpecRow::newVsBase).average().orElse(0));
            System.out.printf("%-34s %5d %5d %6.0f%% %7.2f %7.2f %7.2f %7.2f %7s%n",
                group.getKey(), g.size(), g.stream().mapToInt(r -> r.calls().size()).sum(),
                100.0 * empty / n,
                g.stream().mapToInt(SpecRow::raw).sum() / n,
                g.stream().mapToInt(SpecRow::categoryMismatch).sum() / n,
                g.stream().mapToInt(SpecRow::outOfRegion).sum() / n,
                g.stream().mapToInt(r -> r.kept().size()).sum() / n,
                contribution);
        }
    }

    private static void writeCsv(List<SpecRow> rows) throws IOException {
        Files.createDirectories(CSV.getParent());
        StringBuilder csv = new StringBuilder(
            "requestId,region,tier,keywordSet,day,area,slotType,modifier,httpCalls,queries,"
                + "callOutcomes,outcome,raw,categoryMismatch,outOfRegion,noCoordinates,kept,"
                + "newVsBase,inPool\n");
        for (SpecRow r : rows) {
            csv.append(String.join(",",
                    String.valueOf(r.requestId()), r.region(), r.tier(), r.keywordSet(),
                    String.valueOf(r.day()), quote(r.area()), r.slotType().name(), r.modifier(),
                    String.valueOf(r.calls().size()),
                    quote(r.calls().stream().map(Call::query).collect(Collectors.joining(" | "))),
                    quote(r.calls().stream().map(c -> c.outcome() + ":" + c.raw())
                        .collect(Collectors.joining(" | "))),
                    r.outcome(), String.valueOf(r.raw()), String.valueOf(r.categoryMismatch()),
                    String.valueOf(r.outOfRegion()), String.valueOf(r.noCoordinates()),
                    String.valueOf(r.kept().size()), String.valueOf(r.newVsBase()),
                    String.valueOf(r.inPool())))
                .append('\n');
        }
        Files.writeString(CSV, csv, StandardCharsets.UTF_8);
        System.out.println("CSV: " + CSV.toAbsolutePath());
    }

    private static String key(String name) {
        return name == null ? "" : name.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static String quote(String value) {
        return value == null ? "" : "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
