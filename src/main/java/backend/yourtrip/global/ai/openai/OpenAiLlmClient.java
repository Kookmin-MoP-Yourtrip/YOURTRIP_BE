package backend.yourtrip.global.ai.openai;

import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.LlmCall;
import backend.yourtrip.global.ai.LlmClient;
import backend.yourtrip.global.ai.LlmResponseParser;
import backend.yourtrip.global.ai.LlmRetryExecutor;
import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.exception.LlmResponseException;
import backend.yourtrip.global.ai.exception.LlmTransportException;
import backend.yourtrip.global.ai.exception.LlmTruncatedResponseException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.openai.api.ResponseFormat.JsonSchema;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/**
 * {@link LlmClient}의 OpenAI 어댑터 — <b>Spring AI가 등장하는 유일한 프로덕션 파일이다.</b>
 *
 * <p>이 경계를 지키는 것이 설계의 핵심이다. 에이전트(6·9단계)와 파이프라인(7단계)은
 * {@code LlmClient}·{@code LlmCall}만 알고, 여기서 벤더가 바뀌어도 그 위쪽 코드는 한 줄도
 * 바뀌지 않는다. {@code LlmPortIsolationTest}가 이 규칙을 테스트로 강제한다.
 *
 * <p>설계 근거: {@code docs/tasks/ai-course-create/design/LLM-연동.md} "벤더 중립 LLM 추상화"
 *
 * <h2>Spring AI를 auto-config로 쓰지 않는 이유</h2>
 * {@code spring.ai.model.chat: openai}로 켜면 세 가지가 걸린다 — ① {@code api-key}가 기동 필수가
 * 되어 키 없는 환경에서 부팅이 깨지고 ② agent마다 model·temperature·스키마가 달라
 * {@code defaultOptions} 하나로 표현되지 않으며 ③ {@code baseUrl}을 갈아끼울 수 없어 WireMock으로
 * 어댑터를 검증할 수 없다. 그래서 조립을 여기서 직접 한다.
 *
 * <h2>재시도 2계층</h2>
 * <pre>
 * generate(call)
 *   └ 의미 계층: 최대 2회 (초회 + 보정 1회)
 *       └ 전송 계층: LlmRetryExecutor 가 429/5xx 를 지수 백오프 + 지터로 재시도
 *           └ HTTP 호출
 *       finish_reason 확인  → 비정상이면 LlmTruncatedResponseException
 *       LlmResponseParser   → 실패면 LlmParseException
 * </pre>
 * 의미 계층을 여기 두는 이유는 <b>재시도하려면 프롬프트를 다시 조립해야 하기 때문</b>이다.
 * 전송 계층만 {@link LlmRetryExecutor}로 떼어낸 것은 백오프 계산을 순수 함수로 테스트하기 위해서다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
public class OpenAiLlmClient implements LlmClient {

    /** OpenAI가 정상 종료를 알리는 값. 그 외(length·content_filter 등)는 전부 절단으로 다룬다. */
    private static final String FINISH_REASON_STOP = "stop";

    /**
     * 응답 스키마의 이름. OpenAI가 요구하는 필수 필드지만 모델 동작에는 영향을 주지 않는다.
     * agentName을 넣으면 스키마 이름이 곧 어느 에이전트의 것인지를 말해준다.
     */
    private static final String SCHEMA_NAME_PREFIX = "yourtrip_";

    /**
     * 의미 재시도 시 덧붙이는 보정 지시. <b>"다시 해줘"가 아니라 무엇이 틀렸는지를 말한다</b> —
     * 같은 요청을 그대로 되풀이하면 같은 응답이 온다.
     */
    private static final String CORRECTION_INSTRUCTION = """

        [재시도] 직전 응답이 유효한 JSON이 아니었거나 스키마를 위반했다.
        지정된 스키마에 정확히 맞는 JSON만 출력하라. 설명 문장이나 코드블록 표시를 덧붙이지 마라.""";

    /** 보정 재시도에서 쓰는 온도. 일관성을 최대로 올려 형식 오류를 반복하지 않게 한다. */
    private static final double CORRECTION_TEMPERATURE = 0.0;

    private final AiLlmProperties properties;
    private final LlmResponseParser responseParser;
    private final LlmRetryExecutor retryExecutor;
    private final OpenAiChatModel chatModel;
    private final AiCourseMetrics metrics;
    private final Semaphore concurrencyGate;

    /**
     * 생성자가 둘이라 주입 대상을 명시한다. 표시하지 않으면 Spring이 기본 생성자를 찾다가
     * 컨텍스트가 통째로 깨진다.
     */
    @Autowired
    public OpenAiLlmClient(AiLlmProperties properties, LlmResponseParser responseParser,
        LlmRetryExecutor retryExecutor, AiCourseMetrics metrics) {
        this(properties, responseParser, retryExecutor, metrics,
            buildChatModel(properties.openai().baseUrl(), properties.openai().apiKey(),
                properties.timeoutMs()));
    }

    /**
     * 테스트·벤치마크 하네스가 조립한 {@code OpenAiChatModel}을 직접 넣기 위한 생성자.
     *
     * <p>하네스({@code AiHallucinationBaselineTest})는 Spring 컨텍스트를 띄우지 않는다 —
     * 컨텍스트를 띄우면 DB/Redis가 필요해져 측정의 초점이 흐려지기 때문이다.
     * {@code KakaoConfig.buildKakaoWebClient}가 같은 이유로 만든 선례를 따른다.
     */
    public OpenAiLlmClient(AiLlmProperties properties, LlmResponseParser responseParser,
        LlmRetryExecutor retryExecutor, AiCourseMetrics metrics, OpenAiChatModel chatModel) {
        this.properties = properties;
        this.responseParser = responseParser;
        this.retryExecutor = retryExecutor;
        this.metrics = metrics;
        this.chatModel = chatModel;
        this.concurrencyGate = new Semaphore(properties.maxConcurrentCalls());

        // 어느 모델이 살아 있는지를 기동 로그에서 바로 볼 수 있게 한다. 이 어댑터는 조건부 빈이라
        // 기동 성공만으로는 활성 여부가 드러나지 않고, agent별 모델은 설정 파일을 열어야 알 수 있다.
        // API 키는 찍지 않는다.
        log.info("LLM 어댑터 활성화: provider={}, timeoutMs={}, maxConcurrentCalls={}, agents={}",
            properties.provider(), properties.timeoutMs(), properties.maxConcurrentCalls(),
            properties.agents().entrySet().stream()
                .map(entry -> "%s=%s(t=%s)".formatted(
                    entry.getKey(), entry.getValue().model(), entry.getValue().temperature()))
                .toList());
    }

    /**
     * 프로덕션과 동일한 설정으로 {@code OpenAiChatModel}을 조립한다.
     *
     * <p><b>{@code retryTemplate}을 1회로 고정하는 것이 중요하다.</b> Spring AI는 기본으로
     * 자체 RetryTemplate(다중 시도 + 자체 백오프)을 붙이는데, 그대로 두면 우리
     * {@link LlmRetryExecutor}와 곱해져 실제 시도 횟수가 설정값과 전혀 달라진다
     * ({@code llm.retry.attempts: 3}이 사실상 수십 회가 된다). 재시도 정책은 한 곳에만 있어야 한다.
     */
    public static OpenAiChatModel buildChatModel(String baseUrl, String apiKey, int timeoutMs) {
        Duration timeout = Duration.ofMillis(timeoutMs);

        // detect()가 아니라 reactor()로 못박는다.
        //
        // detect()는 클래스패스를 보고 HTTP 스택을 고르는데, 이 레포에는 Apache HttpClient 5가
        // 다른 의존성에 딸려 (선언되지 않은 채) 올라와 있어 그쪽이 선택된다. 문제는 Apache의 기본
        // 재시도 전략(DefaultHttpRequestRetryStrategy)이 429·503을 자체적으로 한 번 더 시도한다는
        // 것이다 — WireMock 테스트에서 llm.retry.attempts: 3 이 실제 HTTP 요청 6건으로 관측됐다.
        // 재시도가 여러 계층에 흩어지면 설정값이 실제 시도 횟수를 설명하지 못하고, rate limit
        // 상황에서는 그 곱셈이 문제를 키운다.
        //
        // reactor-netty를 고르는 이유는 두 가지다. ① 상태 코드 기반 자동 재시도가 없어 재시도
        // 정책이 llm.retry 한 곳에만 존재한다 ② 이 레포의 다른 외부 호출(KakaoConfig)이 이미
        // reactor-netty라 HTTP 스택이 하나로 모인다. Apache는 선언조차 되지 않은 전이 의존성이라
        // 거기 기대는 것 자체가 취약하다.
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.reactor()
            .build(ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(timeout)
                .withReadTimeout(timeout));

        OpenAiApi api = OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey == null ? "" : apiKey)
            .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
            .responseErrorHandler(new OpenAiErrorHandler())
            .build();

        return OpenAiChatModel.builder()
            .openAiApi(api)
            // 빈 defaultOptions 로 덮는다. Spring AI의 Builder는 defaultOptions 를
            // temperature 0.7 로 초기화해두는데, 호출마다 옵션을 만들어 넘겨도 우리가 지정하지
            // 않은 필드는 이 기본값이 병합돼 실려 나간다 — gpt-5 계열은 커스텀 온도를 400으로
            // 거부하므로, "안 보내는 것"이 실제로 안 보내지려면 기본값 자체를 비워야 한다.
            .defaultOptions(OpenAiChatOptions.builder().build())
            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
            .build();
    }

    @Override
    public <T> T generate(LlmCall<T> call) {
        acquirePermit(call.agentName());
        // 세마포어 대기는 지연에 넣지 않는다 — 그건 모델이 아니라 우리가 건 동시성 상한이 만든
        // 대기이고, 섞으면 "모델이 느려졌다"와 "티어가 좁다"를 구분할 수 없다.
        long startedAt = System.nanoTime();
        String outcome = AiCourseMetrics.LLM_OUTCOME_ERROR;
        try {
            T result = generateWithSemanticRetry(call);
            outcome = AiCourseMetrics.LLM_OUTCOME_SUCCESS;
            return result;
        } catch (LlmTruncatedResponseException e) {
            // 절단은 따로 센다 — 2-6이 JSON 실패의 진짜 원인으로 지목한 사건이라
            // "스키마 위반"과 뭉치면 그 결론을 다시 확인할 수 없다.
            outcome = AiCourseMetrics.LLM_OUTCOME_TRUNCATED;
            throw e;
        } catch (LlmResponseException e) {
            outcome = AiCourseMetrics.LLM_OUTCOME_RESPONSE_ERROR;
            throw e;
        } catch (LlmTransportException e) {
            outcome = AiCourseMetrics.LLM_OUTCOME_TRANSPORT_ERROR;
            throw e;
        } finally {
            concurrencyGate.release();
            metrics.llmCall(call.agentName(), properties.provider(), outcome,
                System.nanoTime() - startedAt);
        }
    }

    @Override
    public <T> CompletableFuture<T> generateAsync(LlmCall<T> call, Executor executor) {
        return CompletableFuture.supplyAsync(() -> generate(call), executor);
    }

    /**
     * 의미 계층 — 200 OK인데 쓸 수 없는 응답이면 보정 지시를 붙여 다시 부른다.
     *
     * <p>기본값은 총 2회(초회 + 보정 1회)다. 3회 이상은 지연 예산만 태운다는 것이 LLM 포트 설계의
     * 판단이며, {@code llm.retry.semantic-attempts}로 조절한다.
     */
    private <T> T generateWithSemanticRetry(LlmCall<T> call) {
        int attempts = Math.max(1, properties.retry().semanticAttempts());
        LlmResponseException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return callOnce(call, attempt > 1);
            } catch (LlmResponseException e) {
                last = e;
                if (attempt < attempts) {
                    log.warn("LLM 응답이 유효하지 않아 보정 재시도한다({}/{}). agent={}, cause={}",
                        attempt, attempts - 1, call.agentName(), e.getMessage());
                }
            }
        }
        throw last;
    }

    private <T> T callOnce(LlmCall<T> call, boolean correcting) {
        Prompt prompt = buildPrompt(call, correcting);
        ChatResponse response = callTransport(call.agentName(), prompt);

        Generation generation = requireGeneration(call, response);
        String finishReason = generation.getMetadata().getFinishReason();
        String text = generation.getOutput().getText();

        // 파싱보다 먼저 확인한다. 잘린 JSON을 파싱하면 원인이 "스키마 위반"으로 오분류되고,
        // 그게 정확히 Gemini baseline에서 파싱 실패율의 원인을 몇 달 놓친 경위다.
        if (!isNormalFinish(finishReason)) {
            throw new LlmTruncatedResponseException(call.agentName(), finishReason, text);
        }

        return responseParser.parse(call.agentName(), text, call.responseType());
    }

    private <T> Prompt buildPrompt(LlmCall<T> call, boolean correcting) {
        List<Message> messages = new ArrayList<>();
        if (call.systemInstruction() != null && !call.systemInstruction().isBlank()) {
            messages.add(new SystemMessage(call.systemInstruction()));
        }
        messages.add(new UserMessage(
            correcting ? call.userPrompt() + CORRECTION_INSTRUCTION : call.userPrompt()));

        return new Prompt(messages, buildOptions(call, correcting));
    }

    /**
     * agent별 설정을 <b>호출마다</b> 옵션으로 만든다. {@code defaultOptions}에 한 번 박아둘 수 없는
     * 이유는 model·temperature·응답 스키마가 에이전트마다 다르기 때문이다.
     */
    private <T> OpenAiChatOptions buildOptions(LlmCall<T> call, boolean correcting) {
        AiLlmProperties.Agent agent = properties.agent(call.agentName());

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
            .model(agent.model())
            // max_tokens 가 아니라 max_completion_tokens 다 — 추론 계열 모델은 추론 토큰이
            // 출력에 포함되므로 이쪽이 실제 상한을 가리킨다.
            .maxCompletionTokens(agent.maxOutputTokens())
            .responseFormat(buildResponseFormat(call));

        // 온도를 지원하지 않는 모델이 있다 — gpt-5 계열은 커스텀 값을 400으로 거부한다.
        // 설정이 비어 있으면 필드를 아예 싣지 않는다. 보정 재시도에서도 마찬가지다:
        // "일관성을 위해 0으로 내린다"는 것도 값을 보내는 행위라 똑같이 거부된다.
        if (agent.temperature() != null) {
            options.temperature(correcting ? CORRECTION_TEMPERATURE : agent.temperature());
        }
        // 추론 강도. LLM 포트 설계가 Gemini 전용 thinking-budget 을 제거하며 "대응 설정이 있다면
        // 어댑터에서 다룬다"고 열어둔 자리다. 낮추지 않으면 max-output-tokens 를 추론이 다 먹고
        // 본문이 0바이트로 오는 일이 실제로 발생한다.
        if (agent.reasoningEffort() != null && !agent.reasoningEffort().isBlank()) {
            options.reasoningEffort(agent.reasoningEffort());
        }
        return options.build();
    }

    /**
     * 스키마를 <b>프롬프트가 아니라 API 필드로</b> 보낸다 — 이것이 파싱 실패를 디코딩 레벨에서
     * 막는 지점이고, 0단계에서 WireMock으로 실제 전송 위치를 확인한 근거이기도 하다.
     *
     * <p>{@code responseJsonSchema}가 없으면 스키마 없는 JSON 모드로 떨어진다. 2-6 baseline
     * 재측정이 "구조화 출력을 켜지 않은 OpenAI" 측정점을 필요로 하기 때문이다.
     */
    private <T> ResponseFormat buildResponseFormat(LlmCall<T> call) {
        if (!call.hasJsonSchema()) {
            return ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();
        }
        return ResponseFormat.builder()
            .type(ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(JsonSchema.builder()
                .name(SCHEMA_NAME_PREFIX + call.agentName().replace('-', '_'))
                .schema(call.responseJsonSchema())
                .strict(true)
                .build())
            .build();
    }

    /**
     * 전송 계층 호출 — 재시도를 걸고, <b>어떤 실패가 나오든 우리 예외 타입으로 번역해서</b> 내보낸다.
     *
     * <p>번역을 여기서 마무리하는 이유는 포트의 계약 때문이다. 여기서 벤더 예외가 하나라도 새면
     * 위쪽 파이프라인이 그걸 잡으려고 Spring AI 타입을 import하게 되고, 그 순간 포트가
     * 무의미해진다.
     */
    private ChatResponse callTransport(String agentName, Prompt prompt) {
        try {
            return retryExecutor.execute(agentName, () -> chatModel.call(prompt),
                OpenAiLlmClient::isRetriable);
        } catch (LlmTransportException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmTransportException(agentName, 1,
                "LLM 호출이 재시도 대상이 아닌 오류로 실패했다: " + e.getMessage(), e);
        }
    }

    /**
     * 재시도 대상 판정 — <b>벤더 오류 모양을 아는 유일한 지점</b>이라 어댑터에 있다.
     *
     * <p><b>Spring AI의 기본 분류를 쓰지 않는다.</b> {@code RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER}는
     * 4xx 전체를 {@link org.springframework.ai.retry.NonTransientAiException}(재시도 무의미)으로,
     * 5xx만 {@link TransientAiException}으로 나눈다. 그런데 <b>429가 4xx라 rate limit이
     * "다시 시도해도 소용없음"으로 분류된다</b> — 실제로는 정확히 그 반대다. 이 저장소의 WireMock
     * 테스트가 {@code NonTransientAiException: 429 -} 를 관측해 확인했다.
     *
     * <p>그래서 {@link OpenAiErrorHandler}로 분류를 직접 소유한다. 메시지 문자열에서 "429"를 찾는
     * 방식(기존 벤치마크 하네스가 쓰던 방법)은 벤더가 문구를 바꾸면 조용히 깨지므로 택하지 않았다.
     */
    private static boolean isRetriable(RuntimeException e) {
        for (Throwable current = e; current != null && current != current.getCause();
            current = current.getCause()) {
            if (current instanceof OpenAiHttpException http) {
                return http.isRetriable();
            }
            // 연결 실패·읽기 타임아웃은 상대 상태와 무관하게 다시 시도할 가치가 있다.
            if (current instanceof java.io.IOException) {
                return true;
            }
            // 우리가 핸들러를 못 끼운 경로로 5xx가 올라온 경우의 안전망.
            if (current instanceof TransientAiException) {
                return true;
            }
        }
        return false;
    }

    /**
     * HTTP 오류 응답을 상태 코드가 살아 있는 예외로 바꾼다.
     *
     * <p>Spring AI 기본 핸들러는 상태 코드를 메시지 문자열에만 남기고 타입에서는 지워버린다
     * ({@code "429 - ..."}). 그러면 재시도 판정이 문자열 파싱이 될 수밖에 없다.
     */
    static final class OpenAiErrorHandler implements ResponseErrorHandler {

        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return response.getStatusCode().isError();
        }

        /**
         * 인자 없는 {@code handleError(ClientHttpResponse)} 오버로드는 제거 예정이라 쓰지 않는다.
         * 대신 URI·메서드를 받는 쪽을 구현해, 실패했을 때 어느 엔드포인트였는지도 함께 남긴다.
         */
        @Override
        public void handleError(URI url, HttpMethod method, ClientHttpResponse response)
            throws IOException {
            int status = response.getStatusCode().value();
            String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
            throw new OpenAiHttpException(status, "%s %s -> %s".formatted(method, url, body));
        }
    }

    /** 상태 코드를 보존하는 전송 오류. 패키지 안에서만 쓰인다. */
    static final class OpenAiHttpException extends RuntimeException {

        private final int status;

        OpenAiHttpException(int status, String body) {
            super("OpenAI가 %d를 반환했다: %s".formatted(status, body));
            this.status = status;
        }

        /**
         * 429(rate limit) · 408(요청 타임아웃) · 5xx만 재시도한다.
         *
         * <p>400(스키마 오류)·401(키 오류)·404는 같은 요청을 다시 보내도 같은 답이 온다 —
         * 백오프를 태우면 오류를 늦게 볼 뿐이다.
         */
        boolean isRetriable() {
            return status == 429 || status == 408 || status >= 500;
        }
    }

    /**
     * {@code max-concurrent-calls} 게이트.
     *
     * <p>무한 대기하지 않고 {@code timeout-ms}에서 잘라내는 이유는, 앞선 호출들이 전부 느려졌을 때
     * 뒤에 쌓인 요청이 <b>호출도 못 해보고 스레드만 붙잡는</b> 상태를 막기 위해서다. 이 저장소는
     * 커넥션이 오래 묶이는 것이 어떤 결과를 내는지 이미 실측한 이력이 있다.
     */
    private void acquirePermit(String agentName) {
        try {
            if (!concurrencyGate.tryAcquire(properties.timeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new LlmTransportException(agentName, 0,
                    "LLM 동시 호출 슬롯(%d)을 %dms 안에 얻지 못했다"
                        .formatted(properties.maxConcurrentCalls(), properties.timeoutMs()), null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmTransportException(agentName, 0, "LLM 호출 슬롯 대기 중 인터럽트됐다", e);
        }
    }

    private <T> Generation requireGeneration(LlmCall<T> call, ChatResponse response) {
        Generation generation = response == null ? null : response.getResult();
        if (generation == null || generation.getOutput() == null) {
            throw new LlmTruncatedResponseException(call.agentName(), "no_choice", "");
        }
        return generation;
    }

    private static boolean isNormalFinish(String finishReason) {
        // 스텁이나 일부 게이트웨이가 finish_reason 을 아예 안 주는 경우가 있다. 값이 없는 것을
        // 절단으로 단정하면 정상 응답을 버리게 되므로, 명시적으로 다른 값일 때만 절단으로 본다.
        return finishReason == null
            || finishReason.isBlank()
            || FINISH_REASON_STOP.equalsIgnoreCase(finishReason);
    }
}
