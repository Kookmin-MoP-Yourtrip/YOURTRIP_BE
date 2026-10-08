package backend.yourtrip.global.benchmark;

import static backend.yourtrip.global.benchmark.BenchmarkEnv.loadDotEnv;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.resolve;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.setting;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.sleep;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.RESULTS_DIR;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.csv;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.oneLine;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.writeUtf8Bom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.agent.CuratorAgent;
import backend.yourtrip.global.ai.agent.DefaultPlannerPlans;
import backend.yourtrip.global.ai.agent.PlannerAgent;
import backend.yourtrip.global.ai.pipeline.AiCourseDraft;
import backend.yourtrip.global.ai.pipeline.AiCoursePipeline;
import backend.yourtrip.global.ai.pipeline.CourseBrief;
import backend.yourtrip.global.ai.pipeline.PipelineStage;
import backend.yourtrip.global.benchmark.BaselineInputSet.RegionTier;
import backend.yourtrip.global.benchmark.BaselineInputSet.RequestSpec;
import backend.yourtrip.global.config.AsyncConfig;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * <b>LLM 호출 경로 기준선</b> — 로드맵 2단계, 이슈 #175.
 *
 * <p>{@code docs/tasks/llm-performance/README.md}의 1단계 E2E는 표본 1건이라 "세 번째 Curator가
 * 슬롯을 6.7~8.1초 기다린다"가 늘 그런지 말할 수 없었다. 이 하네스는 {@link BaselineInputSet}의
 * 30요청을 순차로 태워 <b>요청별·단계별 지연, 슬롯 대기, 폴백, 토큰</b>을 한 행씩 남긴다.
 * #108({@code max-concurrent-calls} 조정)의 "개선 전" 측정을 겸한다 — 4단계에서 {@link #LIMITS}의
 * 동시 호출 수만 바꿔 같은 세트로 다시 돈다.
 *
 * <h2>운영값으로 잰다 — 환각률 하네스와 갈리는 지점</h2>
 *
 * <p>{@link AiPipelineHallucinationBenchmarkTest}는 예산을 180초, LLM 상한을 60초로 늘려 표본이
 * 잘리지 않게 한다. 그쪽이 재는 것이 장소의 실존이라 잘린 요청이 분모에서 빠지면 안 되기 때문이다.
 *
 * <p><b>여기서는 거꾸로 운영값(35초·20초)을 쓴다.</b> 4단계 전후로 비교할 대상이 "사용자가 실제로
 * 받는 결과"이고, 슬롯 대기 포기({@code permit.wait{result=timeout}})·Planner 기본 플랜 대체·
 * Curator 폴백은 운영값에서만 일어난다. 대가로 예산을 넘는 꼬리는 "약 35초"로 눌려 보이므로,
 * 마감에 닿은 요청 수를 따로 센다.
 *
 * <h2>요청별 귀속 — 증분을 슬롯이 빈 뒤에 뜬다</h2>
 *
 * <p>요청마다 레지스트리 스냅샷의 차이를 그 요청의 값으로 삼는다. 단계 타이머는 요청마다 한 번씩만
 * 기록되므로 증분이 곧 그 요청의 단계 시간이다.
 *
 * <p>함정이 하나 있다 — <b>마감 뒤에도 LLM 호출이 뒤에서 계속 돈다</b>(로드맵 3단계).
 * {@code generate()}가 돌아온 직후에 증분을 뜨면 그 호출의 토큰·대기·결말이 <b>다음 요청</b>의
 * 증분에 섞인다. 그래서 반환 뒤 슬롯 게이지({@code in_use}·{@code waiting})가 0이 될 때까지
 * 기다렸다가 증분을 뜬다. 이 기다림의 길이({@code drainMs})가 그 자체로 "응답이 나간 뒤에도 남은
 * 호출"의 크기라 함께 기록한다 — 3단계의 근거 자료다.
 *
 * <h2>측정하지 않는 것</h2>
 *
 * <ul>
 *   <li>동시 요청 간 슬롯 경합, Tomcat 워커 점유 — HTTP 동시 시나리오({@code scripts/k6/ai-course-concurrent.js})가 맡는다
 *   <li>환각률 — 카카오 채점을 하지 않는다. 쿼터와 시간을 아끼고, 지연에 채점 시간이 섞이지 않는다
 *   <li>개별 호출의 슬롯 대기 최댓값 — {@code SimpleMeterRegistry}의 {@code max}는 시간 창으로
 *       감쇠해 요청 단위로 뗄 수 없다. 대기는 요청별 <b>합과 횟수</b>로 남긴다
 * </ul>
 *
 * <h2>실행</h2>
 *
 * <pre>
 * 전체 측정: ./gradlew benchmarkTest --tests '*AiCourseLatencyBaselineTest*' --rerun
 * 스모크:    LATENCY_BASELINE_REQUEST_LIMIT=2 ./gradlew ... --rerun
 * 이어서:    LATENCY_BASELINE_REQUEST_FROM=15 LATENCY_BASELINE_WARMUP=0 ./gradlew ... --rerun
 * 4단계:     LATENCY_BASELINE_MAX_CONCURRENT_CALLS=4 ./gradlew ... --rerun
 * </pre>
 *
 * <p>30요청 + 웜업 1건이면 LLM 약 124회 · TourAPI ≤ 279(일 1,000 한도) · 네이버·카카오는 파이프라인
 * 몫만 든다.
 */
