package backend.yourtrip.global.benchmark;

import static backend.yourtrip.global.benchmark.BenchmarkEnv.loadDotEnv;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.resolve;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.setting;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.sleep;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.PLACE_CSV_HEADER;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.RESULTS_DIR;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.csv;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.oneLine;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.placeCsvRow;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.writeUtf8Bom;
import static backend.yourtrip.global.benchmark.HallucinationReport.pct;
import static backend.yourtrip.global.benchmark.HallucinationScoring.BAND_KAKAO_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.LlmResponseParser;
import backend.yourtrip.global.ai.LlmRetryExecutor;
import backend.yourtrip.global.ai.agent.CuratorAgent;
import backend.yourtrip.global.ai.agent.PlannerAgent;
import backend.yourtrip.global.ai.candidate.AreaGeocoder;
import backend.yourtrip.global.ai.candidate.CandidateRetrievalStage;
import backend.yourtrip.global.ai.candidate.NaverLocalSeedSource;
import backend.yourtrip.global.ai.candidate.TourApiSource;
import backend.yourtrip.global.ai.config.AiCourseProperties;
import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.grounding.GroundedPlace;
import backend.yourtrip.global.ai.grounding.GroundingStage;
import backend.yourtrip.global.ai.grounding.PlaceUrlEnricher;
import backend.yourtrip.global.ai.openai.OpenAiLlmClient;
import backend.yourtrip.global.ai.pipeline.AiCourseDay;
import backend.yourtrip.global.ai.pipeline.AiCourseDraft;
import backend.yourtrip.global.ai.pipeline.AiCoursePipeline;
import backend.yourtrip.global.ai.pipeline.AiCoursePlace;
import backend.yourtrip.global.ai.pipeline.CourseBrief;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.route.RouteOptimizer;
import backend.yourtrip.global.config.AsyncConfig;
import backend.yourtrip.global.benchmark.BaselineInputSet.RegionTier;
import backend.yourtrip.global.benchmark.BaselineInputSet.RequestSpec;
import backend.yourtrip.global.benchmark.HallucinationScoring.PlaceRow;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.config.NaverConfig;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.config.TourApiConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * <b>멀티 에이전트 파이프라인</b> 구조의 환각률 측정 하네스 (ROADMAP 8-6).
 *
 * <p>3점 비교의 마지막 점을 찍는다. {@link AiHallucinationBaselineTest}가 단일 LLM 호출을
 * 재는 자리에서, 이 클래스는 같은 입력 세트를 {@link AiCoursePipeline}에 태우고
 * <b>같은 채점기</b>({@link HallucinationScoring})로 출력 장소를 판정한다.
 *
 * <pre>
 *   Gemini 단일 호출     지어냄률 10.1% (장소 미확보율 21.1%)   — 완료
 *   OpenAI 단일 호출     (참고) 1.08%                          — 산출물 소실
 *   OpenAI 파이프라인    ← 이 하네스
 * </pre>
 *
 * <h2>측정 정의 — 파이프라인이 이미 검증했어도 다시 채점한다</h2>
 *
 * <p>파이프라인은 자체 그라운딩을 거쳐 좌표가 확보된 장소만 내보내므로, 그 출력은 정의상
 * "검증을 통과한 것"이다. 그런데도 <b>출력 장소 전건에 baseline 과 똑같은 카카오 검색과
 * 점수 판정을 다시 건다.</b> 절차가 같아야 두 값이 비교 가능하기 때문이다 — 파이프라인 쪽만
 * 자체 판정을 믿으면 "다른 자로 잰 값"이 되고, 그 순간 3점 비교가 무너진다(STEP-8 "측정 정의 고정").
 *
 * <p>같은 이유로 {@code placeLocation} 자리에는 day 별 권역({@code area})이 아니라
 * <b>요청의 {@code location}</b>을 넣는다. baseline 이 그렇게 하기 때문이다. 권역을 쓰면
 * 검색어가 더 좁아져 파이프라인에 유리해지는데, 그건 파이프라인의 개선이 아니라 채점의 변경이다.
 *
 * <h2>같은 밴드가 baseline 과 다른 것을 뜻한다 — 해석 주의</h2>
 *
 * <p>절차를 같게 맞췄다고 <b>의미</b>까지 같아지지는 않는다. {@code SEEDED}·{@code LISTED}는
 * {@code GroundingStage.resolve}의 승계 분기에서 <b>카카오를 한 번도 부르지 않고 무조건
 * {@code HIT}으로</b> 통과한다(호출 0회). 그래서 카카오에 없는 장소라도 네이버·TourAPI 가 준
 * 실좌표와 함께 최종 코스에 실린다.
 *
 * <pre>
 *   baseline   NO_RESULT = 좌표를 못 얻음        → 지도에 못 찍는 장소
 *   파이프라인  NO_RESULT = 카카오에 없을 뿐      → 좌표는 있고 placeUrl 만 빈다
 * </pre>
 *
 * <p>즉 파이프라인에서 <b>"장소 미확보율"이라는 이름은 사실과 어긋난다</b> — 장소는 확보했고
 * 확보하지 못한 것은 카카오 교차확인이다. 그래서 이 하네스는 출처별 분해를 함께 찍고, CSV 에
 * {@code source} 열을 남긴다. <b>baseline 과 직접 비교할 수 있는 것은 {@code SUGGESTED}뿐이다</b>
 * — 후보 목록 밖에서 나온 이름이라 단일 호출과 성격이 같다.
 *
 * <p>1차 지표인 지어냄률은 이 문제를 타지 않는다. 판정자가 답하는 질문이 "카카오에 있는가"가
 * 아니라 <b>"그 이름이 그 지역에 실존하는가"</b>이기 때문이다.
 *
 * <h2>예산을 운영값(30초)이 아니라 180초로 둔다</h2>
 *
 * <p>병합 후 실측 최대 지연이 28.0초로 운영 예산 30초에 붙어 있어(STEP-8 판정 2), 운영값으로
 * 재면 일부 요청이 504로 잘리고 <b>그 요청의 장소가 환각률 분모에서 통째로 빠진다.</b> 표본이
 * 사라지는 방향의 오염이라 값이 어느 쪽으로 튈지도 알 수 없다.
 *
 * <p>대신 요청별 소요 시간을 CSV 에 남겨 <b>"운영 예산 30초였다면 몇 건이 잘렸을까"를 사후
 * 역산</b>한다. 한 번의 실행으로 환각률(오염 없음)과 지연 분포(11-2 · {@code ai.course.budget-ms}
 * 재검토의 근거)를 함께 얻는다.
 *
 * <h2>측정 오염 감시</h2>
 *
 * <p>Curator 가 죽어 폴백이 슬롯을 채우면 그 장소는 후보 목록 상위 3(= 네이버·TourAPI 가
 * 실존을 이미 확인한 것)이라 <b>환각률이 구조적으로 0에 가깝다.</b> 즉 파이프라인이 고장날수록
 * 결과가 좋아 보이는 방향으로 오염된다(STEP-7 판정 13). 그래서 요청마다
 * {@code ai.curation.slot} 카운터의 증분을 떠서 CSV 와 리포트에 병기한다.
 *
 * <p><b>장소 단위 귀속은 현 구조상 불가능하다</b> — {@code SlotFillOutcome}은 집계 맵으로만
 * 존재하고 {@link GroundedPlace}에 "폴백이 채웠다"는 표식이 없다. 요청 단위 증분이 현재 구조가
 * 허용하는 최선이다.
 *
 * <h2>실행</h2>
 *
 * <pre>
 * 전체 측정: ./gradlew benchmarkTest --tests '*AiPipelineHallucinationBenchmarkTest*' --rerun
 * 스모크:    PIPELINE_HALLUCINATION_REQUEST_LIMIT=2 ./gradlew ... --rerun
 * 이어서:    PIPELINE_HALLUCINATION_REQUEST_FROM=15 ./gradlew ... --rerun
 * </pre>
 *
 * <p><b>{@code --rerun} 필수</b> — 소스가 안 바뀌면 Gradle 이 UP-TO-DATE 로 판단해 실행하지
 * 않고 성공으로 끝낸다. 30요청이면 LLM 약 120회 · 네이버 540~900 · <b>TourAPI ≤270(일 1,000
 * 한도라 가장 빡빡하다)</b> · 카카오는 파이프라인 600~1,000 에 채점 450~550 이 더해진다.
 */
