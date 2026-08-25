package backend.yourtrip.global.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.LlmResponseParser;
import backend.yourtrip.global.ai.LlmRetryExecutor;
import backend.yourtrip.global.ai.candidate.AreaGeocoder;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateRetrievalStage;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.NaverLocalSeedSource;
import backend.yourtrip.global.ai.candidate.TourApiSource;
import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.openai.OpenAiLlmClient;
import backend.yourtrip.global.ai.pipeline.CuratedDay;
import backend.yourtrip.global.ai.pipeline.CuratedPlace;
import backend.yourtrip.global.ai.pipeline.CuratedSlot;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.config.NaverConfig;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.config.TourApiConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Planner·Curator 실호출 프로브 (ROADMAP 6단계 검증). <b>OpenAI와 장소 API를 실제로 호출한다.</b>
 *
 * <p><b>스텁으로는 물을 수 없는 것 넷을 확인한다.</b>
 * <ol>
 *   <li><b>Planner 스키마가 실 API에서 400을 맞지 않는가</b> — 특히 {@code slots}의 {@code enum}
 *       제약과 {@code dayStartTime} 문자열. WireMock은 "필드가 실려 나가는 것"까지만 검증한다</li>
 *   <li><b>Curator 스키마의 nullable {@code listIndex}가 strict 모드를 통과하는가</b> —
 *       {@code "type": ["integer", "null"]} 표기가 받아들여지는지는 실호출로만 알 수 있다</li>
 *   <li><b>Curator가 실제로 목록에서 고르는가</b> — 강등률이 이 질문의 답이다. 100%라면 6-7이
 *       막고는 있지만 선별 자체가 동작하지 않는다는 뜻이라 프롬프트를 다시 봐야 한다</li>
 *   <li><b>{@code SUGGESTED} 비율</b> — 유명 지역과 무인지 지역에서 다르게 나오는지. 설계는
 *       "무인지에서는 비어 있는 게 정상"이라고 봤다</li>
 * </ol>
 *
 * <p><b>판정용이지 회귀 테스트가 아니다.</b> 외부 응답에 의존하므로 단언은 "이 설계가 성립하려면
 * 반드시 참이어야 하는 것"에만 걸고, 나머지는 콘솔로 덤프해 사람이 읽는다
 * ({@code CandidateRetrievalProbeTest}가 세운 형태).
 *
 * <p><b>규모는 2코스 × 2일 = LLM 호출 6회다</b>(Planner 2 + Curator 4). 로드맵의 "외부 API 실호출은
 * 비용과 쿼터를 소모한다"는 원칙에 따라 의도적으로 작게 잡았다.
 *
 * <pre>{@code
 * ./gradlew benchmarkTest --tests '*AgentProbeTest*' --rerun
 * }</pre>
 */
@Tag("benchmark")
@DisplayName("Planner·Curator 실호출 프로브 (ROADMAP 6단계)")
class AgentProbeTest {

    /** 하네스와 같은 축 — 유명 1 + 무인지 1. 지역 티어별 차이를 보기 위한 최소 세트다. */
    private static final List<String> REGIONS = List.of("경주", "공주");

    private static final int DAYS = 2;
    /** 관광 계열 하나와 시더 전용 슬롯 둘. 어느 쪽이 비는지 가르기 위한 최소 조합이다. */
    private static final List<backend.yourtrip.global.ai.route.SlotType> PROBE_SLOTS = List.of(
        backend.yourtrip.global.ai.route.SlotType.ATTRACTION,
        backend.yourtrip.global.ai.route.SlotType.MEAL,
        backend.yourtrip.global.ai.route.SlotType.CAFE);

    private static final List<KeywordType> KEYWORDS =
        List.of(KeywordType.WALK, KeywordType.COUPLE, KeywordType.HEALING, KeywordType.SENSIBILITY);