@org.junit.jupiter.api.Tag("benchmark")
class AiCourseLatencyBaselineTest {

    /**
     * 운영 설정({@code application.yml})의 동시 호출 수(#184 에서 4 → 5). 기준선(2단계)은 당시 운영값 2로 쟀다 — 2단계를
     * 다시 재현하려면 {@code LATENCY_BASELINE_MAX_CONCURRENT_CALLS=2}를 준다.
     */
    private static final int PRODUCTION_MAX_CONCURRENT_CALLS = 5;

    /**
     * 예산·LLM 상한은 운영값으로 고정하고, <b>동시 호출 수만 환경변수로 바꾼다</b>(4단계, #108).
     * 코드를 고쳐 바꾸지 않는 이유 — 기준선과 개선 측정이 같은 커밋의 같은 하네스에서 나와야
     * 전후 차이를 그 한 값에 귀속할 수 있다. 산출물 파일명에 값이 실려 섞이지 않는다.
     *
     * <p>예산은 운영값을 따라 35초다(#189). 2·4단계 기준선은 30초에서 쟀으므로, 그 수치와 나란히
     * 놓을 때는 예산 조건이 다르다는 점을 함께 적는다.
     */
    private static final PipelineBenchmarkWiring.Limits LIMITS =
        new PipelineBenchmarkWiring.Limits(35_000, 20_000, (int) setting(
            "latency.baseline.maxConcurrentCalls", "LATENCY_BASELINE_MAX_CONCURRENT_CALLS",
            PRODUCTION_MAX_CONCURRENT_CALLS));

    /**
     * 여행 일수. 기본은 공유 입력 세트의 3일이고, 일수 상한(#178)을 정할 때만 환경변수로 바꾼다.
     * Curator 는 day 마다 호출 1개라 일수가 곧 요청 하나의 LLM 작업 수를 정한다 — 슬롯 수를 넘는
     * 일수에서 단일 요청이 예산 안에 끝나는지를 같은 하네스로 잰다. 산출물 파일명에 실려 섞이지 않는다.
     */
    private static final int TRIP_DAYS = (int) setting("latency.baseline.tripDays",
        "LATENCY_BASELINE_TRIP_DAYS", BaselineInputSet.TRIP_DAYS);

    /** 요청 간 휴지. 슬롯이 빈 뒤에도 두는 것은 RPM·외부 API 쿼터에 대한 예의다. */
    private static final long DEFAULT_DELAY_MS = 5_000L;