@Tag("benchmark")
class AiPipelineHallucinationBenchmarkTest {

    /** RPM 방어용 요청 간 지연. baseline·3-7 과 같은 값이다. */
    private static final long DEFAULT_DELAY_MS = 5_000L;

    /** 채점(카카오 검색) 호출 간 지연. 요청당 15~20회가 연달아 나간다. */
    private static final long DEFAULT_SCORING_DELAY_MS = 100L;

    /**
     * 측정용 예산. 운영값이 아니라는 것이 요점이다 — 클래스 javadoc "예산을 운영값이 아니라
     * 180초로 둔다" 참고.
     */
    private static final int BUDGET_MS = 180_000;

    /** 역산 기준이 되는 운영 예산({@code ai.course.budget-ms} 기본값). */
    private static final int PRODUCTION_BUDGET_MS = 30_000;

    /** 연속 이만큼 실패하면 키·쿼터 문제로 보고 멈춘다. 환각률 하네스가 세운 방침이다. */
    private static final int ABORT_AFTER_CONSECUTIVE_FAILURES = 3;

    /**
     * 채점(카카오 검색)이 연속 이만큼 실패하면 멈춘다 — {@code AiHallucinationBaselineTest}의
     * 재채점 모드와 같은 임계값이다.
     *
     * <p><b>이 가드가 없으면 측정이 조용히 무효가 된다.</b> 카카오 키가 만료되거나 쿼터가
     * 소진되면 {@code lookupBestPlace}가 전건 {@code Failed}를 <b>값으로</b> 돌려주므로 예외가
     * 나지 않는다. 하네스는 30요청을 끝까지 완주하고 CSV 도 정상 산출하는데, 모든 장소가
     * {@code KAKAO_ERROR} 라 자동 프록시와 이름 불일치율의 <b>분자가 0</b>이 된다 — 리포트에는
     * "자동 프록시 0.0% / 장소 미확보율 0.0%"로 찍히고, 이건 지어냄률 0.0%라는 실제 결론과
     * 겉모습이 같아 산출물만 보고는 가려낼 수 없다.
     *
     * <p>채점은 LLM 120회를 이미 다 쓴 뒤의 단계이므로, 조기 중단하면 이어 돌릴 때 그 비용을
     * 아낄 수 있다는 이유도 그대로 성립한다.
     */
    private static final int ABORT_AFTER_CONSECUTIVE_KAKAO_ERRORS = 5;