    @Test
    @DisplayName("두 지역의 Planner·Curator를 실제로 호출해 스키마와 선별 동작을 확인한다")
    void probe() {
        String openAiKey = env("OPENAI_API_KEY");
        String naverId = env("NAVER_CLIENT_ID");
        String naverSecret = env("NAVER_CLIENT_SECRET");
        String tourKey = env("TOUR_API_KEY");
        String kakaoKey = env("KAKAO_API_KEY");
        assumeTrue(openAiKey != null && naverId != null && naverSecret != null && tourKey != null
            && kakaoKey != null, "OpenAI·네이버·TourAPI·카카오 키가 모두 있어야 실측할 수 있다");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiCourseMetrics metrics = new AiCourseMetrics(registry);
        AiLlmProperties properties = probeProperties(openAiKey);
        OpenAiLlmClient llmClient = new OpenAiLlmClient(properties,
            new LlmResponseParser(new ObjectMapper()), new LlmRetryExecutor(properties), metrics,
            OpenAiLlmClient.buildChatModel(properties.openai().baseUrl(), openAiKey,
                properties.timeoutMs()));

        PromptLoader promptLoader = new PromptLoader();
        PlannerAgent planner = new PlannerAgent(llmClient, promptLoader, Runnable::run);
        CuratorAgent curator = new CuratorAgent(llmClient, promptLoader, metrics, Runnable::run);
        CandidateRetrievalStage retrieval = retrievalStage(naverId, naverSecret, tourKey, kakaoKey,
            metrics);

        for (String region : REGIONS) {
            CourseDeadline deadline = CourseDeadline.unbounded();

            // ① Planner — 여기서 400 이 나면 스키마의 enum 제약이나 dayStartTime 표기가 문제다.
            PlannerPlan plan = planner.plan(region, DAYS, KEYWORDS, deadline);
            printPlan(region, plan);
            assertThat(plan.days()).hasSize(DAYS);
            assertThat(plan.days()).allSatisfy(day -> {
                assertThat(day.area()).isNotBlank();
                assertThat(day.anchor()).isNotBlank();
                assertThat(day.slots()).isNotEmpty();
            });

            // ② 후보 공급 — LLM 은 쓰지 않는다. Curator 에게 줄 목록을 실제로 만든다.
            CandidatePool pool = retrieval.retrieve(region, plan, KEYWORDS, deadline);

            // ③ Curator — nullable listIndex 가 strict 를 통과하는지가 여기서 드러난다.
            List<CuratedDay> curated = curator.curate(plan, pool, KEYWORDS, deadline);
            printCuration(region, plan, pool, curated, registry);
            assertThat(curated).hasSize(DAYS);
        }

        printDemotionSummary(registry);
    }

    /**
     * <b>Planner 가 내는 자연어 {@code area}가 네이버 시더에서 통하는가.</b>
     *
     * <p>5-9 실측은 "빈 슬롯 0%"였지만 그때의 {@code area}는 {@code "경주"} 같은 <b>단순 지역명</b>이었다.
     * 실제 Planner 는 설계가 요구한 대로 {@code "황리단길·대릉원 일대"} 같은 자연어를 내고, 그 문자열이
     * 그대로 {@code "{area} {searchHint}"} 쿼리의 접두사가 된다. 가운뎃점과 "일대"가 붙은 채로도
     * 지역검색이 결과를 주는지는 <b>실호출로만 알 수 있다.</b>
     *
     * <p>LLM 을 부르지 않으므로 위 프로브와 따로 돌려도 비용이 들지 않는다.
     */
    @Test
    @DisplayName("Planner 가 내는 자연어 area 로 시더가 후보를 확보하는지 본다 (LLM 호출 없음)")
    void probeNaturalLanguageArea() {
        String naverId = env("NAVER_CLIENT_ID");
        String naverSecret = env("NAVER_CLIENT_SECRET");
        String tourKey = env("TOUR_API_KEY");
        String kakaoKey = env("KAKAO_API_KEY");
        assumeTrue(naverId != null && naverSecret != null && tourKey != null && kakaoKey != null,
            "네이버·TourAPI·카카오 키가 모두 있어야 실측할 수 있다");

        AiCourseMetrics metrics = new AiCourseMetrics(new SimpleMeterRegistry());
        CandidateRetrievalStage retrieval =
            retrievalStage(naverId, naverSecret, tourKey, kakaoKey, metrics);

        // Planner 가 실제로 낸 자연어 area 를 한 단계씩 벗겨 어느 표기부터 0건이 되는지 본다.
        // 대조군은 단순 지역명 — 5-9 실측이 쓴 형태이고, 그때 "빈 슬롯 0%" 가 나온 조건이다.
        List<PlannerDayPlan> cases = List.of(
            PlannerDayPlan.of(1, "황리단길·대릉원 일대", "대릉원", PROBE_SLOTS),
            PlannerDayPlan.of(2, "황리단길·대릉원", "대릉원", PROBE_SLOTS),
            PlannerDayPlan.of(3, "황리단길 일대", "대릉원", PROBE_SLOTS),
            PlannerDayPlan.of(4, "황리단길", "대릉원", PROBE_SLOTS),
            PlannerDayPlan.of(5, "경주 황리단길", "대릉원", PROBE_SLOTS),
            PlannerDayPlan.of(6, "경주", "대릉원", PROBE_SLOTS));

        CandidatePool pool = retrieval.retrieve("경주", new PlannerPlan("t", "c", cases),
            KEYWORDS, CourseDeadline.unbounded());

        System.out.printf("%n[자연어 area] 슬롯별 확보 건수 — SEEDED 만 따로 센다%n");
        pool.slots().forEach(slot -> {
            long seeded = slot.candidates().stream().filter(candidate -> candidate.seeded()).count();
            System.out.printf("  %-24s %-11s 전체 %2d건 · SEEDED %2d건%n",
                cases.get(slot.day() - 1).area(), slot.slotType(), slot.candidates().size(), seeded);
        });
    }