    /**
     * 슬롯이 빌 때까지 기다리는 상한. 남은 호출 하나가 쓸 수 있는 최악은 대기 20초 + HTTP 20초에
     * 재시도가 곱해진 값이라, 이 값을 넘으면 기다리지 않고 기록만 남긴다(행에 표시된다).
     */
    private static final long DRAIN_TIMEOUT_MS = 90_000L;

    /** 이 이상이면 마감에 닿은 요청으로 센다. 예산 판정과 스테이지 정리 사이의 수백 ms 를 흡수한다. */
    private static final long NEAR_BUDGET_MS = LIMITS.budgetMs() - 1_000L;

    private static final int ABORT_AFTER_CONSECUTIVE_FAILURES = 3;

    /** 설계 지연 예산 — {@link PipelineStage} javadoc. 추정 대 실측 표의 왼쪽 열이다. */
    private static final Map<PipelineStage, String> DESIGN_ESTIMATE = designEstimate();

    private enum Outcome { OK, FAILED }

    /** 요청 1건 = CSV 1행. 시간 단위는 전부 ms. */
    private record RequestRow(
        int requestId, String location, RegionTier tier, String keywordSetId,
        Outcome outcome, String failureDetail, long elapsedMs, long drainMs, boolean drainTimedOut,
        boolean plannerFallback, Map<PipelineStage, Long> stageMs,
        long plannerWaitMs, long curatorWaitMs, long curatorWaitCount, long permitTimeouts,
        long plannerCallMs, long curatorCallMs, long llmCalls, long llmNonSuccess,
        long llmResponses, long tokensInput, long tokensOutput, long tokensReasoning,
        long tokensCached,
        long curationCurator, long curationFallback, long curationUnfilled, int totalPlaces
    ) {}