    /** 파이프라인 고유 열. baseline 17열 <b>뒤에</b> 붙는다 — 앞을 건드리면 재채점 호환이 깨진다. */
    private static final String PIPELINE_CSV_HEADER =
        ",source,modifier,slotType,pipelineLat,pipelineLng,pipelinePlaceUrl";

    /** 요청 결말. 파이프라인은 degrade 로 흡수하므로 baseline 의 네 갈래가 성립하지 않는다. */
    private enum Outcome {
        /** 초안을 받았다. 장소가 0개인 경우는 파이프라인이 예외를 올리므로 여기 오지 않는다. */
        OK,
        /** 파이프라인이 예외를 올렸다(전 day 장소 0개 = AI_GROUNDING_FAILED / AI_COURSE_TIMEOUT). */
        FAILED
    }

    /** 채점 결과 + 파이프라인이 그 장소에 대해 알고 있던 것. */
    private record PipelinePlaceRow(PlaceRow scored, GroundedPlace place) {}

    private record RequestOutcome(
        int requestId, String location, RegionTier tier, String keywordSetId,
        Outcome outcome, String failureDetail, long elapsedMs,
        int dayCount, String placesPerDay, int totalPlaces,
        long curationCurator, long curationFallback, long curationUnfilled,
        long droppedAfterCuration
    ) {}

