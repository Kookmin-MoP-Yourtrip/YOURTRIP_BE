package backend.yourtrip.global.ai.pipeline;

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
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.route.RouteOptimizer;
import backend.yourtrip.global.ai.route.RoutePlace;
import backend.yourtrip.global.ai.route.RouteRequest;
import backend.yourtrip.global.ai.route.RoutedDay;
import backend.yourtrip.global.benchmark.BaselineInputSet;
import backend.yourtrip.global.benchmark.BaselineInputSet.RequestSpec;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.config.NaverConfig;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.config.TourApiConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 동선 측정용 파이프라인 입력 채집 프로브 (ROADMAP 3-7).
 *
 * <p>재는 것이 아니라 <b>재료를 만드는 것</b>이 이 클래스의 일이다. 30요청을 실제 파이프라인에
 * 태우고, {@code RouteOptimizer}에 <b>들어가기 직전의 입력</b>을 CSV로 남긴다. 실제 측정은
 * 외부 호출이 없는 {@code RouteOptimizationEffectTest}가 그 CSV를 읽어 수행한다.
 *
 * <h2>왜 실제 파이프라인을 태우는가</h2>
 *
 * <p><b>슬롯 종류가 권위 있는 값이어야 하기 때문이다.</b> 슬롯은 체류시간을 통해 도착 시각을
 * 바꾸고, 도착 시각은 식사 시간창 벌점을 바꾸므로 <b>최적 순열 선택에 개입한다.</b> MEAL 이
 * 하나 잘못 붙으면 있어서는 안 될 벌점이 생기고 최적화기는 그 자리를 점심창에 맞추려 순서를
 * 크게 흔든다. 즉 슬롯을 사후에 추론한 데이터로는 이 측정을 할 수 없다.
 *
 * <p>파이프라인에서는 슬롯을 Planner 가 정하고 후보 공급이 그 타입으로만 검색하며 5-3 카테고리
 * 하드 제약이 강제한다 — <b>추론할 여지가 없다.</b> 시작 시각과 이동수단도 마찬가지로 실제
 * 입력값 그대로 캡처된다.
 *
 * <h2>캡처 방식</h2>
 *
 * <p>{@code RouteOptimizer}는 {@code final}이 아니고 인스턴스 상태도 없다. 그래서 조립부의
 * {@code new RouteOptimizer()} 자리에 아래 서브클래스를 끼우는 것으로 충분하다 —
 * <b>프로덕션 코드는 한 줄도 바꾸지 않는다.</b> 같은 형태의 선례가
 * {@code AiHallucinationBaselineTest}의 {@code CapturingParser extends LlmResponseParser}다.
 *
 * <pre>
 * ROUTE_PROBE_REQUEST_LIMIT=3 ./gradlew benchmarkTest --tests '*AiCourseRouteInputProbeTest*' --rerun
 * ./gradlew benchmarkTest --tests '*AiCourseRouteInputProbeTest*' --rerun
 * </pre>
 *
 * <p>30요청 기준 LLM 약 120회 · 네이버 540~900회 · TourAPI ≤270회(<b>일 1,000 — 가장 빡빡하다</b>)
 * · 카카오 600~1,000회, 약 20분.
 */
@Tag("benchmark")
@DisplayName("동선 측정용 파이프라인 입력 채집 (ROADMAP 3-7)")
class AiCourseRouteInputProbeTest {

    private static final Path RESULTS_DIR = Path.of("results");

    /**
     * 요청 간 지연. 기본값은 환각률 하네스와 같은 5초다.
     *
     * <p>파이프라인은 요청 하나가 LLM 을 4회 부르므로 baseline(1회)보다 RPM 압박이 크다. 그래도
     * 같은 값에서 출발하는 이유는, 429 가 실제로 나는지를 먼저 보고 조정하는 것이 순서이기
     * 때문이다 — {@code llm.max-concurrent-calls: 2}의 근거를 실제 동시 호출 조건에서 재는 것은
     * 로드맵의 별도 미해결 항목이다.
     */
    private static final long DEFAULT_DELAY_MS = 5_000L;

