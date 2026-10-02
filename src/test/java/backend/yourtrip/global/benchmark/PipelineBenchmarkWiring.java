package backend.yourtrip.global.benchmark;

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
import backend.yourtrip.global.ai.pipeline.AiCoursePipeline;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.route.RouteOptimizer;
import backend.yourtrip.global.config.AsyncConfig;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.config.NaverConfig;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.config.TourApiConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 측정 하네스들이 공유하는 <b>파이프라인 손조립 배선</b>.
 *
 * <p>원래 {@link AiPipelineHallucinationBenchmarkTest} 안에 {@code private}으로 있었다. 로드맵 2단계
 * 지연 기준선({@link AiCourseLatencyBaselineTest})이 같은 배선을 써야 해서 여기로 뗐다.
 * <b>복사하지 않은 이유</b>는 {@link BaselineInputSet}을 뗄 때와 같다 — 하네스마다 사본을 두면
 * 운영 배선이 바뀔 때 한쪽만 구식이 되고, 두 측정값이 서로 다른 파이프라인을 잰 것이 되는데
 * 그 사실이 산출물에 드러나지 않는다.
 *
 * <p><b>측정마다 달라지는 값만 인자로 받는다</b> — 요청 예산, LLM 호출 상한, 동시 호출 수.
 * 환각률 하네스는 표본이 데드라인에 잘리지 않도록 이 값들을 늘려 쓰고(180초/60초), 지연 기준선은
 * 운영값 그대로 쓴다. 모델·출력 상한·재시도처럼 측정 사이에 같아야 하는 값은 여기 고정한다.
 *
 * <h2>Spring 컨텍스트를 쓰지 않는 이유</h2>
 *
 * <p>{@code test} 프로필은 {@code .env} 없이 자급하도록 설계돼 API 키가 전부 더미이고(CI 가 시크릿
 * 없이 서 있는 전제), 컨텍스트를 띄우면 H2·Redis·JPA 까지 따라와 측정 대상과 무관한 것이 붙는다.
 * 손조립 선례가 {@code AiCourseRouteInputProbeTest}·{@code AiCourseDayShapeProbeTest} 둘 있다.
 *
 * <p><b>카카오 클라이언트는 밖에서 만들어 넣는다</b> — 파이프라인과 채점기가 <b>같은 인스턴스</b>를
 * 써야 커넥션 풀과 타임아웃 조건이 같아진다.
 *
 * <h2>실행기는 운영 것을 쓴다 — 다른 프로브와 갈리는 지점</h2>
 *
 * <p>3-7·day-shape 프로브는 {@code Executor} 자리에 {@code Runnable::run}(동기 실행)을 넣는다.
 * 그쪽이 재는 것은 동선과 day 모양이라 실행 방식이 결과를 바꾸지 않기 때문이다.
 *
 * <p><b>지연을 재는 하네스에서 그렇게 하면 측정이 무효가 된다.</b> 파이프라인의 병렬 지점(day별
 * Curator, 슬롯별 후보 공급, 후보별 그라운딩)이 전부 순차로 퇴화해 운영보다 구조적으로 느려진다 —
 * 스모크에서 41.9초 / 38.0초가 나왔는데 E2E 실측(운영 배선)은 22.6초 / 28.0초였다.
 *
 * <p>그래서 호출자가 {@link AsyncConfig}를 직접 인스턴스화해 <b>운영과 같은 풀 설정</b>(aiAgent
 * core 4 / placeGrounding core 8, 둘 다 {@code CallerRunsPolicy})을 넘긴다. Spring 컨텍스트 없이도
 * 그 메서드는 평범한 팩토리라 그대로 부를 수 있고, 설정이 바뀌면 하네스가 자동으로 따라간다 —
 * 값을 복사하면 운영만 바뀌고 벤치마크는 조용히 구식이 된다.
 */
final class PipelineBenchmarkWiring {

    private PipelineBenchmarkWiring() {
    }

    /** 외부 API 자격 증명. 카카오는 클라이언트째로 받으므로 여기 없다. */
    record ApiKeys(String openAi, String naverId, String naverSecret, String tour) {}

    /**
     * 측정마다 달라지는 한도.
     *
     * @param budgetMs           요청 예산({@code ai.course.budget-ms})
     * @param llmTimeoutMs       슬롯 대기와 HTTP 시도 1회의 상한({@code llm.timeout-ms})
     * @param maxConcurrentCalls 서버 전체 LLM 동시 호출 슬롯({@code llm.max-concurrent-calls})
     */
    record Limits(int budgetMs, int llmTimeoutMs, int maxConcurrentCalls) {}

    static AiCoursePipeline pipeline(MeterRegistry registry, KakaoLocalClient kakaoClient,
        ThreadPoolTaskExecutor agentExecutor, ThreadPoolTaskExecutor groundingExecutor,
        ApiKeys keys, Limits limits) {

        AiCourseMetrics metrics = new AiCourseMetrics(registry);
        AiLlmProperties properties = llmProperties(keys.openAi(), limits);
        OpenAiLlmClient llmClient = new OpenAiLlmClient(properties,
            new LlmResponseParser(new ObjectMapper()), new LlmRetryExecutor(properties), metrics,
            OpenAiLlmClient.buildChatModel(properties.openai().baseUrl(), keys.openAi(),
                properties.timeoutMs()));

        PromptLoader promptLoader = new PromptLoader();
        NaverLocalClient naverClient = new NaverLocalClient(NaverConfig.buildNaverWebClient(
            "https://naverapihub.apigw.ntruss.com", keys.naverId(), keys.naverSecret()));
        TourApiClient tourClient = new TourApiClient(TourApiConfig.buildTourApiWebClient(
            "https://apis.data.go.kr/B551011/KorService2"), keys.tour());

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
            new AiCourseProperties(limits.budgetMs()));
    }

    /** 운영 설정({@code application.yml})과 같은 모델·추론 강도·재시도를 쓴다 — 3-7 과 같은 값이다. */
    private static AiLlmProperties llmProperties(String apiKey, Limits limits) {
        return new AiLlmProperties(
            "openai",
            limits.llmTimeoutMs(),
            limits.maxConcurrentCalls(),
            new AiLlmProperties.Retry(3, 2, 0.5, 4.0, 0.3),
            Map.of(
                PlannerAgent.AGENT_NAME,
                new AiLlmProperties.Agent("gpt-5.6-luna", null, 2048, null),
                CuratorAgent.AGENT_NAME,
                new AiLlmProperties.Agent("gpt-5.6-luna", null, 4096, "low")),
            new AiLlmProperties.OpenAi(apiKey, "https://api.openai.com"));
    }
}
