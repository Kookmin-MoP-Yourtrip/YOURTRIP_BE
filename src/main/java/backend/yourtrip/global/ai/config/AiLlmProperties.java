package backend.yourtrip.global.ai.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * LLM 호출 설정. <b>이 저장소 최초의 {@code @ConfigurationProperties}다</b> — 지금까지 모든 설정이
 * {@code @Value} 필드 주입이었다.
 *
 * <p>여기서 방식을 바꾸는 이유는 취향이 아니라 <b>구조 때문</b>이다. LLM 설정은
 * {@code agents.planner.model}처럼 <b>2단계 중첩 + 키가 열려 있는 맵</b>이라 {@code @Value}로는
 * 표현 자체가 안 된다. 게다가 {@code @Value}는 오타를 런타임에야 드러내지만, 여기서는
 * {@code @Validated}가 <b>기동 시점에</b> 잡는다 — 모델 ID 오타 하나로 요청 30건을 날리고 나서
 * 알게 되는 것과 부팅이 실패하는 것의 차이다.
 *
 * <p>설계 근거: {@code docs/tasks/ai-course-create/TASK-AI-MULTI-AGENT.md} §6 "설정 외부화"
 *
 * @param provider           어댑터 선택 키. OpenAI로 확정됐지만 {@code @ConditionalOnProperty}
 *                           구조는 유지한다 — 값을 비우면 어댑터 빈이 아예 뜨지 않으므로,
 *                           API 키 없는 환경에서 기동을 살리는 탈출구도 된다
 * @param timeoutMs          호출 1건의 상한. 세마포어 대기 시간도 여기서 함께 잘라낸다
 * @param maxConcurrentCalls <b>rate limit 대응의 전부다.</b> 2로 두면 day별 Curator 3개가
 *                           2라운드로 나뉘어 실행된다(+3~6초). 429가 나는 대신 느려질 뿐이고,
 *                           티어 상향은 이 값 한 줄이다. "Curator를 day별 병렬로 만들지 1회
 *                           통합으로 만들지"를 코드 구조로 결정하지 않는 것이 요점이다
 * @param retry              전송 계층 재시도(429/5xx). 의미 계층 재시도는 1회로 고정이라
 *                           설정 대상이 아니다
 * @param agents             에이전트별 모델·온도·출력 상한. 키는 {@code LlmCall.agentName}과
 *                           일치한다
 * @param openai             OpenAI 어댑터 전용 설정. 벤더 종속 항목을 여기 가둬 위쪽을
 *                           중립으로 유지한다
 */
@Validated
@ConfigurationProperties(prefix = "llm")
public record AiLlmProperties(

    @NotBlank
    String provider,

    @Positive
    int timeoutMs,

    @Positive
    int maxConcurrentCalls,

    @NotNull @Valid
    Retry retry,

    @NotEmpty
    Map<String, @Valid Agent> agents,

    @NotNull @Valid
    OpenAi openai
) {

    /**
     * 전송 계층 재시도 설정.
     *
     * <p>{@code resilience4j}를 추가하지 않는 이유는 설계 문서 §6에 있다 — 지수 백오프는 이
     * 정도로 충분하고, 서킷브레이커를 얹으면 LLM 장애 시 "코스를 아예 못 만드는" 상태가 되는데
     * 파이프라인의 폴백 전략이 어차피 에이전트 실패를 개별 흡수한다.
     *
     * @param attempts            초회를 포함한 총 시도 횟수
     * @param initialDelaySeconds 첫 백오프
     * @param maxDelaySeconds     백오프 상한
     * @param jitter              지연에 곱해지는 무작위 폭(0.3이면 ±30%). <b>여러 요청이 429를
     *                            동시에 맞았을 때 같은 시각에 재시도가 몰리는 것</b>을 흩는다
     */
    public record Retry(
        @Positive int attempts,
        @Positive double initialDelaySeconds,
        @Positive double maxDelaySeconds,
        @PositiveOrZero double jitter
    ) {}

    /**
     * 에이전트 1개의 모델 파라미터.
     *
     * <p><b>agent별로 나누는 것이 이 설정의 핵심</b>이다. 현재 코드의 단일 {@code temperature 0.3}은
     * "장소 선정은 다양해야 하고 판정은 일관돼야 한다"는 상충 요구를 하나로 뭉갠 값이다.
     * 모델도 같은 논리로 나눈다 — 셋 중 추론 이득이 실제로 있는 건 Planner(컨셉·권역 설계)뿐이고,
     * Curator·PlaceProfile은 이득이 적으면서 토큰 비중은 가장 크다.
     *
     * @param maxOutputTokens 출력 상한. <b>설계 문서 §6에는 없던 항목을 추가했다</b> —
     *                        Gemini baseline의 파싱 실패 5건이 전부 응답 절단이었고
     *                        ({@code BASELINE-ARTIFACT-ANALYSIS.md} 판정 3), 절단은 구조화
     *                        출력으로 막히지 않으므로 출력 여유를 설정으로 다룰 수 있어야 한다
     */
    public record Agent(
        @NotBlank String model,
        @NotNull Double temperature,
        @Positive Integer maxOutputTokens
    ) {}

    /**
     * @param apiKey  비어 있어도 기동은 성공한다. 2단계는 이 어댑터를 프로덕션 경로에서 호출하지
     *                않으므로(8단계 스위치 전까지), 키가 없는 환경에서 부팅이 깨지면 "동작 변화
     *                없음"이라는 이 단계의 전제가 깨진다
     * @param baseUrl WireMock을 가리키게 바꿔 어댑터를 실제 호출 없이 테스트하기 위한 확장점
     */
    public record OpenAi(
        String apiKey,
        @NotBlank String baseUrl
    ) {}

    /**
     * {@code agentName}으로 설정을 찾는다.
     *
     * <p>없는 키를 조용히 기본값으로 대체하지 않는다 — 오타 하나가 "왜 이 에이전트만 이상하지"라는
     * 형태로 나타나면 추적 비용이 크다. 사용 가능한 키를 메시지에 실어 즉시 드러낸다.
     */
    public Agent agent(String agentName) {
        Agent agent = agents.get(agentName);
        if (agent == null) {
            throw new IllegalArgumentException(
                "llm.agents 에 '" + agentName + "' 설정이 없다. 사용 가능한 키: " + agents.keySet());
        }
        return agent;
    }
}