    // ── 출력 — 판정은 사람이 한다 ─────────────────────────────────────────────

    private static void printPlan(String region, PlannerPlan plan) {
        System.out.printf("%n[Planner] %s — title=%s%n", region, plan.title());
        System.out.printf("  concept=%s%n", plan.concept());
        for (PlannerDayPlan day : plan.days()) {
            System.out.printf("  day %d: area=%s / anchor=%s / theme=%s / start=%s / slots=%s%n",
                day.day(), day.area(), day.anchor(), day.theme(), day.dayStartTime(), day.slots());
        }
    }

    private static void printCuration(String region, PlannerPlan plan, CandidatePool pool,
        List<CuratedDay> curated, SimpleMeterRegistry registry) {
        System.out.printf("%n[Curator] %s — 후보 수(day별)=%s%n", region, pool.candidateCountByDay());
        pool.slots().forEach(slot -> System.out.printf("    후보 day %d %-11s %d건%n",
            slot.day(), slot.slotType(), slot.candidates().size()));

        int total = 0;
        int suggested = 0;
        for (int index = 0; index < curated.size(); index++) {
            CuratedDay day = curated.get(index);
            System.out.printf("  day %d (%s)%n", day.day(), plan.days().get(index).area());
            for (CuratedSlot slot : day.slots()) {
                System.out.printf("    %-11s %s%n", slot.slotType(), describe(slot.choices()));
                for (CuratedPlace choice : slot.choices()) {
                    total++;
                    if (choice.source() == CandidateSourceType.SUGGESTED) {
                        suggested++;
                    }
                }
            }
        }
        System.out.printf("  선택 %d건 중 SUGGESTED %d건 (%.1f%%)%n",
            total, suggested, total == 0 ? 0.0 : suggested * 100.0 / total);
    }

    private static String describe(List<CuratedPlace> choices) {
        if (choices.isEmpty()) {
            return "(비어 있음)";
        }
        StringBuilder line = new StringBuilder();
        for (CuratedPlace choice : choices) {
            line.append(line.isEmpty() ? "" : " > ")
                .append(choice.placeName())
                .append("[").append(choice.source())
                .append(choice.listIndex() == null ? "" : " #" + choice.listIndex())
                .append("]");
        }
        return line.toString();
    }

    /**
     * 강등 집계. <b>이 값이 이 프로브의 핵심 산출물이다</b> — 0에 가까우면 모델이 목록을 제대로
     * 참조하고 있다는 뜻이고, 크면 6-7이 막고는 있어도 선별 자체가 헛돌고 있다는 뜻이다.
     */
    private static void printDemotionSummary(SimpleMeterRegistry registry) {
        System.out.printf("%n[강등 집계] %s%n", AiCourseMetrics.CANDIDATE_DEMOTED);
        for (DemotionReason reason : DemotionReason.values()) {
            double count = registry.get(AiCourseMetrics.CANDIDATE_DEMOTED)
                .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                .counter().count();
            System.out.printf("  %-20s %.0f%n", reason.name().toLowerCase(Locale.ROOT),
                count);
        }
    }

    // ── 조립 ──────────────────────────────────────────────────────────────────

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

    /**
     * 운영 설정({@code application.yml})과 <b>같은 모델·같은 추론 강도</b>를 쓴다. 다르게 두면
     * 여기서 통과한 스키마가 운영에서 400을 맞을 수 있다.
     */
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

    /**
     * Spring 컨텍스트를 쓰지 않으므로 spring-dotenv가 동작하지 않는다 — 실제 환경변수 → 레포 루트
     * {@code .env} 순으로 찾는다.
     */
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
                    return value.isEmpty() ? null : value;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}
