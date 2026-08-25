package backend.yourtrip.global.ai.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
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
import backend.yourtrip.global.ai.grounding.GroundingStage;
import backend.yourtrip.global.ai.grounding.PlaceUrlEnricher;
import backend.yourtrip.global.ai.openai.OpenAiLlmClient;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.route.RouteOptimizer;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.ai.route.TravelMode;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>하루의 모양</b>을 실호출로 확인하는 프로브 (이슈 #135). 파이프라인 전 단계를 실제로 태운다 —
 * Planner·Curator 는 OpenAI 를, 후보 공급과 그라운딩은 네이버·TourAPI·카카오를 부른다.
 *
 * <p><b>왜 단위 테스트로는 못 묻는가.</b> 탄력 체류의 단위 테스트({@code RouteOptimizerStretchTest})는
 * 슬롯 구성과 좌표를 직접 준다. 그런데 이슈가 물은 것은 "실제 LLM 이 내놓는 슬롯 구성과 실제 장소
 * 좌표에서도 하루가 저녁까지 이어지는가"이고, 그 둘은 <b>고정할 수 없는 입력</b>이다. 프롬프트를
 * 5~7개·식사 2회로 바꾼 것이 실제로 먹히는지도 실호출로만 드러난다.
 *
 * <p><b>이슈의 출발점이 된 관측</b>은 "생성한 6개 코스가 모두 오후 1~4시에 끝났다"였다. 그래서 이
 * 프로브의 덤프도 같은 축이다 — day 종료가 저녁에 닿는가, 저녁 식사가 시간창에 들어오는가.
 *
 * <p><b>판정용이지 회귀 테스트가 아니다</b>({@code AgentProbeTest}가 세운 형태). 외부 응답에
 * 의존하므로 단언은 "이 설계가 성립하려면 반드시 참이어야 하는 것"에만 걸고, 나머지는 콘솔로
 * 덤프해 사람이 읽는다. 특히 <b>종료 시각 자체에는 하한을 걸지 않는다</b> — 후보가 부족한 지역에서
 * 슬롯이 덜 차면 짧은 하루가 나올 수 있고, 그건 탄력 체류의 실패가 아니라 후보 공급의 문제다.
 * 그 구분은 사람이 덤프를 보고 해야 한다.
 *
 * <p><b>규모는 2코스 × 3일 = LLM 호출 8회다</b>(Planner 2 + Curator 6). 3일인 것은 이슈의 관측
 * 대상이 3일 코스였기 때문이다.
 *
 * <pre>{@code
 * ./gradlew benchmarkTest --tests '*AiCourseDayShapeProbeTest*' --rerun
 * }</pre>
 */
@Tag("benchmark")
@DisplayName("하루의 모양 실호출 프로브 (이슈 #135)")
class AiCourseDayShapeProbeTest {

    /** 유명 1 + 무인지 1. {@code AgentProbeTest}와 같은 축이라 후보 공급 차이를 함께 읽을 수 있다. */
    private static final List<String> REGIONS = List.of("경주", "공주");

    private static final int DAYS = 3;

    private static final List<KeywordType> KEYWORDS =
        List.of(KeywordType.WALK, KeywordType.COUPLE, KeywordType.HEALING);

    /** 저녁 시간창. {@code RouteOptimizer}의 {@code DINNER_WINDOW}와 같은 값이다. */
    private static final LocalTime DINNER_START = LocalTime.of(17, 30);
    private static final LocalTime DINNER_END = LocalTime.of(19, 30);

    @Test
    @DisplayName("실제 LLM·장소 API 로 3일 코스를 만들어 하루가 저녁까지 이어지는지 본다")
    void probe() {
        String openAiKey = env("OPENAI_API_KEY");
        String naverId = env("NAVER_CLIENT_ID");
        String naverSecret = env("NAVER_CLIENT_SECRET");
        String tourKey = env("TOUR_API_KEY");
        String kakaoKey = env("KAKAO_API_KEY");
        assumeTrue(openAiKey != null && naverId != null && naverSecret != null && tourKey != null
            && kakaoKey != null, "OpenAI·네이버·TourAPI·카카오 키가 모두 있어야 실측할 수 있다");

        AiCoursePipeline pipeline = pipeline(openAiKey, naverId, naverSecret, tourKey, kakaoKey);

        List<AiCourseDay> allDays = new ArrayList<>();
        for (String region : REGIONS) {
            long startNanos = System.nanoTime();
            AiCourseDraft draft = pipeline.generate(
                new CourseBrief(region, DAYS, KEYWORDS, TravelMode.UNSPECIFIED));
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            print(region, draft, elapsedMs);
            assertThat(draft.days()).hasSize(DAYS);
            allDays.addAll(draft.days());
        }

        summarize(allDays);

        // 이 설계가 성립하려면 반드시 참이어야 하는 것만 단언한다.
        assertThat(allDays).allSatisfy(day -> {
            assertThat(day.places())
                .as("day %d 는 장소가 있어야 한다", day.day())
                .isNotEmpty();

            // 탄력 체류가 늘린 체류도 슬롯 상한을 넘지 않는다 — 넘으면 흡수 상한 계산이 샌 것이다.
            assertThat(day.places()).allSatisfy(place -> {
                SlotType slotType = place.place().slotType();
                assertThat(place.stayMinutes())
                    .as("%s(%s) 의 체류", place.place().name(), slotType)
                    .isBetween(1, slotType.getMaxStayMinutes());
            });

            // 빈 시간이 없다 = 다음 장소는 앞 장소가 끝난 뒤에 시작한다. 탄력 체류가 대기가 아니라
            // 체류 확대로 구현됐다는 것의 직접 확인이다(표시 시각이 5분 올림이라 등호로는 못 쓴다).
            List<AiCoursePlace> places = day.places();
            for (int i = 0; i < places.size() - 1; i++) {
                assertThat(places.get(i + 1).startTime())
                    .as("day %d 의 %d번째 → %d번째 순서", day.day(), i, i + 1)
                    .isAfter(places.get(i).startTime());
            }
        });
    }

    /** 하루를 사람이 읽을 수 있게 덤프한다. 이슈의 관측("오후 1~4시 종료")과 같은 축이다. */
    private static void print(String region, AiCourseDraft draft, long elapsedMs) {
        System.out.printf("%n=== [%s] %s (%dms) ===%n", region, draft.title(), elapsedMs);
        System.out.printf("  컨셉: %s%n", draft.concept());

        for (AiCourseDay day : draft.days()) {
            System.out.printf("%n  day %d — %s ~ %s (슬롯 %d개, 식사 %d회)%n",
                day.day(), day.startTime(), day.endTime(), day.places().size(), mealCount(day));

            for (AiCoursePlace place : day.places()) {
                System.out.printf("    %s  %-10s %-24s 체류 %3d분  (%s)%n",
                    place.startTime(),
                    place.place().slotType(),
                    truncate(place.place().name()),
                    place.stayMinutes(),
                    place.place().source());
            }
        }
    }

    /**
     * 이슈가 물은 것에 대한 답을 한 화면에 모은다 — 종료 시각 분포, 슬롯 수, 식사 횟수,
     * 저녁 식사가 시간창에 들어온 비율.
     */
    private static void summarize(List<AiCourseDay> days) {
        System.out.printf("%n=== 하루의 모양 요약 (day %d개) ===%n", days.size());

        long eveningEnd = days.stream().filter(d -> d.endTime().isAfter(LocalTime.of(17, 0))).count();
        long fiveSlots = days.stream().filter(d -> d.places().size() >= 5).count();
        long twoMeals = days.stream().filter(d -> mealCount(d) >= 2).count();
        long dinnerInWindow = days.stream().filter(AiCourseDayShapeProbeTest::hasDinnerInWindow).count();

        System.out.printf("  17시 이후 종료 : %d/%d%n", eveningEnd, days.size());
        System.out.printf("  슬롯 5개 이상  : %d/%d%n", fiveSlots, days.size());
        System.out.printf("  식사 2회 이상  : %d/%d%n", twoMeals, days.size());
        System.out.printf("  저녁이 창 안   : %d/%d  (%s~%s)%n",
            dinnerInWindow, days.size(), DINNER_START, DINNER_END);

        System.out.println("  종료 시각 목록 : " + days.stream()
            .map(d -> "day" + d.day() + " " + d.endTime())
            .toList());
    }

    private static long mealCount(AiCourseDay day) {
        return day.places().stream().filter(p -> p.place().slotType() == SlotType.MEAL).count();
    }

    /** 마지막 식사가 저녁 시간창 안에서 시작하는가. */
    private static boolean hasDinnerInWindow(AiCourseDay day) {
        return day.places().stream()
            .filter(p -> p.place().slotType() == SlotType.MEAL)
            .map(AiCoursePlace::startTime)
            .reduce((first, second) -> second)
            .filter(t -> !t.isBefore(DINNER_START) && !t.isAfter(DINNER_END))
            .isPresent();
    }

    private static String truncate(String name) {
        return name.length() <= 24 ? name : name.substring(0, 23) + "…";
    }

    // ── 배선 — Spring 컨텍스트를 쓰지 않고 손으로 조립한다 (AgentProbeTest 와 같은 방식) ──

    private static AiCoursePipeline pipeline(String openAiKey, String naverId, String naverSecret,
        String tourKey, String kakaoKey) {

        AiCourseMetrics metrics = new AiCourseMetrics(new SimpleMeterRegistry());
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
                new NaverLocalSeedSource(naverClient, metrics), new TourApiSource(tourClient), metrics,
                Runnable::run),
            new CuratorAgent(llmClient, promptLoader, metrics, Runnable::run),
            new GroundingStage(kakaoClient, metrics, Runnable::run),
            new RouteOptimizer(),
            new PlaceUrlEnricher(kakaoClient, metrics, Runnable::run),
            metrics,
            // 예산을 넉넉히 준다 — 여기서 재려는 것은 지연이 아니라 하루의 모양이라,
            // 데드라인에 걸려 단계가 잘리면 관측 대상 자체가 사라진다.
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
}