    /**
     * 연속 실패가 이만큼 쌓이면 멈춘다.
     *
     * <p>키가 죽었거나 쿼터가 끝난 상태로 30요청을 헛돌리면 20분과 남은 쿼터를 함께 버린다.
     * 환각률 하네스가 세운 방침을 그대로 따른다.
     */
    private static final int ABORT_AFTER_CONSECUTIVE_FAILURES = 3;

    @Test
    @DisplayName("30요청을 파이프라인에 태우고 최적화 직전 입력을 CSV 로 남긴다")
    void captureRouteInputs() throws IOException {
        String openAiKey = env("OPENAI_API_KEY");
        String naverId = env("NAVER_CLIENT_ID");
        String naverSecret = env("NAVER_CLIENT_SECRET");
        String tourKey = env("TOUR_API_KEY");
        String kakaoKey = env("KAKAO_API_KEY");
        assumeTrue(openAiKey != null && naverId != null && naverSecret != null && tourKey != null
            && kakaoKey != null, "OpenAI·네이버·TourAPI·카카오 키가 모두 있어야 실측할 수 있다");

        List<RequestSpec> fullInputSet = BaselineInputSet.buildInputSet();
        int startIndex = (int) setting("route.probe.requestFrom", "ROUTE_PROBE_REQUEST_FROM", 1) - 1;
        int limit = (int) setting("route.probe.requestLimit", "ROUTE_PROBE_REQUEST_LIMIT",
            fullInputSet.size());
        int endIndex = Math.min(fullInputSet.size(), startIndex + limit);
        List<RequestSpec> inputSet = fullInputSet.subList(startIndex, endIndex);
        long delayMs = setting("route.probe.delayMs", "ROUTE_PROBE_DELAY_MS", DEFAULT_DELAY_MS);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CapturingRouteOptimizer optimizer = new CapturingRouteOptimizer();
        AiCoursePipeline pipeline =
            pipeline(registry, optimizer, openAiKey, naverId, naverSecret, tourKey, kakaoKey);

        System.out.printf("%n=== 파이프라인 입력 채집: 요청 %d~%d (%d건 / 전체 %d건), "
                + "여행 %d일, 요청 간 지연 %dms ===%n",
            startIndex + 1, endIndex, inputSet.size(), fullInputSet.size(),
            BaselineInputSet.TRIP_DAYS, delayMs);

        List<PlaceRow> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        // try/finally 로 감싸는 이유는 조기 중단 때문이다. 아래 연속 실패 단언은 AssertionError 를
        // 던지는데, 그것이 그대로 빠져나가면 그때까지 성공한 요청의 산출물이 함께 사라진다 —
        // 25번째에서 멈추면 24요청분(약 100회 LLM 호출)을 다시 태워야 한다. 중단은 하되
        // 모은 것은 남기고, 이어 돌릴 때는 ROUTE_PROBE_REQUEST_FROM 으로 뒤를 잇는다.
        try {
            collect(pipeline, optimizer, inputSet, delayMs, rows, failures);
        } finally {
            if (rows.isEmpty()) {
                System.out.printf("%n채집된 장소가 없어 CSV 를 쓰지 않는다.%n");
            } else {
                report(rows, failures, registry, writeCsv(rows));
            }
        }

        assertThat(rows).as("채집된 장소가 하나도 없다 — 파이프라인이 전부 실패했다").isNotEmpty();
    }

