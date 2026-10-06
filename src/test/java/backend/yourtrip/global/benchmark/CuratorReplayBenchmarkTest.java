package backend.yourtrip.global.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.LlmCall;
import backend.yourtrip.global.ai.LlmResponseParser;
import backend.yourtrip.global.ai.LlmRetryExecutor;
import backend.yourtrip.global.ai.agent.CandidateListRenderer;
import backend.yourtrip.global.ai.agent.CuratedChoiceValidator;
import backend.yourtrip.global.ai.agent.CuratedChoiceValidator.CurationOutcome;
import backend.yourtrip.global.ai.agent.CuratorAgent;
import backend.yourtrip.global.ai.agent.PlannerAgent;
import backend.yourtrip.global.ai.agent.dto.CuratorResponse;
import backend.yourtrip.global.ai.candidate.AreaGeocoder;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateRetrievalStage;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.NaverLocalSeedSource;
import backend.yourtrip.global.ai.candidate.PlaceNameNormalizer;
import backend.yourtrip.global.ai.candidate.TourApiSource;
import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.openai.OpenAiLlmClient;
import backend.yourtrip.global.ai.pipeline.CuratedPlace;
import backend.yourtrip.global.ai.pipeline.CuratedSlot;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.prompt.KeywordRenderer;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.prompt.PromptTemplate;
import backend.yourtrip.global.ai.prompt.ResponseSchema;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.config.NaverConfig;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.config.TourApiConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Curator 만 <b>고정 입력으로 재생</b>해 추론 강도·응답 스키마의 효과를 재는 하네스 (#194).
 *
 * <h2>왜 입력을 고정하는가</h2>
 * 파이프라인을 통째로 돌리면 조건마다 Planner 출력과 후보 목록이 달라진다. #182 의 전후 비교는
 * 측정 도중 TourAPI 일일 한도가 소진돼 Curator 입력이 바뀌는 바람에 폴백 비교가 무효가 됐다
 * (STEP-4-3). 이 하네스는 <b>Planner·후보 공급을 한 번만 돌려 Curator 입력을 파일로 얼리고</b>,
 * 조건별로는 Curator 만 다시 부른다. 바뀌는 것은 조건 하나뿐이고, 네이버·TourAPI 쿼터도 쓰지 않는다.
 *
 * <h2>두 테스트로 나눈 이유</h2>
 * <ol>
 *   <li>{@link #capture()} — 입력 수집. Planner·후보 공급 실호출(LLM 은 Planner 만)</li>
 *   <li>{@link #replay()} — 재생. Curator 만 실호출</li>
 * </ol>
 * 같은 입력 파일을 여러 단계(추론 강도 → 스키마)와 여러 날에 다시 쓸 수 있어야 한다.
 *
 * <h2>측정 설계에서 지킨 것</h2>
 * <ul>
 *   <li><b>조건을 교차 배치한다.</b> 같은 입력에 대해 조건들을 연달아 부르고, 반복마다 순서를 돌린다.
 *       시간대에 따라 LLM 이 약 20% 느려지는 흔들림(STEP-6)이 한 조건에만 몰리지 않게 하기 위해서다</li>
 *   <li><b>프롬프트 캐시가 공평하게 걸리게 한다.</b> 같은 프롬프트를 반복하므로 두 번째부터 캐시가
 *       적중한다. 순서를 고정하면 맨 앞 조건만 늘 콜드라 불리해진다 — 순서를 돌리는 또 하나의 이유다.
 *       캐시 적중 토큰은 호출마다 기록한다</li>
 *   <li><b>순차 호출이다.</b> 슬롯 대기가 섞이지 않아 호출 시간이 곧 LLM 응답 시간이고, 토큰 지표의
 *       전후 차이가 그 호출 하나의 값이 된다</li>
 *   <li><b>품질은 "자기 자신과의 일치율"을 기준선으로 본다.</b> 같은 조건을 반복해도 선택은 흔들린다.
 *       그래서 조건 간 1순위 일치율을 같은 조건 반복 간 일치율과 나란히 놓는다</li>
 * </ul>
 *
 * <pre>{@code
 * ./gradlew benchmarkTest --tests '*CuratorReplayBenchmarkTest.capture' --rerun
 * CURATOR_REPLAY_CONDITIONS=legacy:low,compact:low CURATOR_REPLAY_REPEATS=3 \
 *     ./gradlew benchmarkTest --tests '*CuratorReplayBenchmarkTest.replay' --rerun
 * }</pre>
 * benchmarkTest 태스크는 {@code -D} 시스템 프로퍼티를 테스트 JVM 에 넘기지 않으므로 환경변수로 준다.
 */
@Tag("benchmark")
@DisplayName("Curator 고정 입력 재생 벤치마크 (#194)")
class CuratorReplayBenchmarkTest {

    private static final Path OUTPUT_DIR = HallucinationArtifacts.RESULTS_DIR.resolve("curator-replay");
    private static final Path INPUT_FILE = OUTPUT_DIR.resolve("inputs.json");

    private static final String MODEL = "gpt-5.6-luna";
    /** 운영 설정과 같은 값. 다르게 두면 잘림 여부가 운영과 달라진다. */
    private static final int MAX_OUTPUT_TOKENS = 2048;
    private static final int DAYS = 3;
    /** 이만큼 연속 실패하면 외부 장애로 보고 측정을 멈춘다. 첫 측정에서 로컬 네트워크가 바뀌며 36회가 연달아 실패했다. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * 기준선 입력 세트({@link BaselineInputSet})에서 성격이 다른 셋을 골랐다 — 유명 지역, 후보 목록이
     * 긴 대도시, 덜 알려진 지역. 키워드 세트도 셋 다 다르게 둬 프롬프트 다양성을 확보한다.
     */
    private static final List<InputSpec> INPUT_SPECS = List.of(
        new InputSpec("경주", "A"),
        new InputSpec("부산", "C"),
        new InputSpec("공주", "B"));

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // record 의 isEmpty() 가 "empty" 속성으로 직렬화된다. 읽을 때는 무시한다.
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // ── ① 입력 수집 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Planner·후보 공급을 한 번 돌려 Curator 입력을 파일로 고정한다")
    void capture() throws IOException {
        Map<String, String> dotEnv = BenchmarkEnv.loadDotEnv(Path.of(".env"));
        String openAiKey = BenchmarkEnv.resolve(dotEnv, "OPENAI_API_KEY");
        String naverId = BenchmarkEnv.resolve(dotEnv, "NAVER_CLIENT_ID");
        String naverSecret = BenchmarkEnv.resolve(dotEnv, "NAVER_CLIENT_SECRET");
        String tourKey = BenchmarkEnv.resolve(dotEnv, "TOUR_API_KEY");
        String kakaoKey = BenchmarkEnv.resolve(dotEnv, "KAKAO_API_KEY");
        assumeTrue(openAiKey != null && naverId != null && naverSecret != null && tourKey != null
            && kakaoKey != null, "OpenAI·네이버·TourAPI·카카오 키가 모두 있어야 수집할 수 있다");

        AiCourseMetrics metrics = new AiCourseMetrics(new SimpleMeterRegistry());
        OpenAiLlmClient llmClient = llmClient(openAiKey, "low", metrics);
        PromptLoader promptLoader = new PromptLoader();
        PlannerAgent planner = new PlannerAgent(llmClient, promptLoader, Runnable::run);
        CandidateRetrievalStage retrieval = retrievalStage(naverId, naverSecret, tourKey, kakaoKey,
            metrics);

        List<CapturedDay> captured = new ArrayList<>();
        for (InputSpec spec : INPUT_SPECS) {
            List<KeywordType> keywords = spec.keywords();
            CourseDeadline deadline = CourseDeadline.unbounded();
            PlannerPlan plan = planner.plan(spec.region(), DAYS, keywords, deadline);
            CandidatePool pool = retrieval.retrieve(spec.region(), plan, keywords, deadline);

            for (PlannerDayPlan day : plan.days()) {
                // 운영 CuratorAgent.buildCall 과 같은 렌더링이다. 여기서 만든 문자열을 그대로 얼린다.
                String userPrompt = promptLoader.render(PromptTemplate.CURATOR_USER, Map.of(
                    "day", String.valueOf(day.day()),
                    "area", day.area(),
                    "theme", day.theme() == null ? plan.concept() : day.theme(),
                    "concept", plan.concept(),
                    "keywords", KeywordRenderer.render(keywords),
                    "slots", CandidateListRenderer.renderSlots(day),
                    "candidates", CandidateListRenderer.renderCandidates(day, pool)));
                CandidatePool dayPool = new CandidatePool(pool.slots().stream()
                    .filter(slot -> slot.day() == day.day()).toList());
                captured.add(new CapturedDay(spec.region(), spec.keywordSetId(), day, dayPool,
                    userPrompt));
                System.out.printf("[수집] %s day %d — area=%s, 자리=%s, 후보=%s%n", spec.region(),
                    day.day(), day.area(), day.slots(), dayPool.candidateCountByDay());
            }
        }

        Files.createDirectories(OUTPUT_DIR);
        mapper.writeValue(INPUT_FILE.toFile(), new CapturedInputs(LocalDateTime.now(), captured));
        System.out.printf("%n[수집] %d개 day 입력을 %s 에 저장했다%n", captured.size(),
            INPUT_FILE.toAbsolutePath());
        assertThat(captured).hasSize(INPUT_SPECS.size() * DAYS);
    }

    // ── ② 재생 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("고정 입력으로 Curator 를 조건별로 재생해 시간·토큰·품질을 비교한다")
    void replay() throws IOException {
        assumeTrue(Files.exists(INPUT_FILE), "먼저 capture() 로 입력을 수집해야 한다: " + INPUT_FILE);
        Map<String, String> dotEnv = BenchmarkEnv.loadDotEnv(Path.of(".env"));
        String openAiKey = BenchmarkEnv.resolve(dotEnv, "OPENAI_API_KEY");
        assumeTrue(openAiKey != null, "OPENAI_API_KEY 가 있어야 재생할 수 있다");

        CapturedInputs inputs = mapper.readValue(INPUT_FILE.toFile(), CapturedInputs.class);
        // 조건 = "스키마:추론강도". legacy 는 #194 이전 응답 스키마, compact 는 현행이다.
        List<String> conditions = List.of(BenchmarkEnv.text("curator.replay.conditions",
            "CURATOR_REPLAY_CONDITIONS", "legacy:low,compact:low").split(","));
        int repeats = (int) BenchmarkEnv.setting("curator.replay.repeats",
            "CURATOR_REPLAY_REPEATS", 3);

        PromptLoader promptLoader = new PromptLoader();

        // 조건마다 클라이언트와 레지스트리를 따로 둔다 — 토큰 지표의 전후 차이를 조건별로 떼어 읽기 위해서다.
        Map<String, Runner> runners = new LinkedHashMap<>();
        for (String condition : conditions) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            String[] parts = condition.trim().split(":");
            boolean legacy = parts[0].equals("legacy");
            runners.put(condition, new Runner(condition, legacy,
                legacy ? classpathText("curator-legacy/curator-system.md")
                    : promptLoader.render(PromptTemplate.CURATOR_SYSTEM, Map.of()),
                legacy ? classpathText("curator-legacy/curator-response.schema.json")
                    : promptLoader.schema(ResponseSchema.CURATOR),
                llmClient(openAiKey, parts[1], new AiCourseMetrics(registry)), registry));
        }

        System.out.printf("[재생] 입력 %d개(수집 %s) × 조건 %s × 반복 %d = Curator %d회%n",
            inputs.days().size(), inputs.capturedAt(), conditions, repeats,
            inputs.days().size() * conditions.size() * repeats);

        List<CallRecord> records = new ArrayList<>();
        int consecutiveFailures = 0;
        measure:
        for (int repeat = 1; repeat <= repeats; repeat++) {
            for (int dayIndex = 0; dayIndex < inputs.days().size(); dayIndex++) {
                CapturedDay day = inputs.days().get(dayIndex);
                // 반복·입력마다 조건 순서를 돌린다. 캐시 적중과 시간대 흔들림을 조건 사이에 고르게 나눈다.
                int offset = (repeat + dayIndex) % conditions.size();
                for (int k = 0; k < conditions.size(); k++) {
                    Runner runner = runners.get(conditions.get((offset + k) % conditions.size()));
                    CallRecord record = runner.call(dayIndex, repeat, day);
                    records.add(record);
                    System.out.printf("  r%d %-11s %s d%d %-5s %6dms out=%4d(reason %4d) in=%5d(cache %5d) %s%n",
                        repeat, runner.condition(), day.region(), day.plan().day(), record.outcome(),
                        record.elapsedMs(), record.outputTokens(), record.reasoningTokens(),
                        record.inputTokens(), record.cachedTokens(),
                        record.error().isEmpty() ? record.demotions() : record.error());
                    consecutiveFailures = record.outcome().equals("success") ? 0 : consecutiveFailures + 1;
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        // 조건과 무관한 외부 장애다. 남은 호출을 재시도로 소진해 봐야 무효 데이터만 쌓인다.
                        System.out.printf("%n[재생] 실패가 %d회 연속됐다 — 측정을 중단한다. 이 실행은 무효로 본다%n",
                            consecutiveFailures);
                        break measure;
                    }
                }
            }
        }

        Path runDir = OUTPUT_DIR.resolve(
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(runDir);
        writeCsv(runDir.resolve("calls.csv"), records);
        printSummary(conditions, records);
        printAgreement(conditions, repeats, records);
        System.out.printf("%n[재생] 호출별 기록: %s%n", runDir.resolve("calls.csv").toAbsolutePath());
    }

    /**
     * Curator 가 받는 추론 강도 값이 실제 API 에서 받아들여지는지 한 번씩만 확인한다.
     * 설정 주석의 "luna 는 none/low/..., nano 는 minimal/low" 기록을 실호출로 다시 본다.
     */
    @Test
    @DisplayName("추론 강도 값별로 Curator 호출이 받아들여지는지 확인한다")
    void effortSupport() throws IOException {
        assumeTrue(Files.exists(INPUT_FILE), "먼저 capture() 로 입력을 수집해야 한다: " + INPUT_FILE);
        Map<String, String> dotEnv = BenchmarkEnv.loadDotEnv(Path.of(".env"));
        String openAiKey = BenchmarkEnv.resolve(dotEnv, "OPENAI_API_KEY");
        assumeTrue(openAiKey != null, "OPENAI_API_KEY 가 있어야 확인할 수 있다");

        CapturedInputs inputs = mapper.readValue(INPUT_FILE.toFile(), CapturedInputs.class);
        PromptLoader promptLoader = new PromptLoader();
        CapturedDay day = inputs.days().get(0);
        for (String effort : List.of("minimal", "none")) {
            OpenAiLlmClient client = llmClient(openAiKey, effort,
                new AiCourseMetrics(new SimpleMeterRegistry()));
            try {
                client.generate(new LlmCall<>(CuratorAgent.AGENT_NAME,
                    promptLoader.render(PromptTemplate.CURATOR_SYSTEM, Map.of()), day.userPrompt(),
                    CuratorResponse.class, promptLoader.schema(ResponseSchema.CURATOR)));
                System.out.printf("[추론 강도] %-8s 받아들여졌다%n", effort);
            } catch (RuntimeException e) {
                System.out.printf("[추론 강도] %-8s 거부됐다: %s%n", effort, rootMessage(e));
            }
        }
    }

    /**
     * OpenAI 가 TPM 을 <b>출력 상한(max_completion_tokens)만큼 미리 잡는지</b>, 실제 출력만큼만 쓰는지를
     * 응답 헤더로 가른다(#194 5-1). 슬롯 상한·출력 상한 계산이 모두 "미리 잡는다"는 공식 문서 기술에
     * 기대고 있는데, 이 계정에서 확인한 적은 없다.
     *
     * <p>방법 — 같은 입력에 출력 상한만 4,096 / 1,024 로 바꿔 순차로 부르고, 응답의
     * {@code x-ratelimit-remaining-tokens}를 본다. 호출 사이에 쉬어 버킷(초당 약 8.3K 회복)이 가득 찬 상태에서
     * 부르면 {@code limit − remaining}이 곧 이 요청 하나가 잡힌 양이다. 미리 잡는다면 두 조건의 차이가
     * 약 3,072, 실제 출력만 센다면 차이가 0 근처다.
     *
     * <p>어댑터(Spring AI)는 응답 헤더를 드러내지 않으므로 이 측정만 HTTP 를 직접 부른다. 요청 본문은
     * 어댑터가 보내는 것과 같은 필드(모델·스키마 strict·추론 강도 low)로 맞춘다.
     */
    @Test
    @DisplayName("TPM 이 출력 상한만큼 미리 잡히는지 응답 헤더로 확인한다")
    void rateLimitAccounting() throws Exception {
        assumeTrue(Files.exists(INPUT_FILE), "먼저 capture() 로 입력을 수집해야 한다: " + INPUT_FILE);
        Map<String, String> dotEnv = BenchmarkEnv.loadDotEnv(Path.of(".env"));
        String openAiKey = BenchmarkEnv.resolve(dotEnv, "OPENAI_API_KEY");
        assumeTrue(openAiKey != null, "OPENAI_API_KEY 가 있어야 확인할 수 있다");

        CapturedInputs inputs = mapper.readValue(INPUT_FILE.toFile(), CapturedInputs.class);
        CapturedDay day = inputs.days().get(0);
        PromptLoader promptLoader = new PromptLoader();
        String systemPrompt = promptLoader.render(PromptTemplate.CURATOR_SYSTEM, Map.of());
        Object schema = mapper.readValue(promptLoader.schema(ResponseSchema.CURATOR), Object.class);

        List<Integer> caps = List.of(4096, 1024, 4096, 1024, 4096, 1024);
        long pauseMs = (long) BenchmarkEnv.setting("curator.ratelimit.pause-ms",
            "CURATOR_RATELIMIT_PAUSE_MS", 15_000);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        List<String> rows = new ArrayList<>();
        rows.add("seq,max_completion_tokens,status,limit_tokens,remaining_tokens,held_tokens,"
            + "reset_tokens,prompt_tokens,completion_tokens,reasoning_tokens,elapsed_ms");
        System.out.printf("[TPM] 입력 %s d%d, 호출 사이 %dms 휴식%n", day.region(), day.plan().day(), pauseMs);
        for (int seq = 1; seq <= caps.size(); seq++) {
            // 첫 호출 전에도 쉰다 — 직전에 돌린 다른 측정이 버킷을 비워 뒀을 수 있다.
            Thread.sleep(pauseMs);
            int cap = caps.get(seq - 1);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", MODEL);
            body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", day.userPrompt())));
            body.put("max_completion_tokens", cap);
            body.put("reasoning_effort", "low");
            body.put("response_format", Map.of("type", "json_schema", "json_schema",
                Map.of("name", "yourtrip_curator", "strict", true, "schema", schema)));

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + openAiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            long started = System.nanoTime();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            long limit = headerLong(response, "x-ratelimit-limit-tokens");
            long remaining = headerLong(response, "x-ratelimit-remaining-tokens");
            String reset = response.headers().firstValue("x-ratelimit-reset-tokens").orElse("");
            var usage = response.statusCode() == 200
                ? mapper.readTree(response.body()).path("usage") : mapper.createObjectNode();
            long prompt = usage.path("prompt_tokens").asLong(-1);
            long completion = usage.path("completion_tokens").asLong(-1);
            long reasoning = usage.path("completion_tokens_details").path("reasoning_tokens").asLong(-1);
            long held = limit < 0 || remaining < 0 ? -1 : limit - remaining;

            rows.add(String.join(",", String.valueOf(seq), String.valueOf(cap),
                String.valueOf(response.statusCode()), String.valueOf(limit), String.valueOf(remaining),
                String.valueOf(held), quote(reset), String.valueOf(prompt), String.valueOf(completion),
                String.valueOf(reasoning), String.valueOf(elapsedMs)));
            System.out.printf("  #%d cap=%4d status=%d limit=%d remaining=%d held=%6d "
                    + "prompt=%5d out=%4d(reason %4d) %6dms%n",
                seq, cap, response.statusCode(), limit, remaining, held, prompt, completion,
                reasoning, elapsedMs);
            if (response.statusCode() != 200) {
                // 본문에 키가 실리지는 않지만, 길이만 잘라 원인만 남긴다.
                String bodyText = response.body();
                System.out.printf("    오류 본문: %s%n",
                    bodyText.length() > 300 ? bodyText.substring(0, 300) + "…" : bodyText);
            }
        }

        Path runDir = OUTPUT_DIR.resolve("ratelimit-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(runDir);
        Files.write(runDir.resolve("calls.csv"), rows, StandardCharsets.UTF_8);
        System.out.printf("%n[TPM] 호출별 기록: %s%n", runDir.resolve("calls.csv").toAbsolutePath());
    }

    private static long headerLong(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).map(Long::parseLong).orElse(-1L);
    }

    // ── 실행 단위 ────────────────────────────────────────────────────────────

    private record Runner(String condition, boolean legacy, String systemPrompt, String schema,
                          OpenAiLlmClient client, SimpleMeterRegistry registry) {

        CallRecord call(int dayIndex, int repeat, CapturedDay day) {
            TokenSnapshot before = TokenSnapshot.of(registry);
            long started = System.nanoTime();
            CuratorResponse response = null;
            Map<String, Integer> legacyChecks = new TreeMap<>();
            String outcome = "success";
            String error = "";
            try {
                if (legacy) {
                    LegacyCuratorResponse raw = client.generate(new LlmCall<>(
                        CuratorAgent.AGENT_NAME, systemPrompt, day.userPrompt(),
                        LegacyCuratorResponse.class, schema));
                    response = raw.toCurrent(day, legacyChecks);
                } else {
                    response = client.generate(new LlmCall<>(CuratorAgent.AGENT_NAME,
                        systemPrompt, day.userPrompt(), CuratorResponse.class, schema));
                }
            } catch (RuntimeException e) {
                outcome = e.getClass().getSimpleName();
                // 클래스 이름만 남기면 네트워크 장애와 API 거부를 가를 수 없다(첫 측정에서 겪었다).
                error = rootMessage(e);
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            TokenSnapshot delta = TokenSnapshot.of(registry).minus(before);

            // 운영과 같은 검증기를 태운다 — 강등 사유가 그대로 품질 지표가 된다.
            CurationOutcome curation = CuratedChoiceValidator.validate(day.plan(), day.pool(), response);
            Map<String, Integer> demotions = new TreeMap<>(legacyChecks);
            curation.demotions().forEach((reason, count) -> demotions.merge(reason.name(), count,
                Integer::sum));
            List<String> topChoices = new ArrayList<>();
            List<String> allChoices = new ArrayList<>();
            int suggested = 0;
            int choiceCount = 0;
            for (CuratedSlot slot : curation.day().slots()) {
                topChoices.add(slot.choices().isEmpty() ? "-" : key(slot.choices().get(0)));
                List<String> keys = new ArrayList<>();
                for (CuratedPlace choice : slot.choices()) {
                    keys.add(key(choice));
                    choiceCount++;
                    if (choice.source() == CandidateSourceType.SUGGESTED) {
                        suggested++;
                    }
                }
                allChoices.add(String.join(">", keys));
            }
            return new CallRecord(condition, repeat, dayIndex, day.region(), day.plan().day(),
                outcome, elapsedMs, delta.responses(), delta.input(), delta.cached(),
                delta.output(), delta.reasoning(), choiceCount, suggested,
                demotions,
                topChoices, allChoices, error);
        }

        /**
         * 선택의 식별자. 목록 선택은 <b>번호</b>로, 목록 밖 제안은 이름으로 센다 — 스키마에서 이름을
         * 빼는 조건(3단계)과도 같은 키로 비교하기 위해서다. 출처가 목록이면 최종 장소는 번호가 정한다.
         */
        private static String key(CuratedPlace choice) {
            return choice.source() == CandidateSourceType.SUGGESTED
                ? "S:" + choice.placeName()
                : "#" + choice.listIndex();
        }
    }

    /** 순차 호출이라 전후 차이가 곧 그 호출 하나의 토큰이다. 의미 재시도가 있으면 응답 2건이 합쳐진다. */
    private record TokenSnapshot(long responses, long input, long cached, long output,
                                 long reasoning) {

        static TokenSnapshot of(SimpleMeterRegistry registry) {
            return new TokenSnapshot(
                (long) summary(registry, AiCourseMetrics.TOKEN_OUTPUT).count(),
                total(registry, AiCourseMetrics.TOKEN_INPUT),
                total(registry, AiCourseMetrics.TOKEN_CACHED),
                total(registry, AiCourseMetrics.TOKEN_OUTPUT),
                total(registry, AiCourseMetrics.TOKEN_REASONING));
        }

        TokenSnapshot minus(TokenSnapshot other) {
            return new TokenSnapshot(responses - other.responses, input - other.input,
                cached - other.cached, output - other.output, reasoning - other.reasoning);
        }

        private static long total(SimpleMeterRegistry registry, String type) {
            return (long) summary(registry, type).totalAmount();
        }

        private static DistributionSummary summary(SimpleMeterRegistry registry, String type) {
            DistributionSummary summary = registry.find(AiCourseMetrics.LLM_TOKENS)
                .tag("agent", CuratorAgent.AGENT_NAME).tag("type", type).summary();
            return summary != null ? summary
                : DistributionSummary.builder("absent").register(new SimpleMeterRegistry());
        }
    }

    // ── 집계 ─────────────────────────────────────────────────────────────────

    private static void printSummary(List<String> conditions, List<CallRecord> records) {
        System.out.printf("%n[요약] 조건별 (성공 호출 기준)%n");
        System.out.printf("  %-6s %4s %8s %8s %8s %8s %8s %8s %6s %6s %s%n", "조건", "호출",
            "시간p50", "시간평균", "시간최대", "출력평균", "출력최대", "추론평균", "본문평균", "SUG%",
            "강등/실패");
        for (String condition : conditions) {
            List<CallRecord> all = records.stream().filter(r -> r.condition().equals(condition))
                .toList();
            List<CallRecord> ok = all.stream().filter(r -> r.outcome().equals("success")).toList();
            if (ok.isEmpty()) {
                System.out.printf("  %-6s 성공 0건 — %s%n", condition,
                    all.stream().map(CallRecord::outcome).distinct().toList());
                continue;
            }
            List<Long> elapsed = ok.stream().map(CallRecord::elapsedMs).sorted().toList();
            Map<String, Integer> demotions = new TreeMap<>();
            ok.forEach(r -> r.demotions().forEach((k, v) -> demotions.merge(k, v, Integer::sum)));
            int choices = ok.stream().mapToInt(CallRecord::choiceCount).sum();
            int suggested = ok.stream().mapToInt(CallRecord::suggested).sum();
            long failed = all.size() - ok.size();
            long retried = ok.stream().filter(r -> r.responses() > 1).count();
            System.out.printf("  %-6s %4d %8d %8.0f %8d %8.0f %8d %8.0f %8.0f %5.1f%% %s 실패%d 재시도%d%n",
                condition, ok.size(), elapsed.get((elapsed.size() - 1) / 2),
                ok.stream().mapToLong(CallRecord::elapsedMs).average().orElse(0),
                elapsed.get(elapsed.size() - 1),
                ok.stream().mapToLong(CallRecord::outputTokens).average().orElse(0),
                ok.stream().mapToLong(CallRecord::outputTokens).max().orElse(0),
                ok.stream().mapToLong(CallRecord::reasoningTokens).average().orElse(0),
                ok.stream().mapToLong(r -> r.outputTokens() - r.reasoningTokens()).average()
                    .orElse(0),
                choices == 0 ? 0.0 : suggested * 100.0 / choices, demotions, failed, retried);
        }
    }

    /**
     * 1순위 일치율. 같은 입력·같은 자리에서 두 실행의 1순위가 같은 장소인가를 센다.
     * <b>같은 조건 반복 간</b> 일치율이 기준선이고, <b>조건 간</b> 일치율이 그보다 뚜렷이 낮을 때만
     * "조건이 선택을 바꿨다"고 읽는다.
     */
    private static void printAgreement(List<String> conditions, int repeats,
        List<CallRecord> records) {
        Map<String, CallRecord> byKey = new LinkedHashMap<>();
        records.stream().filter(r -> r.outcome().equals("success"))
            .forEach(r -> byKey.put(r.condition() + "|" + r.repeat() + "|" + r.dayIndex(), r));
        int dayCount = records.stream().mapToInt(CallRecord::dayIndex).max().orElse(-1) + 1;

        System.out.printf("%n[일치율] 1순위 / 3선택 순서까지 동일%n");
        for (int a = 0; a < conditions.size(); a++) {
            for (int b = a; b < conditions.size(); b++) {
                int[] top = new int[2];
                int[] full = new int[2];
                for (int ra = 1; ra <= repeats; ra++) {
                    for (int rb = 1; rb <= repeats; rb++) {
                        // 같은 조건이면 서로 다른 반복끼리만, 한 쌍을 한 번만 센다.
                        if (a == b && rb <= ra) {
                            continue;
                        }
                        for (int d = 0; d < dayCount; d++) {
                            CallRecord x = byKey.get(conditions.get(a) + "|" + ra + "|" + d);
                            CallRecord y = byKey.get(conditions.get(b) + "|" + rb + "|" + d);
                            if (x == null || y == null) {
                                continue;
                            }
                            for (int s = 0; s < x.topChoices().size(); s++) {
                                top[1]++;
                                full[1]++;
                                if (x.topChoices().get(s).equals(y.topChoices().get(s))) {
                                    top[0]++;
                                }
                                if (x.allChoices().get(s).equals(y.allChoices().get(s))) {
                                    full[0]++;
                                }
                            }
                        }
                    }
                }
                System.out.printf("  %-6s vs %-6s 1순위 %5.1f%% (%d/%d) · 3선택 %5.1f%%%n",
                    conditions.get(a), conditions.get(b), pct(top), top[0], top[1], pct(full));
            }
        }
    }

    private static double pct(int[] ratio) {
        return ratio[1] == 0 ? 0.0 : ratio[0] * 100.0 / ratio[1];
    }

    private static void writeCsv(Path path, List<CallRecord> records) throws IOException {
        StringBuilder csv = new StringBuilder(
            "condition,repeat,region,day,outcome,elapsedMs,responses,input,cached,output,reasoning,"
                + "choices,suggested,demotions,top,all,error\n");
        for (CallRecord r : records) {
            csv.append(String.join(",", r.condition(), String.valueOf(r.repeat()), r.region(),
                String.valueOf(r.day()), r.outcome(), String.valueOf(r.elapsedMs()),
                String.valueOf(r.responses()), String.valueOf(r.inputTokens()),
                String.valueOf(r.cachedTokens()), String.valueOf(r.outputTokens()),
                String.valueOf(r.reasoningTokens()), String.valueOf(r.choiceCount()),
                String.valueOf(r.suggested()), quote(r.demotions().toString()),
                quote(String.join(" | ", r.topChoices())), quote(String.join(" | ", r.allChoices())), quote(r.error())))
                .append('\n');
        }
        // 엑셀에서 한글이 깨지지 않게 BOM 을 붙인다(환각 하네스와 같은 관례).
        HallucinationArtifacts.writeUtf8Bom(path, csv.toString());
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String classpathText(String path) {
        try (var in = CuratorReplayBenchmarkTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("테스트 리소스가 없다: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = String.valueOf(cause.getMessage());
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    // ── 조립 ─────────────────────────────────────────────────────────────────

    /** 운영 설정과 같은 재시도·타임아웃·출력 상한. 바뀌는 것은 Curator 의 추론 강도 하나다. */
    private static OpenAiLlmClient llmClient(String apiKey, String curatorEffort,
        AiCourseMetrics metrics) {
        AiLlmProperties properties = new AiLlmProperties(
            "openai",
            20_000,
            1,
            new AiLlmProperties.Retry(3, 2, 0.5, 4.0, 0.3),
            Map.of(
                PlannerAgent.AGENT_NAME,
                new AiLlmProperties.Agent(MODEL, null, 2048, null),
                CuratorAgent.AGENT_NAME,
                new AiLlmProperties.Agent(MODEL, null, MAX_OUTPUT_TOKENS, curatorEffort)),
            new AiLlmProperties.OpenAi(apiKey, "https://api.openai.com"));
        return new OpenAiLlmClient(properties, new LlmResponseParser(new ObjectMapper()),
            new LlmRetryExecutor(properties), metrics,
            OpenAiLlmClient.buildChatModel(properties.openai().baseUrl(), apiKey,
                properties.timeoutMs()));
    }

    private static CandidateRetrievalStage retrievalStage(String naverId, String naverSecret,
        String tourKey, String kakaoKey, AiCourseMetrics metrics) {
        KakaoLocalClient kakaoClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));
        NaverLocalClient naverClient = new NaverLocalClient(NaverConfig.buildNaverWebClient(
            "https://naverapihub.apigw.ntruss.com", naverId, naverSecret));
        TourApiClient tourClient = new TourApiClient(TourApiConfig.buildTourApiWebClient(
            "https://apis.data.go.kr/B551011/KorService2"), tourKey);
        return new CandidateRetrievalStage(new AreaGeocoder(kakaoClient),
            new NaverLocalSeedSource(naverClient, metrics), new TourApiSource(tourClient), metrics,
            Runnable::run);
    }

    // ── 데이터 ───────────────────────────────────────────────────────────────

    private record InputSpec(String region, String keywordSetId) {

        List<KeywordType> keywords() {
            return BaselineInputSet.KEYWORD_SETS.stream()
                .filter(set -> set.id().equals(keywordSetId))
                .findFirst().orElseThrow().keywords();
        }
    }

    /** day 하나의 Curator 입력. 검증기를 다시 태우려고 자리 구성과 그 day 의 후보 풀도 함께 얼린다. */
    record CapturedDay(String region, String keywordSetId, PlannerDayPlan plan, CandidatePool pool,
                       String userPrompt) {
    }

    record CapturedInputs(LocalDateTime capturedAt, List<CapturedDay> days) {
    }

    /**
     * #194 이전의 Curator 응답. 기준선 조건(legacy)을 같은 세션에서 다시 재기 위해 하네스에만 남긴다.
     *
     * <p>{@link #toCurrent}는 <b>옛 검증기의 의미를 그대로</b> 현행 DTO 로 옮긴다 — 슬롯 타입·출처·이름이
     * 어긋난 선택은 이름만 남겨(번호 null) 옛날처럼 SUGGESTED 로 강등되게 한다. 그래야 두 조건이 같은
     * 검증기·같은 선택 키로 비교되고, 옛 검산 장치가 발동한 횟수를 {@code legacy:*}로 따로 셀 수 있다.
     * 그 횟수가 새 스키마로 <b>잃는 감지량</b>이다.
     */
    record LegacyCuratorResponse(Integer day, List<Slot> slots) {

        record Slot(Integer slotIndex, String slotType, List<Choice> choices) {
        }

        record Choice(String source, Integer listIndex, String placeName) {
        }

        CuratorResponse toCurrent(CapturedDay captured, Map<String, Integer> checks) {
            List<CuratorResponse.Slot> converted = new ArrayList<>();
            List<SlotType> plannerSlots = captured.plan().slots();
            for (Slot slot : slots == null ? List.<Slot>of() : slots) {
                Integer position = slot.slotIndex();
                SlotType plannerType = position != null && position >= 0
                    && position < plannerSlots.size() ? plannerSlots.get(position) : null;
                boolean slotMismatch = plannerType != null
                    && !plannerType.name().equalsIgnoreCase(String.valueOf(slot.slotType()).trim());
                CandidateSlot list = plannerType == null ? CandidateSlot.empty(captured.plan().day(),
                    SlotType.ATTRACTION) : captured.pool().findOrEmpty(captured.plan().day(), plannerType);
                List<CuratorResponse.Choice> choices = new ArrayList<>();
                for (Choice choice : slot.choices() == null ? List.<Choice>of() : slot.choices()) {
                    String name = choice.placeName() == null ? null : choice.placeName().trim();
                    String source = choice.source() == null ? "" : choice.source().trim()
                        .toUpperCase(Locale.ROOT);
                    if (name == null || name.isEmpty()) {
                        choices.add(new CuratorResponse.Choice(null, null));
                    } else if (source.equals("SUGGESTED")) {
                        choices.add(new CuratorResponse.Choice(null, name));
                    } else if (!source.equals("SEEDED") && !source.equals("LISTED")) {
                        checks.merge("legacy:UNKNOWN_SOURCE", 1, Integer::sum);
                        choices.add(new CuratorResponse.Choice(null, name));
                    } else if (slotMismatch) {
                        checks.merge("legacy:SLOT_MISMATCH", 1, Integer::sum);
                        choices.add(new CuratorResponse.Choice(null, name));
                    } else if (list.at(choice.listIndex()).isEmpty()) {
                        // 범위 밖은 현행 검증기도 같은 사유로 강등하므로 번호를 그대로 넘긴다.
                        choices.add(new CuratorResponse.Choice(
                            choice.listIndex() == null ? -1 : choice.listIndex(), name));
                    } else if (!PlaceNameNormalizer.similar(
                        list.at(choice.listIndex()).get().name(), name)) {
                        checks.merge("legacy:NAME_MISMATCH", 1, Integer::sum);
                        choices.add(new CuratorResponse.Choice(null, name));
                    } else {
                        choices.add(new CuratorResponse.Choice(choice.listIndex(), null));
                    }
                }
                converted.add(new CuratorResponse.Slot(position, choices));
            }
            return new CuratorResponse(converted);
        }
    }

    private record CallRecord(String condition, int repeat, int dayIndex, String region, int day,
                              String outcome, long elapsedMs, long responses, long inputTokens,
                              long cachedTokens, long outputTokens, long reasoningTokens,
                              int choiceCount, int suggested, Map<String, Integer> demotions,
                              List<String> topChoices, List<String> allChoices, String error) {
    }
}