    // ── 측정 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("파이프라인 30요청의 환각률을 baseline 과 같은 채점으로 측정한다")
    void measurePipelineHallucination() throws IOException {
        Map<String, String> dotEnv = loadDotEnv(Path.of(".env"));
        String openAiKey = resolve(dotEnv, "OPENAI_API_KEY");
        String naverId = resolve(dotEnv, "NAVER_CLIENT_ID");
        String naverSecret = resolve(dotEnv, "NAVER_CLIENT_SECRET");
        String tourKey = resolve(dotEnv, "TOUR_API_KEY");
        String kakaoKey = resolve(dotEnv, "KAKAO_API_KEY");
        assumeTrue(openAiKey != null && naverId != null && naverSecret != null && tourKey != null
            && kakaoKey != null, "OpenAI·네이버·TourAPI·카카오 키가 모두 있어야 측정할 수 있다");

        List<RequestSpec> fullInputSet = BaselineInputSet.buildInputSet();
        int from = (int) setting("pipeline.hallucination.requestFrom",
            "PIPELINE_HALLUCINATION_REQUEST_FROM", 1);
        int limit = (int) setting("pipeline.hallucination.requestLimit",
            "PIPELINE_HALLUCINATION_REQUEST_LIMIT", fullInputSet.size());
        int startIndex = Math.min(Math.max(0, from - 1), fullInputSet.size());
        int endIndex = Math.min(startIndex + limit, fullInputSet.size());
        List<RequestSpec> inputSet = fullInputSet.subList(startIndex, endIndex);

        long delayMs = setting("pipeline.hallucination.delayMs",
            "PIPELINE_HALLUCINATION_DELAY_MS", DEFAULT_DELAY_MS);
        long scoringDelayMs = setting("pipeline.hallucination.scoringDelayMs",
            "PIPELINE_HALLUCINATION_SCORING_DELAY_MS", DEFAULT_SCORING_DELAY_MS);

        String runTag = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path rawDir = RESULTS_DIR.resolve("raw-pipeline-" + runTag);
        Files.createDirectories(rawDir);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KakaoLocalClient kakaoClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));

        // 운영 배선을 그대로 쓴다 — 아래 pipeline() javadoc "실행기는 운영 것을 쓴다" 참고.
        AsyncConfig asyncConfig = new AsyncConfig();
        ThreadPoolTaskExecutor agentExecutor = asyncConfig.aiAgentExecutor();
        ThreadPoolTaskExecutor groundingExecutor = asyncConfig.placeGroundingExecutor();

        AiCoursePipeline pipeline = pipeline(registry, kakaoClient, agentExecutor,
            groundingExecutor, openAiKey, naverId, naverSecret, tourKey);
        ObjectMapper draftMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        System.out.printf("%n=== 파이프라인 환각률 측정: 요청 %d~%d (%d건 / 전체 %d건), 여행 %d일 ===%n",
            startIndex + 1, endIndex, inputSet.size(), fullInputSet.size(),
            BaselineInputSet.TRIP_DAYS);
        System.out.printf("    예산 %,dms (운영값 %,dms 가 아니다 — 데드라인으로 표본이 사라지는 것을 막는다)%n",
            BUDGET_MS, PRODUCTION_BUDGET_MS);
        System.out.printf("    요청 간 지연 %,dms · 채점 호출 간 지연 %,dms%n", delayMs, scoringDelayMs);

        List<PipelinePlaceRow> rows = new ArrayList<>();
        List<RequestOutcome> outcomes = new ArrayList<>();

        // try/finally 로 감싸는 이유는 조기 중단 때문이다(3-7 이 세운 방침). 아래 연속 실패 단언은
        // AssertionError 를 던지는데, 그것이 그대로 빠져나가면 그때까지 성공한 요청의 산출물이
        // 함께 사라진다 — 요청당 LLM 4회라 25번째에서 멈추면 약 100회를 다시 태워야 한다.
        try {
            collect(pipeline, kakaoClient, registry, draftMapper, rawDir,
                inputSet, delayMs, scoringDelayMs, rows, outcomes);
        } finally {
            if (rows.isEmpty() && outcomes.isEmpty()) {
                System.out.printf("%n측정된 것이 없어 CSV 를 쓰지 않는다.%n");
            } else {
                writePlaceCsv(runTag, rows);
                writeRequestCsv(runTag, outcomes);
                HallucinationArtifacts.writeManualVerificationCsv(
                    RESULTS_DIR.resolve("manual-verification-pipeline-" + runTag + ".csv"),
                    scoredOf(rows));
                report(rows, outcomes, registry, runTag);
            }
            agentExecutor.shutdown();
            groundingExecutor.shutdown();
        }

        assertThat(rows).as("측정된 장소가 하나도 없다 — 키나 네트워크를 확인하라").isNotEmpty();
    }

    /** 요청 루프. 산출물 기록은 호출자의 {@code finally}가 맡는다. */
    private static void collect(AiCoursePipeline pipeline, KakaoLocalClient kakaoClient,
        SimpleMeterRegistry registry, ObjectMapper draftMapper, Path rawDir,
        List<RequestSpec> inputSet, long delayMs, long scoringDelayMs,
        List<PipelinePlaceRow> rows, List<RequestOutcome> outcomes) throws IOException {

        int consecutiveFailures = 0;
        // 채점 실패는 요청 경계를 넘어 이어진다 — 카카오가 죽으면 다음 요청에서도 계속 실패한다.
        int[] consecutiveKakaoErrors = {0};

        for (RequestSpec spec : inputSet) {
            Map<String, Long> before = curationCounts(registry);
            long startNanos = System.nanoTime();

            AiCourseDraft draft;
            try {
                draft = pipeline.generate(CourseBrief.of(spec.region().name(),
                    BaselineInputSet.TRIP_DAYS, spec.keywordSet().keywords()));
            } catch (RuntimeException e) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                consecutiveFailures++;
                Map<String, Long> delta = delta(before, curationCounts(registry));
                outcomes.add(new RequestOutcome(spec.requestId(), spec.region().name(),
                    spec.region().tier(), spec.keywordSet().id(), Outcome.FAILED,
                    oneLine(e.toString()), elapsedMs, 0, "", 0,
                    delta.getOrDefault("curator", 0L), delta.getOrDefault("fallback", 0L),
                    delta.getOrDefault("unfilled", 0L), 0));

                System.out.printf("  [실패] #%02d %-4s %s : %s (%,dms)%n", spec.requestId(),
                    spec.region().name(), spec.keywordSet().id(), oneLine(e.toString()), elapsedMs);

                assertThat(consecutiveFailures)
                    .as("연속 %d회 실패했다 — 키·쿼터를 확인하고 "
                            + "PIPELINE_HALLUCINATION_REQUEST_FROM=%d 으로 이어 돌려라",
                        consecutiveFailures, spec.requestId())
                    .isLessThan(ABORT_AFTER_CONSECUTIVE_FAILURES);
                sleep(delayMs);
                continue;
            }

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            consecutiveFailures = 0;
            Map<String, Long> delta = delta(before, curationCounts(registry));

            dumpDraft(draftMapper, rawDir, spec, draft);

            int rowsBefore = rows.size();
            score(spec, draft, kakaoClient, scoringDelayMs, rows, consecutiveKakaoErrors);
            int scoredCount = rows.size() - rowsBefore;
            outcomes.add(summarize(spec, draft, scoredCount, elapsedMs, delta));

            System.out.printf("  #%02d %-4s %s : day %d, 장소 %2d개, %,6dms%s%n",
                spec.requestId(), spec.region().name(), spec.keywordSet().id(),
                draft.days().size(), scoredCount, elapsedMs,
                elapsedMs > PRODUCTION_BUDGET_MS ? "  ← 운영 예산 초과" : "");

            sleep(delayMs);
        }
    }

    /**
     * 초안의 장소 전건을 <b>baseline 과 같은 채점기로</b> 판정해 {@code sink}에 더한다.
     *
     * <p>{@code placeIndex}는 day 안에서 0부터 매긴다 — baseline 의 {@code groundPlaces}와 같다.
     * 파이프라인의 리스트 순서가 곧 방문 순서이므로 이 번호는 동선 순서이기도 하다.
     *
     * <p><b>결과를 반환하지 않고 {@code sink}에 바로 넣는 이유</b>: 아래 카카오 연속 실패 단언이
     * {@code AssertionError}를 던지면 그 요청의 채점이 중간에 끊기는데, 지역 리스트에 모았다가
     * 반환하는 구조였다면 그때까지 채점한 것이 통째로 사라진다. 호출자의 {@code try/finally}가
     * 산출물을 남기는 의미가 있으려면 부분 결과도 함께 남아야 한다.
     *
     * @param consecutiveKakaoErrors 요청을 건너뛰며 이어지는 카운터라 배열 홀더로 받는다
     */
    private static void score(RequestSpec spec, AiCourseDraft draft, KakaoLocalClient kakaoClient,
        long scoringDelayMs, List<PipelinePlaceRow> sink, int[] consecutiveKakaoErrors) {

        for (AiCourseDay day : draft.days()) {
            int placeIndex = 0;
            for (AiCoursePlace place : day.places()) {
                GroundedPlace grounded = place.place();
                PlaceRow scored = HallucinationScoring.groundOnePlace(spec, day.day(),
                    placeIndex++, grounded.name(), kakaoClient);
                sink.add(new PipelinePlaceRow(scored, grounded));

                // 키가 죽었거나 쿼터가 끝났으면 남은 장소를 헛돌리지 말고 멈춘다. 상수 javadoc 참고 —
                // 카카오 실패는 예외가 아니라 값이라 이 검사가 없으면 측정이 조용히 무효가 된다.
                consecutiveKakaoErrors[0] = BAND_KAKAO_ERROR.equals(scored.scoreBand())
                    ? consecutiveKakaoErrors[0] + 1 : 0;
                assertThat(consecutiveKakaoErrors[0])
                    .as("카카오 채점이 연속 %d회 실패했다 — 키·쿼터를 확인하고 "
                            + "PIPELINE_HALLUCINATION_REQUEST_FROM=%d 으로 이어 돌려라",
                        consecutiveKakaoErrors[0], spec.requestId())
                    .isLessThan(ABORT_AFTER_CONSECUTIVE_KAKAO_ERRORS);

                sleep(scoringDelayMs);
            }
        }
    }

    private static RequestOutcome summarize(RequestSpec spec, AiCourseDraft draft, int totalPlaces,
        long elapsedMs, Map<String, Long> curation) {

        List<String> perDay = new ArrayList<>();
        for (AiCourseDay day : draft.days()) {
            perDay.add(String.valueOf(day.places().size()));
        }
        long curator = curation.getOrDefault("curator", 0L);
        long fallback = curation.getOrDefault("fallback", 0L);
        long unfilled = curation.getOrDefault("unfilled", 0L);

        // 큐레이션이 채운 슬롯 수와 최종 장소 수의 차 = 그라운딩 이후 사라진 것. 파이프라인은
        // 빈 슬롯을 continue 로 건너뛰며 로그도 메트릭도 남기지 않으므로(STEP-8 판정 2)
        // 이 역산이 아니면 어떤 지표에도 잡히지 않는다.
        long dropped = Math.max(0, curator + fallback - totalPlaces);

        return new RequestOutcome(spec.requestId(), spec.region().name(), spec.region().tier(),
            spec.keywordSet().id(), Outcome.OK, "", elapsedMs,
            draft.days().size(), String.join("/", perDay), totalPlaces,
            curator, fallback, unfilled, dropped);
    }

    // ── 메트릭 증분 ───────────────────────────────────────────────────────────

    /**
     * {@code ai.curation.slot}의 result 태그별 현재 카운터 값.
     *
     * <p>3-7 은 측정이 끝난 뒤 전체 합계만 읽었는데, 여기서는 <b>요청별로 귀속</b>시켜야 한다 —
     * 어느 요청이 폴백을 탔는지 알아야 그 요청의 장소를 오염 후보로 지목할 수 있기 때문이다.
     */
    private static Map<String, Long> curationCounts(SimpleMeterRegistry registry) {
        Map<String, Long> counts = new LinkedHashMap<>();
        registry.getMeters().stream()
            .filter(meter -> AiCourseMetrics.CURATION_SLOT.equals(meter.getId().getName()))
            .forEach(meter -> counts.put(meter.getId().getTag("result"),
                (long) counterValue(meter)));
        return counts;
    }

    private static Map<String, Long> delta(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> result = new LinkedHashMap<>();
        after.forEach((key, value) -> result.put(key, value - before.getOrDefault(key, 0L)));
        return result;
    }

    private static double counterValue(Meter meter) {
        double sum = 0;
        for (Measurement measurement : meter.measure()) {
            sum += measurement.getValue();
        }
        return sum;
    }

    // ── 산출물 ────────────────────────────────────────────────────────────────

    /** 성공한 초안의 원본. 없으면 사후 재분석이 불가능하다 — OpenAI 산출물 소실이 그 사례다. */
    private static void dumpDraft(ObjectMapper mapper, Path rawDir, RequestSpec spec,
        AiCourseDraft draft) throws IOException {

        Files.writeString(rawDir.resolve(String.format("draft-%02d-%s-%s.json",
                spec.requestId(), spec.region().name(), spec.keywordSet().id())),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(draft),
            StandardCharsets.UTF_8);
    }

    /**
     * baseline 의 17열을 그대로 앞에 두고 파이프라인 고유 열을 뒤에 붙인다.
     *
     * <p>그래서 이 CSV 를 {@code BASELINE_RESCORE_FROM} 으로 되먹일 수 있다 — 채점 로직이
     * 바뀌면 LLM 을 한 번도 부르지 않고 같은 장소 목록을 다시 채점할 수 있다는 뜻이다.
     */
    private static void writePlaceCsv(String runTag, List<PipelinePlaceRow> rows)
        throws IOException {

        StringBuilder sb = new StringBuilder();
        sb.append(PLACE_CSV_HEADER).append(PIPELINE_CSV_HEADER).append('\n');
        for (PipelinePlaceRow row : rows) {
            GroundedPlace p = row.place();
            sb.append(placeCsvRow(row.scored())).append(',')
                .append(p.source()).append(',')
                .append(p.matchedModifier() == null ? "" : p.matchedModifier().name()).append(',')
                .append(p.slotType()).append(',')
                .append(String.format("%.7f", p.latitude())).append(',')
                .append(String.format("%.7f", p.longitude())).append(',')
                .append(csv(p.placeUrl())).append('\n');
        }
        writeUtf8Bom(RESULTS_DIR.resolve("hallucination-pipeline-" + runTag + ".csv"),
            sb.toString());
    }

    private static void writeRequestCsv(String runTag, List<RequestOutcome> outcomes)
        throws IOException {

        StringBuilder sb = new StringBuilder();
        sb.append("requestId,location,regionTier,keywordSet,outcome,failureDetail,elapsedMs,")
            .append("dayCount,placesPerDay,totalPlaces,")
            .append("curationCurator,curationFallback,curationUnfilled,droppedAfterCuration\n");

        for (RequestOutcome o : outcomes) {
            sb.append(o.requestId()).append(',')
                .append(csv(o.location())).append(',')
                .append(o.tier()).append(',')
                .append(csv(o.keywordSetId())).append(',')
                .append(o.outcome()).append(',')
                .append(csv(o.failureDetail())).append(',')
                .append(o.elapsedMs()).append(',')
                .append(o.dayCount()).append(',')
                .append(csv(o.placesPerDay())).append(',')
                .append(o.totalPlaces()).append(',')
                .append(o.curationCurator()).append(',')
                .append(o.curationFallback()).append(',')
                .append(o.curationUnfilled()).append(',')
                .append(o.droppedAfterCuration()).append('\n');
        }
        writeUtf8Bom(RESULTS_DIR.resolve("hallucination-pipeline-" + runTag + "-requests.csv"),
            sb.toString());
    }

    // ── 리포트 ────────────────────────────────────────────────────────────────

    private static void report(List<PipelinePlaceRow> rows, List<RequestOutcome> outcomes,
        SimpleMeterRegistry registry, String runTag) {

        // 장소 지표는 baseline 과 같은 함수로 찍는다 — 같은 문구여야 두 산출물을 나란히 읽는다.
        HallucinationReport.printPlaceMetrics(scoredOf(rows));

        reportBySource(rows);
        reportContamination(outcomes, registry);
        reportLatency(outcomes);

        System.out.printf("%n=== 산출물 ===%n");
        System.out.printf("  초안 원본       results/raw-pipeline-%s/%n", runTag);
        System.out.printf("  장소별 채점     results/hallucination-pipeline-%s.csv%n", runTag);
        System.out.printf("  요청별 지표     results/hallucination-pipeline-%s-requests.csv%n",
            runTag);
        System.out.printf("  수동 검증 대상  results/manual-verification-pipeline-%s.csv"
            + "  ← verdict 를 채워주세요%n", runTag);
        HallucinationReport.printManualMetricFormulas();
    }

    /**
     * 출처별 분해. baseline 에 없던 축이고, 운영 메트릭
     * {@code ai.grounding.match{source}}와 같은 축이라 배포 후 데이터와 대조할 수 있다.
     */
    private static void reportBySource(List<PipelinePlaceRow> rows) {
        System.out.printf("%n=== 출처별 자동 프록시 (파이프라인 고유 축) ===%n");
        Map<String, List<PipelinePlaceRow>> bySource = new TreeMap<>();
        for (PipelinePlaceRow row : rows) {
            bySource.computeIfAbsent(row.place().source().name(), key -> new ArrayList<>()).add(row);
        }
        System.out.printf("  %-10s %6s %10s %10s%n", "source", "장소", "NO_RESULT", "NAME_MISM");
        bySource.forEach((source, group) -> {
            long noResult = group.stream().filter(r ->
                HallucinationScoring.BAND_NO_RESULT.equals(r.scored().scoreBand())).count();
            long mismatch = group.stream().filter(r ->
                HallucinationScoring.BAND_NAME_MISMATCH.equals(r.scored().scoreBand())).count();
            System.out.printf("  %-10s %6d %6d(%4.1f%%) %6d(%4.1f%%)%n", source, group.size(),
                noResult, pct(noResult, group.size()), mismatch, pct(mismatch, group.size()));
        });
        System.out.printf("  ※ SEEDED·LISTED 의 NO_RESULT 는 환각이 아니라 카카오 커버리지 구멍이다 —%n");
        System.out.printf("     그 후보는 카카오를 거치지 않고 네이버·TourAPI 응답을 승계해 실좌표와 함께%n");
        System.out.printf("     코스에 실린다(GroundingStage.resolve 의 inherit 분기, 호출 0회·무조건 HIT).%n");
        System.out.printf("     baseline 에서 NO_RESULT 는 좌표 없음이었지만 여기서는 카카오 링크만 없다.%n");
        System.out.printf("  ※ baseline 과 직접 비교할 수 있는 것은 SUGGESTED 뿐이다 — 목록 밖에서 나온%n");
        System.out.printf("     이름이라 단일 호출과 성격이 같다. 나머지는 후보 공급의 덮개를 재는 값이다.%n");
    }

    /** 폴백이 채운 슬롯과 빈 슬롯. 클래스 javadoc "측정 오염 감시" 참고. */
    private static void reportContamination(List<RequestOutcome> outcomes,
        SimpleMeterRegistry registry) {

        long curator = outcomes.stream().mapToLong(RequestOutcome::curationCurator).sum();
        long fallback = outcomes.stream().mapToLong(RequestOutcome::curationFallback).sum();
        long unfilled = outcomes.stream().mapToLong(RequestOutcome::curationUnfilled).sum();
        long dropped = outcomes.stream().mapToLong(RequestOutcome::droppedAfterCuration).sum();
        long slots = curator + fallback + unfilled;

        System.out.printf("%n=== 측정 오염 확인 (%s) ===%n", AiCourseMetrics.CURATION_SLOT);
        System.out.printf("  curator   %4d / %4d 슬롯 (%5.1f%%)%n", curator, slots,
            pct(curator, slots));
        System.out.printf("  fallback  %4d / %4d 슬롯 (%5.1f%%)  ← 0이 아니면 결과 문서에 반드시 적는다%n",
            fallback, slots, pct(fallback, slots));
        System.out.printf("  unfilled  %4d / %4d 슬롯 (%5.1f%%)%n", unfilled, slots,
            pct(unfilled, slots));
        System.out.printf("  큐레이션 후 사라진 장소 %d개 — 그라운딩 탈락·최적화 드롭의 합(역산)%n",
            dropped);

        List<RequestOutcome> tainted = outcomes.stream()
            .filter(o -> o.curationFallback() > 0).toList();
        if (!tainted.isEmpty()) {
            System.out.printf("  [폴백이 낀 요청 %d건]%n", tainted.size());
            tainted.forEach(o -> System.out.printf("    #%02d %-4s %s : fallback %d%n",
                o.requestId(), o.location(), o.keywordSetId(), o.curationFallback()));
        }

        System.out.printf("%n  [그라운딩 결과 %s]%n", AiCourseMetrics.GROUNDING_MATCH);
        registry.getMeters().stream()
            .filter(meter -> AiCourseMetrics.GROUNDING_MATCH.equals(meter.getId().getName()))
            .filter(meter -> counterValue(meter) > 0)
            .forEach(meter -> System.out.printf("    result=%-16s source=%-10s %.0f%n",
                meter.getId().getTag("result"), meter.getId().getTag("source"),
                counterValue(meter)));
        System.out.printf("    ※ 위 하네스 채점과 어긋나는 폭이 운영 프록시(5-6)의 유효성을 말한다.%n");
    }

    /** 지연 분포와 운영 예산 역산. 남은 선행 조건({@code ai.course.budget-ms} 재검토)의 근거다. */
    private static void reportLatency(List<RequestOutcome> outcomes) {
        List<Long> elapsed = outcomes.stream()
            .filter(o -> o.outcome() == Outcome.OK)
            .map(RequestOutcome::elapsedMs)
            .sorted()
            .toList();
        if (elapsed.isEmpty()) {
            return;
        }

        System.out.printf("%n=== 지연 분포 (성공 %d건, 측정 예산 %,dms) ===%n",
            elapsed.size(), BUDGET_MS);
        System.out.printf("  p50 %,dms · p95 %,dms · max %,dms · min %,dms%n",
            percentile(elapsed, 50), percentile(elapsed, 95),
            elapsed.get(elapsed.size() - 1), elapsed.get(0));

        long over = elapsed.stream().filter(ms -> ms > PRODUCTION_BUDGET_MS).count();
        System.out.printf("  운영 예산 %,dms 초과 %d / %d건 (%.1f%%)"
                + "  ← 운영값으로 쟀다면 504 로 잘렸을 요청%n",
            PRODUCTION_BUDGET_MS, over, elapsed.size(), pct(over, elapsed.size()));
        System.out.printf("  ※ 이 역산이 11-2(202 Accepted 전환)와 ai.course.budget-ms 재검토의 근거다.%n");
    }

    /** 최근접 순위법. 표본이 30건이라 보간을 쓰면 없는 정밀도를 꾸미게 된다. */
    private static long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.min(Math.max(index, 0), sorted.size() - 1));
    }

    private static List<PlaceRow> scoredOf(List<PipelinePlaceRow> rows) {
        return rows.stream().map(PipelinePlaceRow::scored).toList();
    }

    // ── 배선 — Spring 컨텍스트를 쓰지 않고 손으로 조립한다 (3-7 프로브와 같다) ──

    /**
     * {@code @SpringBootTest} 부분 컨텍스트를 쓰지 않는 이유: {@code test} 프로필은 {@code .env}
     * 없이 자급하도록 설계돼 API 키가 전부 더미이고(CI 가 시크릿 없이 서 있는 전제), 컨텍스트를
     * 띄우면 H2·Redis·JPA 까지 따라와 측정 대상과 무관한 것이 붙는다. 손조립 선례가
     * {@code AiCourseRouteInputProbeTest}·{@code AiCourseDayShapeProbeTest} 둘 있다.
     *
     * <p><b>카카오 클라이언트는 밖에서 만들어 넣는다</b> — 파이프라인과 채점기가 <b>같은 인스턴스</b>를
     * 써야 커넥션 풀과 타임아웃 조건이 같아진다.
     *
     * <h3>실행기는 운영 것을 쓴다 — 다른 프로브와 갈리는 지점</h3>
     *
     * <p>3-7·day-shape 프로브는 {@code Executor} 자리에 {@code Runnable::run}(동기 실행)을 넣는다.
     * 그쪽이 재는 것은 동선과 day 모양이라 실행 방식이 결과를 바꾸지 않기 때문이다.
     *
     * <p><b>여기서 그렇게 하면 지연 측정이 무효가 된다.</b> 파이프라인의 병렬 지점(day별 Curator,
     * 슬롯별 후보 공급, 후보별 그라운딩)이 전부 순차로 퇴화해 운영보다 구조적으로 느려진다 —
     * 스모크에서 41.9초 / 38.0초가 나왔는데 E2E 실측(운영 배선)은 22.6초 / 28.0초였다.
     * 8-6 이 지연 재측정과 {@code ai.course.budget-ms} 재검토를 겸하므로 그 값으로는 판단할 수 없다.
     *
     * <p>그래서 {@link AsyncConfig}를 직접 인스턴스화해 <b>운영과 같은 풀 설정</b>(aiAgent core 4 /
     * placeGrounding core 8, 둘 다 {@code CallerRunsPolicy})을 쓴다. Spring 컨텍스트 없이도 그
     * 메서드는 평범한 팩토리라 그대로 부를 수 있고, 설정이 바뀌면 하네스가 자동으로 따라간다 —
     * 값을 복사하면 운영만 바뀌고 벤치마크는 조용히 구식이 된다.
     *
     * <p>환각률 자체에는 영향이 없다. 실행 순서가 달라져도 같은 후보에서 같은 선별이 일어난다.
     */
    private static AiCoursePipeline pipeline(SimpleMeterRegistry registry,
        KakaoLocalClient kakaoClient, ThreadPoolTaskExecutor agentExecutor,
        ThreadPoolTaskExecutor groundingExecutor, String openAiKey, String naverId,
        String naverSecret, String tourKey) {

        AiCourseMetrics metrics = new AiCourseMetrics(registry);
        AiLlmProperties properties = benchmarkProperties(openAiKey);
        OpenAiLlmClient llmClient = new OpenAiLlmClient(properties,
            new LlmResponseParser(new ObjectMapper()), new LlmRetryExecutor(properties), metrics,
            OpenAiLlmClient.buildChatModel(properties.openai().baseUrl(), openAiKey,
                properties.timeoutMs()));

        PromptLoader promptLoader = new PromptLoader();
        NaverLocalClient naverClient = new NaverLocalClient(NaverConfig.buildNaverWebClient(
            "https://naverapihub.apigw.ntruss.com", naverId, naverSecret));
        TourApiClient tourClient = new TourApiClient(TourApiConfig.buildTourApiWebClient(
            "https://apis.data.go.kr/B551011/KorService2"), tourKey);

        return new AiCoursePipeline(
            new PlannerAgent(llmClient, promptLoader, agentExecutor),
            new CandidateRetrievalStage(new AreaGeocoder(kakaoClient),
                new NaverLocalSeedSource(naverClient, metrics), new TourApiSource(tourClient),
                metrics, groundingExecutor),
            new CuratorAgent(llmClient, promptLoader, metrics, agentExecutor),
            new GroundingStage(kakaoClient, metrics, groundingExecutor),
            new RouteOptimizer(),
            new PlaceUrlEnricher(kakaoClient, metrics, groundingExecutor),
            metrics,
            new AiCourseProperties(BUDGET_MS));
    }

    /** 운영 설정({@code application.yml})과 같은 모델·추론 강도를 쓴다 — 3-7 과 같은 값이다. */
    private static AiLlmProperties benchmarkProperties(String apiKey) {
        return new AiLlmProperties(
            "openai",
            60_000,
            2,
            new AiLlmProperties.Retry(3, 2, 0.5, 4.0, 0.3),
            Map.of(
                PlannerAgent.AGENT_NAME,
                new AiLlmProperties.Agent("gpt-5.6-luna", null, 2048, null),
                CuratorAgent.AGENT_NAME,
                new AiLlmProperties.Agent("gpt-5.6-luna", null, 4096, "low")),
            new AiLlmProperties.OpenAi(apiKey, "https://api.openai.com"));
    }
}