    /** 요청 루프. 산출물 기록은 호출자의 {@code finally}가 맡는다. */
    private static void collect(AiCoursePipeline pipeline, CapturingRouteOptimizer optimizer,
        List<RequestSpec> inputSet, long delayMs, List<PlaceRow> rows, List<String> failures) {

        int consecutiveFailures = 0;
        for (RequestSpec spec : inputSet) {
            // 요청 하나가 죽어도 나머지를 계속 돌린다. day-shape 프로브는 try/catch 가 없어
            // 한 요청이 던지면 남은 요청의 LLM 비용이 통째로 날아간다.
            try {
                long startNanos = System.nanoTime();
                optimizer.captured.clear();

                AiCourseDraft draft = pipeline.generate(CourseBrief.of(
                    spec.region().name(), BaselineInputSet.TRIP_DAYS, spec.keywordSet().keywords()));

                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                List<PlaceRow> captured = toRows(spec, optimizer.captured, draft);
                rows.addAll(captured);
                consecutiveFailures = 0;

                System.out.printf("  #%02d %-4s %s : day %d, 장소 %d개 (%,dms)%n",
                    spec.requestId(), spec.region().name(), spec.keywordSet().id(),
                    optimizer.captured.size(), captured.size(), elapsedMs);

            } catch (RuntimeException e) {
                consecutiveFailures++;
                String detail = "#%d %s %s : %s".formatted(spec.requestId(), spec.region().name(),
                    spec.keywordSet().id(), oneLine(e.toString()));
                failures.add(detail);
                System.out.printf("  [실패] %s%n", detail);

                assertThat(consecutiveFailures)
                    .as("연속 %d회 실패했다 — 키·쿼터를 확인하고 ROUTE_PROBE_REQUEST_FROM=%d 으로 "
                        + "이어 돌려라", consecutiveFailures, spec.requestId())
                    .isLessThan(ABORT_AFTER_CONSECUTIVE_FAILURES);
            }
            sleep(delayMs);
        }
    }

    // ── 캡처 ──────────────────────────────────────────────────────────────────

    /**
     * 최적화기에 들어가는 입력을 기록만 더한다. 계산은 그대로 위임하므로 파이프라인의 동작이
     * 달라지지 않는다 — 이 프로브가 만드는 코스는 운영에서 나올 코스와 같다.
     */
    private static final class CapturingRouteOptimizer extends RouteOptimizer {

        private final List<RouteRequest> captured = new ArrayList<>();

        @Override
        public RoutedDay optimize(RouteRequest request) {
            captured.add(request);
            return super.optimize(request);
        }
    }

    /**
     * 캡처한 {@code RouteRequest}를 장소 1건 = 1행으로 편다.
     *
     * <p><b>{@code source}는 캡처만으로 나오지 않는다.</b> {@link RoutePlace}는 이름·슬롯·좌표만
     * 나르고 {@code SEEDED}/{@code LISTED}/{@code SUGGESTED}는 {@link GroundedPlace}에만 있다.
     * 파이프라인이 돌려준 {@link AiCourseDraft}에는 남아 있으므로 {@code (day, 이름, 좌표)}로
     * 조인해 되찾는다. 좌표까지 키에 넣는 이유는 같은 day 에 같은 이름이 두 번 나올 수 있어서다.
     */
    private static List<PlaceRow> toRows(RequestSpec spec, List<RouteRequest> captured,
        AiCourseDraft draft) {

        Map<String, String> sourceByKey = new HashMap<>();
        for (AiCourseDay day : draft.days()) {
            for (AiCoursePlace place : day.places()) {
                GroundedPlace grounded = place.place();
                sourceByKey.put(joinKey(day.day(), grounded.name(),
                    grounded.latitude(), grounded.longitude()), grounded.source().name());
            }
        }

        List<PlaceRow> rows = new ArrayList<>();
        for (RouteRequest request : captured) {
            List<RoutePlace> places = request.places();
            for (int index = 0; index < places.size(); index++) {
                RoutePlace place = places.get(index);
                rows.add(new PlaceRow(spec, request, index, place,
                    // 하루 초과로 드롭된 장소는 draft 에 없다. 빈 값으로 두고 측정 쪽이 판단한다.
                    sourceByKey.getOrDefault(joinKey(request.day(), place.name(),
                        place.latitude(), place.longitude()), "")));
            }
        }
        return rows;
    }