    @Test
    @DisplayName("운영값으로 30요청을 순차 실행해 단계별 지연·슬롯 대기·폴백·토큰 기준선을 남긴다")
    void measureBaseline() throws IOException {
        Map<String, String> dotEnv = loadDotEnv(java.nio.file.Path.of(".env"));
        String openAiKey = resolve(dotEnv, "OPENAI_API_KEY");
        String naverId = resolve(dotEnv, "NAVER_CLIENT_ID");
        String naverSecret = resolve(dotEnv, "NAVER_CLIENT_SECRET");
        String tourKey = resolve(dotEnv, "TOUR_API_KEY");
        String kakaoKey = resolve(dotEnv, "KAKAO_API_KEY");
        assumeTrue(openAiKey != null && naverId != null && naverSecret != null && tourKey != null
            && kakaoKey != null, "OpenAI·네이버·TourAPI·카카오 키가 모두 있어야 측정할 수 있다");

        assertThat(TRIP_DAYS).as("LATENCY_BASELINE_TRIP_DAYS 는 1 이상이어야 한다").isPositive();

        List<RequestSpec> fullInputSet = BaselineInputSet.buildInputSet();
        int from = (int) setting("latency.baseline.requestFrom",
            "LATENCY_BASELINE_REQUEST_FROM", 1);
        int limit = (int) setting("latency.baseline.requestLimit",
            "LATENCY_BASELINE_REQUEST_LIMIT", fullInputSet.size());
        boolean warmup = setting("latency.baseline.warmup", "LATENCY_BASELINE_WARMUP", 1) != 0;
        long delayMs = setting("latency.baseline.delayMs", "LATENCY_BASELINE_DELAY_MS",
            DEFAULT_DELAY_MS);
        int startIndex = Math.min(Math.max(0, from - 1), fullInputSet.size());
        int endIndex = Math.min(startIndex + limit, fullInputSet.size());
        List<RequestSpec> inputSet = fullInputSet.subList(startIndex, endIndex);

        String runTag = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KakaoLocalClient kakaoClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));

        // 운영 배선을 그대로 쓴다 — PipelineBenchmarkWiring javadoc "실행기는 운영 것을 쓴다" 참고.
        AsyncConfig asyncConfig = new AsyncConfig();
        ThreadPoolTaskExecutor agentExecutor = AsyncConfig.agentExecutorFor(LIMITS.maxConcurrentCalls());
        ThreadPoolTaskExecutor groundingExecutor = asyncConfig.placeGroundingExecutor();
        AiCoursePipeline pipeline = PipelineBenchmarkWiring.pipeline(registry, kakaoClient,
            agentExecutor, groundingExecutor,
            new PipelineBenchmarkWiring.ApiKeys(openAiKey, naverId, naverSecret, tourKey), LIMITS);

        System.out.printf("%n=== LLM 호출 경로 기준선: 요청 %d~%d (%d건), 여행 %d일 ===%n",
            startIndex + 1, endIndex, inputSet.size(), TRIP_DAYS);
        System.out.printf("    예산 %,dms · LLM 상한 %,dms · 동시 호출 %d%s · 요청 간 휴지 %,dms%n",
            LIMITS.budgetMs(), LIMITS.llmTimeoutMs(), LIMITS.maxConcurrentCalls(),
            LIMITS.maxConcurrentCalls() == PRODUCTION_MAX_CONCURRENT_CALLS ? " (운영값)" : " (운영값 아님)",
            delayMs);

        List<RequestRow> rows = new ArrayList<>();
        try {
            if (warmup && !inputSet.isEmpty()) {
                // 첫 요청은 커넥션 풀·TLS·JIT 비용을 함께 낸다. 같은 입력을 한 번 버려 표본에서 뺀다.
                RequestRow discarded = runOne(pipeline, registry, inputSet.get(0));
                System.out.printf("  [웜업 — 집계 제외] %,dms%n", discarded.elapsedMs());
                sleep(delayMs);
            }
            collect(pipeline, registry, inputSet, delayMs, rows);
        } finally {
            // 조기 중단해도 그때까지의 행은 남긴다 — 요청당 LLM 4회라 다시 태우는 비용이 크다.
            if (rows.isEmpty()) {
                System.out.printf("%n측정된 것이 없어 CSV 를 쓰지 않는다.%n");
            } else {
                writeCsv(runTag, rows);
                report(rows, runTag);
            }
            agentExecutor.shutdown();
            groundingExecutor.shutdown();
        }

        assertThat(rows).as("측정된 요청이 하나도 없다 — 키나 네트워크를 확인하라").isNotEmpty();
    }

    private static void collect(AiCoursePipeline pipeline, SimpleMeterRegistry registry,
        List<RequestSpec> inputSet, long delayMs, List<RequestRow> rows) {

        int consecutiveFailures = 0;
        for (RequestSpec spec : inputSet) {
            RequestRow row = runOne(pipeline, registry, spec);
            rows.add(row);
            printRow(row);

            consecutiveFailures = row.outcome() == Outcome.FAILED ? consecutiveFailures + 1 : 0;
            assertThat(consecutiveFailures)
                .as("연속 %d회 실패했다 — 키·쿼터를 확인하고 LATENCY_BASELINE_REQUEST_FROM=%d "
                    + "LATENCY_BASELINE_WARMUP=0 으로 이어 돌려라", consecutiveFailures,
                    spec.requestId())
                .isLessThan(ABORT_AFTER_CONSECUTIVE_FAILURES);
            sleep(delayMs);
        }
    }

    private static RequestRow runOne(AiCoursePipeline pipeline, SimpleMeterRegistry registry,
        RequestSpec spec) {

        Map<String, double[]> before = snapshot(registry);
        long startNanos = System.nanoTime();

        AiCourseDraft draft = null;
        String failure = "";
        try {
            draft = pipeline.generate(CourseBrief.of(spec.region().name(),
                TRIP_DAYS, spec.keywordSet().keywords()));
        } catch (RuntimeException e) {
            failure = oneLine(e.toString());
        }
        long elapsedMs = elapsedMs(startNanos);

        // 응답이 나간 뒤에도 남은 호출을 이 요청에 귀속시킨다 — 클래스 javadoc "요청별 귀속".
        long drainStart = System.nanoTime();
        boolean drained = awaitSlotsIdle(registry, DRAIN_TIMEOUT_MS);
        long drainMs = elapsedMs(drainStart);

        Delta d = new Delta(before, snapshot(registry));
        Map<PipelineStage, Long> stageMs = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.values()) {
            stageMs.put(stage, d.total(AiCourseMetrics.PIPELINE_DURATION,
                "stage", stage.name().toLowerCase(Locale.ROOT)));
        }

        String planner = PlannerAgent.AGENT_NAME;
        String curator = CuratorAgent.AGENT_NAME;
        String waitName = AiCourseMetrics.LLM_PERMIT_WAIT;
        String callName = AiCourseMetrics.LLM_CALL;
        String tokens = AiCourseMetrics.LLM_TOKENS;

        boolean plannerFallback = draft != null && draft.title().equals(
            DefaultPlannerPlans.defaultTitle(spec.region().name(), TRIP_DAYS));
        int totalPlaces = draft == null ? 0
            : draft.days().stream().mapToInt(day -> day.places().size()).sum();

        return new RequestRow(spec.requestId(), spec.region().name(), spec.region().tier(),
            spec.keywordSet().id(), draft == null ? Outcome.FAILED : Outcome.OK, failure,
            elapsedMs, drainMs, !drained, plannerFallback, stageMs,
            d.total(waitName, "agent", planner),
            d.total(waitName, "agent", curator),
            d.count(waitName, "agent", curator),
            d.count(waitName, "result", AiCourseMetrics.PERMIT_TIMEOUT),
            d.total(callName, "agent", planner),
            d.total(callName, "agent", curator),
            d.count(callName),
            d.count(callName) - d.count(callName, "outcome", AiCourseMetrics.LLM_OUTCOME_SUCCESS),
            d.count(tokens, "type", AiCourseMetrics.TOKEN_INPUT),
            d.total(tokens, "type", AiCourseMetrics.TOKEN_INPUT),
            d.total(tokens, "type", AiCourseMetrics.TOKEN_OUTPUT),
            d.total(tokens, "type", AiCourseMetrics.TOKEN_REASONING),
            d.total(tokens, "type", AiCourseMetrics.TOKEN_CACHED),
            d.count(AiCourseMetrics.CURATION_SLOT, "result", "curator"),
            d.count(AiCourseMetrics.CURATION_SLOT, "result", "fallback"),
            d.count(AiCourseMetrics.CURATION_SLOT, "result", "unfilled"),
            totalPlaces);
    }

    /** 슬롯을 쥔 호출도, 슬롯을 기다리는 호출도 없을 때까지 기다린다. */
    private static boolean awaitSlotsIdle(SimpleMeterRegistry registry, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (gauge(registry, AiCourseMetrics.LLM_PERMITS_IN_USE) == 0
                && gauge(registry, AiCourseMetrics.LLM_PERMITS_WAITING) == 0) {
                return true;
            }
            sleep(100);
        }
        return false;
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        Gauge gauge = registry.find(name).gauge();
        assertThat(gauge).as("%s 게이지가 없다 — OpenAiLlmClient 배선을 확인하라", name).isNotNull();
        return gauge.value();
    }

    // ── 스냅샷과 증분 ─────────────────────────────────────────────────────────

    /**
     * 미터마다 {@code [count, total]}. 키는 이름과 정렬된 태그다. total 은 Timer 면 ms,
     * DistributionSummary 면 양(토큰), Counter 면 count 와 같다.
     */
    private static Map<String, double[]> snapshot(SimpleMeterRegistry registry) {
        Map<String, double[]> values = new HashMap<>();
        for (Meter meter : registry.getMeters()) {
            double[] value;
            if (meter instanceof Timer timer) {
                value = new double[]{timer.count(), timer.totalTime(TimeUnit.MILLISECONDS)};
            } else if (meter instanceof DistributionSummary summary) {
                value = new double[]{summary.count(), summary.totalAmount()};
            } else if (meter instanceof io.micrometer.core.instrument.Counter counter) {
                value = new double[]{counter.count(), counter.count()};
            } else {
                continue;
            }
            values.put(key(meter), value);
        }
        return values;
    }

    private static String key(Meter meter) {
        StringBuilder sb = new StringBuilder(meter.getId().getName());
        for (Tag tag : meter.getId().getTags()) {
            sb.append('|').append(tag.getKey()).append('=').append(tag.getValue());
        }
        return sb.toString();
    }

    /** 두 스냅샷의 차. 조회는 "이름 + 태그 하나" 단위의 합이다(나머지 태그는 전부 더한다). */
    private record Delta(Map<String, double[]> before, Map<String, double[]> after) {

        long count(String name) {
            return Math.round(sum(name, null, null, 0));
        }

        long count(String name, String tagKey, String tagValue) {
            return Math.round(sum(name, tagKey, tagValue, 0));
        }

        /** Timer 면 ms, DistributionSummary 면 양(토큰)의 합. */
        long total(String name, String tagKey, String tagValue) {
            return Math.round(sum(name, tagKey, tagValue, 1));
        }

        private double sum(String name, String tagKey, String tagValue, int index) {
            String tagPart = tagKey == null ? null : "|" + tagKey + "=" + tagValue;
            double total = 0;
            for (Map.Entry<String, double[]> entry : after.entrySet()) {
                String k = entry.getKey();
                if (!(k.equals(name) || k.startsWith(name + "|"))) {
                    continue;
                }
                if (tagPart != null && !(k + "|").contains(tagPart + "|")) {
                    continue;
                }
                double[] prev = before.getOrDefault(k, new double[]{0, 0});
                total += entry.getValue()[index] - prev[index];
            }
            return total;
        }
    }

    // ── 산출물 ────────────────────────────────────────────────────────────────

    private static void printRow(RequestRow r) {
        System.out.printf("  #%02d %-3s %s : %s %,6dms | 플래너 %,5d 큐레이터 %,6d (대기 %,5d/%d회)"
                + " | 폴백 %d 슬롯%s%s | 잔여 %,dms%s%n",
            r.requestId(), r.location(), r.keywordSetId(), r.outcome(), r.elapsedMs(),
            r.stageMs().get(PipelineStage.PLANNER), r.stageMs().get(PipelineStage.CURATOR),
            r.curatorWaitMs(), r.curatorWaitCount(), r.curationFallback(),
            r.plannerFallback() ? " · 기본 플랜" : "",
            r.permitTimeouts() > 0 ? " · 슬롯 포기 " + r.permitTimeouts() : "",
            r.drainMs(), r.drainTimedOut() ? " (상한 초과)" : "");
        if (r.outcome() == Outcome.FAILED) {
            System.out.printf("      실패: %s%n", r.failureDetail());
        }
    }

    private static void writeCsv(String runTag, List<RequestRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("requestId,location,regionTier,keywordSet,outcome,failureDetail,elapsedMs,")
            .append("drainMs,drainTimedOut,plannerFallback,");
        for (PipelineStage stage : PipelineStage.values()) {
            sb.append("stage_").append(stage.name().toLowerCase(Locale.ROOT)).append("Ms,");
        }
        sb.append("plannerWaitMs,curatorWaitMs,curatorWaitCount,permitTimeouts,")
            .append("plannerCallMs,curatorCallMs,llmCalls,llmNonSuccess,")
            .append("llmResponses,tokensInput,tokensOutput,tokensReasoning,tokensCached,")
            .append("curationCurator,curationFallback,curationUnfilled,totalPlaces\n");

        for (RequestRow r : rows) {
            sb.append(r.requestId()).append(',').append(csv(r.location())).append(',')
                .append(r.tier()).append(',').append(csv(r.keywordSetId())).append(',')
                .append(r.outcome()).append(',').append(csv(r.failureDetail())).append(',')
                .append(r.elapsedMs()).append(',').append(r.drainMs()).append(',')
                .append(r.drainTimedOut()).append(',').append(r.plannerFallback()).append(',');
            for (PipelineStage stage : PipelineStage.values()) {
                sb.append(r.stageMs().get(stage)).append(',');
            }
            sb.append(r.plannerWaitMs()).append(',').append(r.curatorWaitMs()).append(',')
                .append(r.curatorWaitCount()).append(',').append(r.permitTimeouts()).append(',')
                .append(r.plannerCallMs()).append(',').append(r.curatorCallMs()).append(',')
                .append(r.llmCalls()).append(',').append(r.llmNonSuccess()).append(',')
                .append(r.llmResponses()).append(',').append(r.tokensInput()).append(',')
                .append(r.tokensOutput()).append(',').append(r.tokensReasoning()).append(',')
                .append(r.tokensCached()).append(',')
                .append(r.curationCurator()).append(',').append(r.curationFallback()).append(',')
                .append(r.curationUnfilled()).append(',').append(r.totalPlaces()).append('\n');
        }
        writeUtf8Bom(RESULTS_DIR.resolve(outputName(runTag)), sb.toString());
    }

    // ── 리포트 ────────────────────────────────────────────────────────────────

    private static void report(List<RequestRow> rows, String runTag) {
        List<RequestRow> ok = rows.stream().filter(r -> r.outcome() == Outcome.OK).toList();
        long failed = rows.size() - ok.size();

        System.out.printf("%n=== 요청 전체 (성공 %d / 실패 %d) ===%n", ok.size(), failed);
        printDistribution("요청 전체", ok, RequestRow::elapsedMs);
        long nearBudget = ok.stream().filter(r -> r.elapsedMs() >= NEAR_BUDGET_MS).count();
        System.out.printf("  마감(%,dms) 근접 %d / %d건 — 이 요청들의 실제 소요는 더 길었을 수 있다%n",
            NEAR_BUDGET_MS, nearBudget, ok.size());

        System.out.printf("%n=== 단계별 — 설계 추정 대 실측 ===%n");
        System.out.printf("  %-20s %-12s %8s %8s %8s%n", "stage", "설계", "p50", "p95", "max");
        for (PipelineStage stage : PipelineStage.values()) {
            List<Long> values = sorted(ok, r -> r.stageMs().get(stage));
            System.out.printf("  %-20s %-12s %,8d %,8d %,8d%n", stage, DESIGN_ESTIMATE.get(stage),
                percentile(values, 50), percentile(values, 95), values.get(values.size() - 1));
        }

        System.out.printf("%n=== 슬롯 대기 (요청별 합, 동시 호출 %d) ===%n",
            LIMITS.maxConcurrentCalls());
        printDistribution("Curator 대기 합", ok, RequestRow::curatorWaitMs);
        printDistribution("Planner 대기", ok, RequestRow::plannerWaitMs);
        long waited = ok.stream().filter(r -> r.curatorWaitMs() >= 100).count();
        System.out.printf("  Curator 가 100ms 이상 기다린 요청 %d / %d건%n", waited, ok.size());
        for (RegionTier tier : RegionTier.values()) {
            List<RequestRow> group = ok.stream().filter(r -> r.tier() == tier).toList();
            if (!group.isEmpty()) {
                printDistribution("  " + tier + " Curator 대기", group, RequestRow::curatorWaitMs);
            }
        }
        printDistribution("Curator 순수 호출(합)", ok, RequestRow::curatorCallMs);

        System.out.printf("%n=== 품질 저하 (운영값이라 보이는 것) ===%n");
        long slots = rows.stream().mapToLong(r -> r.curationCurator() + r.curationFallback()
            + r.curationUnfilled()).sum();
        long fallbackSlots = rows.stream().mapToLong(RequestRow::curationFallback).sum();
        System.out.printf("  Curator 폴백 슬롯  %d / %d (%.1f%%), 폴백이 낀 요청 %d건%n",
            fallbackSlots, slots, slots == 0 ? 0.0 : 100.0 * fallbackSlots / slots,
            rows.stream().filter(r -> r.curationFallback() > 0).count());
        System.out.printf("  Planner 기본 플랜  %d건%n",
            rows.stream().filter(RequestRow::plannerFallback).count());
        System.out.printf("  슬롯 대기 포기     %d회%n",
            rows.stream().mapToLong(RequestRow::permitTimeouts).sum());
        System.out.printf("  LLM 비성공 결말    %d회%n",
            rows.stream().mapToLong(RequestRow::llmNonSuccess).sum());

        System.out.printf("%n=== 응답 뒤 남은 호출 (로드맵 3단계 근거) ===%n");
        printDistribution("잔여 시간", rows, RequestRow::drainMs);
        System.out.printf("  잔여 100ms 이상 %d건, 상한 초과 %d건%n",
            rows.stream().filter(r -> r.drainMs() >= 100).count(),
            rows.stream().filter(RequestRow::drainTimedOut).count());

        System.out.printf("%n=== 토큰 (STEP-0 표본 1건 보완) ===%n");
        printDistribution("코스당 합계", ok, r -> r.tokensInput() + r.tokensOutput());
        printDistribution("응답당 평균", ok, r -> r.llmResponses() == 0 ? 0
            : (r.tokensInput() + r.tokensOutput()) / r.llmResponses());
        long responses = rows.stream().mapToLong(RequestRow::llmResponses).sum();
        long calls = rows.stream().mapToLong(RequestRow::llmCalls).sum();
        System.out.printf("  응답 %d개 / 호출 %d회 — 차이는 의미 재시도·절단이다%n", responses, calls);

        System.out.printf("%n=== 산출물 ===%n  results/%s%n", outputName(runTag));
    }

    private static void printDistribution(String label, List<RequestRow> rows,
        ToLongFunction<RequestRow> metric) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> values = sorted(rows, metric);
        System.out.printf("  %-24s p50 %,7d · p95 %,7d · max %,7d · min %,7d%n", label,
            percentile(values, 50), percentile(values, 95), values.get(values.size() - 1),
            values.get(0));
    }

    private static List<Long> sorted(List<RequestRow> rows, ToLongFunction<RequestRow> metric) {
        return rows.stream().map(metric::applyAsLong).sorted().toList();
    }

    /** 최근접 순위법. 표본이 30건이라 보간을 쓰면 없는 정밀도를 꾸미게 된다(환각률 하네스와 같다). */
    private static long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.min(Math.max(index, 0), sorted.size() - 1));
    }

    /**
     * 동시 호출 수를 파일명에 싣는다 — 기준선(c2)과 4단계 측정이 같은 디렉터리에 쌓인다.
     * 일수는 기본(3일)이 아닐 때만 붙여 기존 산출물 이름을 그대로 둔다.
     */
    private static String outputName(String runTag) {
        String days = TRIP_DAYS == BaselineInputSet.TRIP_DAYS ? "" : "-d" + TRIP_DAYS;
        return "latency-baseline-c" + LIMITS.maxConcurrentCalls() + days + "-" + runTag + ".csv";
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static Map<PipelineStage, String> designEstimate() {
        Map<PipelineStage, String> m = new EnumMap<>(PipelineStage.class);
        m.put(PipelineStage.PLANNER, "2.5~4.0s");
        m.put(PipelineStage.CANDIDATE_RETRIEVAL, "0.5~0.9s");
        m.put(PipelineStage.CURATOR, "3.0~6.0s");
        m.put(PipelineStage.GROUNDING, "0.2~0.4s");
        m.put(PipelineStage.ROUTE, "<10ms");
        m.put(PipelineStage.URL_ENRICH, "0.2~0.4s");
        return m;
    }
}