    private static String joinKey(int day, String name, double latitude, double longitude) {
        return "%d|%s|%.7f|%.7f".formatted(day, name, latitude, longitude);
    }

    private record PlaceRow(RequestSpec spec, RouteRequest request, int placeIndex,
                            RoutePlace place, String source) {}

    // ── 산출물 ────────────────────────────────────────────────────────────────

    private static Path writeCsv(List<PlaceRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("requestId,location,regionTier,keywordSet,day,placeIndex,placeName,slotType,")
            .append("latitude,longitude,source,dayStartTime,travelMode\n");

        for (PlaceRow r : rows) {
            sb.append(r.spec().requestId()).append(',')
                .append(csv(r.spec().region().name())).append(',')
                .append(r.spec().region().tier()).append(',')
                .append(csv(r.spec().keywordSet().id())).append(',')
                .append(r.request().day()).append(',')
                .append(r.placeIndex()).append(',')
                .append(csv(r.place().name())).append(',')
                .append(r.place().slotType()).append(',')
                // 좌표는 소수점 7자리로 고정한다 — 네이버가 WGS84 × 10^7 로 주는 정밀도이고,
                // 기본 toString 은 지수 표기로 빠질 수 있어 CSV 를 사람이 읽을 때 걸린다.
                .append("%.7f".formatted(r.place().latitude())).append(',')
                .append("%.7f".formatted(r.place().longitude())).append(',')
                .append(r.source()).append(',')
                .append(r.request().dayStartTime()).append(',')
                .append(r.request().travelMode()).append('\n');
        }

        Files.createDirectories(RESULTS_DIR);
        String runTag = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path out = RESULTS_DIR.resolve("route-pipeline-places-" + runTag + ".csv");
        // 엑셀이 UTF-8 을 자동 인식하지 못해 한글 지역명이 깨진다. 기존 하네스와 같은 처리다.
        Files.writeString(out, '﻿' + sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    private static void report(List<PlaceRow> rows, List<String> failures,
        SimpleMeterRegistry registry, Path out) {

        long days = rows.stream()
            .map(r -> r.spec().requestId() + "/" + r.request().day()).distinct().count();
        long requests = rows.stream().map(r -> r.spec().requestId()).distinct().count();

        System.out.printf("%n=== 채집 결과 ===%n");
        System.out.printf("  요청 %d건 · day %d개 · 장소 %d건%n", requests, days, rows.size());
        System.out.printf("  day 당 평균 장소 %.2f개%n", days == 0 ? 0 : (double) rows.size() / days);

        Map<String, Long> bySource = new java.util.TreeMap<>();
        Map<String, Long> bySlot = new java.util.TreeMap<>();
        for (PlaceRow row : rows) {
            bySource.merge(row.source().isBlank() ? "(드롭)" : row.source(), 1L, Long::sum);
            bySlot.merge(row.place().slotType().name(), 1L, Long::sum);
        }
        System.out.printf("  [출처]%n");
        bySource.forEach((k, v) -> System.out.printf("    %-12s %4d%n", k, v));
        System.out.printf("  [슬롯]%n");
        bySlot.forEach((k, v) -> System.out.printf("    %-12s %4d%n", k, v));

        // 폴백이 채운 장소는 후보 목록 상위 3이라 동선 분포가 다를 수 있다(STEP-7 판정 13).
        // 0이 아니면 측정이 오염된 것이므로 결과 문서에 반드시 적는다.
        System.out.printf("%n  [측정 오염 확인] %s%n", AiCourseMetrics.CURATION_SLOT);
        registry.getMeters().stream()
            .filter(meter -> AiCourseMetrics.CURATION_SLOT.equals(meter.getId().getName()))
            .forEach(meter -> System.out.printf("    result=%-10s %.0f%n",
                meter.getId().getTag("result"), counterValue(meter)));

        if (!failures.isEmpty()) {
            System.out.printf("%n  [실패한 요청 %d건]%n", failures.size());
            failures.forEach(detail -> System.out.printf("    %s%n", detail));
        }
        System.out.printf("%n산출물: %s%n", out.toAbsolutePath());
    }

    private static double counterValue(Meter meter) {
        double sum = 0;
        for (io.micrometer.core.instrument.Measurement measurement : meter.measure()) {
            sum += measurement.getValue();
        }
        return sum;
    }

    // ── 배선 — Spring 컨텍스트를 쓰지 않고 손으로 조립한다 (AiCourseDayShapeProbeTest 와 같다) ──

    private static AiCoursePipeline pipeline(SimpleMeterRegistry registry,
        RouteOptimizer routeOptimizer, String openAiKey, String naverId, String naverSecret,
        String tourKey, String kakaoKey) {

        AiCourseMetrics metrics = new AiCourseMetrics(registry);
        AiLlmProperties properties = probeProperties(openAiKey);
        OpenAiLlmClient llmClient = new OpenAiLlmClient(properties,
            new LlmResponseParser(new ObjectMapper()), new LlmRetryExecutor(properties), metrics,
            OpenAiLlmClient.buildChatModel(properties.openai().baseUrl(), openAiKey,
                properties.timeoutMs()));

        PromptLoader promptLoader = new PromptLoader();
        KakaoLocalClient kakaoClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));
        NaverLocalClient naverClient = new NaverLocalClient(NaverConfig.buildNaverWebClient(
            "https://naverapihub.apigw.ntruss.com", naverId, naverSecret));
        TourApiClient tourClient = new TourApiClient(TourApiConfig.buildTourApiWebClient(
            "https://apis.data.go.kr/B551011/KorService2"), tourKey);

        return new AiCoursePipeline(
            new PlannerAgent(llmClient, promptLoader, Runnable::run),
            new CandidateRetrievalStage(new AreaGeocoder(kakaoClient),
                new NaverLocalSeedSource(naverClient, metrics), new TourApiSource(tourClient),
                metrics, Runnable::run),
            new CuratorAgent(llmClient, promptLoader, metrics, Runnable::run),
            new GroundingStage(kakaoClient, metrics, Runnable::run),
            routeOptimizer,
            new PlaceUrlEnricher(kakaoClient, metrics, Runnable::run),
            metrics,
            // 예산을 넉넉히 준다 — 재려는 것은 지연이 아니라 동선이라, 데드라인에 걸려 단계가
            // 잘리면 관측 대상 자체가 사라진다. 지연 재측정은 8-6의 몫이다.
            new AiCourseProperties(180_000));
    }

    /** 운영 설정({@code application.yml})과 같은 모델·추론 강도를 쓴다. */
    private static AiLlmProperties probeProperties(String apiKey) {
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

    // ── 실행 파라미터 ─────────────────────────────────────────────────────────

    /**
     * 시스템 프로퍼티와 환경변수를 <b>둘 다</b> 받는다 — Gradle 이 {@code -D}를 테스트 JVM 에
     * 그대로 전달하지 않기 때문이다. 환각률 하네스가 세운 관례다.
     */
    private static long setting(String systemProperty, String envVar, long defaultValue) {
        String raw = System.getProperty(systemProperty);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(envVar);
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(raw.trim());
    }

    /** Spring 컨텍스트가 없어 spring-dotenv 가 동작하지 않는다 — 환경변수 → 레포 루트 {@code .env} 순. */
    private static String env(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Path dotEnv = Path.of(".env");
        if (!Files.exists(dotEnv)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(key + "=")) {
                    String value = trimmed.substring(key.length() + 1).trim();
                    return value.isBlank() ? null : value;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static String csv(String value) {
        String s = value == null ? "" : value;
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String oneLine(String value) {
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
