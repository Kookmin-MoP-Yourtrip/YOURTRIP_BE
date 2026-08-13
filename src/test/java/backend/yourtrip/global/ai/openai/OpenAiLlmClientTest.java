package backend.yourtrip.global.ai.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import backend.yourtrip.global.ai.LlmCall;
import backend.yourtrip.global.ai.LlmResponseParser;
import backend.yourtrip.global.ai.LlmRetryExecutor;
import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.config.AiLlmProperties.Agent;
import backend.yourtrip.global.ai.config.AiLlmProperties.OpenAi;
import backend.yourtrip.global.ai.config.AiLlmProperties.Retry;
import backend.yourtrip.global.ai.exception.LlmParseException;
import backend.yourtrip.global.ai.exception.LlmTransportException;
import backend.yourtrip.global.ai.exception.LlmTruncatedResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code OpenAiLlmClient} 어댑터 검증 (ROADMAP 2-2).
 *
 * <p>실제 OpenAI를 부르지 않는다 — WireMock을 {@code baseUrl}로 꽂아 <b>요청 본문이 무엇으로
 * 나가는지</b>와 <b>실패 상황에서 어떤 예외로 번역되는지</b>를 본다. 이게 가능한 것 자체가
 * "어댑터가 {@code OpenAiChatModel}을 직접 조립한다"는 결정의 대가로 얻은 것이다
 * (auto-config를 썼다면 baseUrl을 갈아끼울 수 없다).
 *
 * <p>WireMock 라이프사이클은 {@code KakaoLocalClientTest}·
 * {@code SpringAiStructuredOutputVerificationTest}와 같은 형태를 따른다.
 */
@DisplayName("OpenAiLlmClient 어댑터 (ROADMAP 2-2)")
class OpenAiLlmClientTest {

    private static final String COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String AGENT = "planner";

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": { "title": { "type": "string" } },
          "required": ["title"],
          "additionalProperties": false
        }
        """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    record Plan(String title) {}

    private WireMockServer wireMock;

    @BeforeEach
    void startStubServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopStubServer() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Nested
    @DisplayName("요청 조립")
    class RequestShape {

        @Test
        @DisplayName("agent별 model·temperature·출력 상한과 strict 스키마가 API 필드로 나간다")
        void sendsAgentOptionsAndStrictSchema() throws Exception {
            stubSuccess("{\\\"title\\\":\\\"경주 야경 코스\\\"}", "stop");

            Plan plan = client().generate(call(SCHEMA));

            assertThat(plan.title()).isEqualTo("경주 야경 코스");

            JsonNode body = lastRequestBody();
            assertThat(body.path("model").asText()).isEqualTo("gpt-5.6-luna");
            assertThat(body.path("temperature").asDouble()).isEqualTo(0.7);
            // max_tokens 가 아니라 max_completion_tokens 여야 추론 계열 모델에서 실제 상한이 된다.
            assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(2048);

            JsonNode responseFormat = body.path("response_format");
            assertThat(responseFormat.path("type").asText()).isEqualTo("json_schema");
            assertThat(responseFormat.path("json_schema").path("strict").asBoolean()).isTrue();

            // 스키마가 프롬프트로 새면 디코딩 레벨 강제가 아니게 된다 — 0단계 검증과 같은 주장이다.
            assertThat(body.path("messages").toString()).doesNotContain("additionalProperties");
        }

        @Test
        @DisplayName("systemInstruction 은 system 메시지로, userPrompt 는 user 메시지로 분리된다")
        void splitsSystemAndUserMessages() throws Exception {
            stubSuccess("{\\\"title\\\":\\\"t\\\"}", "stop");

            client().generate(new LlmCall<>(AGENT, "너는 여행 플래너다", "경주 3일", Plan.class, SCHEMA));

            JsonNode messages = lastRequestBody().path("messages");
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
            assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
            assertThat(messages.get(1).path("content").asText()).isEqualTo("경주 3일");
        }

        @Test
        @DisplayName("스키마가 없으면 json_object 모드로 떨어진다 — 2-6 재측정의 프롬프트지시 측정점")
        void fallsBackToJsonObjectWithoutSchema() throws Exception {
            stubSuccess("{\\\"title\\\":\\\"t\\\"}", "stop");

            client().generate(call(null));

            assertThat(lastRequestBody().path("response_format").path("type").asText())
                .isEqualTo("json_object");
        }
    }

    @Nested
    @DisplayName("실패 번역")
    class FailureTranslation {

        @Test
        @DisplayName("finish_reason 이 length 면 파싱 전에 절단으로 판정한다")
        void detectsTruncationBeforeParsing() {
            // 잘린 JSON. 파싱을 먼저 하면 LlmParseException 이 나와 원인이 오분류된다.
            stubSuccess("{\\\"title\\\":\\\"경주 야", "length");

            assertThatThrownBy(() -> client().generate(call(SCHEMA)))
                .isInstanceOf(LlmTruncatedResponseException.class)
                // Spring AI는 finish_reason 을 대문자로 정규화해서 준다("length" -> "LENGTH").
                // 어댑터가 대소문자를 무시하고 비교해야 하는 이유가 이것이다.
                .satisfies(thrown -> assertThat(
                    ((LlmTruncatedResponseException) thrown).getFinishReason())
                    .isEqualToIgnoringCase("length"));
        }

        @Test
        @DisplayName("429 가 두 번 온 뒤 200 이면 성공한다 — 전송 계층 재시도")
        void retriesTransientFailures() {
            wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
                .inScenario("rate-limit").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("second"));
            wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
                .inScenario("rate-limit").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("third"));
            wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
                .inScenario("rate-limit").whenScenarioStateIs("third")
                .willReturn(okJson(completion("{\\\"title\\\":\\\"복구됨\\\"}", "stop"))));

            assertThat(client().generate(call(SCHEMA)).title()).isEqualTo("복구됨");
            assertThat(requestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("429 가 계속되면 시도 횟수가 설정값과 정확히 같다 — Spring AI 자체 재시도와 겹치지 않는다")
        void doesNotDoubleRetry() {
            wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
                .willReturn(aResponse().withStatus(429)));

            assertThatThrownBy(() -> client().generate(call(SCHEMA)))
                .isInstanceOf(LlmTransportException.class)
                .satisfies(thrown ->
                    assertThat(((LlmTransportException) thrown).getAttempts()).isEqualTo(3));

            // Spring AI 기본 RetryTemplate 을 1회로 고정하지 않았다면 여기가 수십 회가 된다.
            assertThat(requestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("400 은 재시도하지 않고, 벤더 예외가 아니라 포트 예외로 나온다")
        void doesNotRetryClientErrors() {
            wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
                .willReturn(aResponse().withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":{\"message\":\"invalid schema\"}}")));

            assertThatThrownBy(() -> client().generate(call(SCHEMA)))
                // 벤더 예외가 하나라도 새면 위쪽 파이프라인이 그걸 잡으려고 Spring AI를
                // import하게 되고, 그 순간 포트가 무의미해진다.
                .isInstanceOf(LlmTransportException.class)
                .hasMessageContaining("invalid schema");

            assertThat(requestCount()).as("백오프를 태우지 않아야 한다").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("의미 계층 재시도")
    class SemanticRetry {

        @Test
        @DisplayName("깨진 JSON 이면 보정 지시를 붙여 1회만 다시 부른다")
        void retriesOnceWithCorrection() throws Exception {
            wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
                .inScenario("broken").whenScenarioStateIs("Started")
                .willReturn(okJson(completion("이건 JSON이 아니다", "stop")))
                .willSetStateTo("corrected"));
            wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
                .inScenario("broken").whenScenarioStateIs("corrected")
                .willReturn(okJson(completion("{\\\"title\\\":\\\"고쳐짐\\\"}", "stop"))));

            assertThat(client().generate(call(SCHEMA)).title()).isEqualTo("고쳐짐");
            assertThat(requestCount()).isEqualTo(2);

            JsonNode retryBody = lastRequestBody();
            assertThat(retryBody.path("messages").toString())
                .as("무엇이 틀렸는지를 말해야 한다 — 같은 요청을 되풀이하면 같은 응답이 온다")
                .contains("[재시도]");
            assertThat(retryBody.path("temperature").asDouble())
                .as("보정 시도는 일관성을 최대로 올린다")
                .isEqualTo(0.0);
        }

        @Test
        @DisplayName("두 번 다 깨지면 LlmParseException 으로 끝낸다 — 2회 이상은 지연 예산만 태운다")
        void givesUpAfterOneCorrection() {
            wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
                .willReturn(okJson(completion("여전히 JSON이 아니다", "stop"))));

            assertThatThrownBy(() -> client().generate(call(SCHEMA)))
                .isInstanceOf(LlmParseException.class);

            assertThat(requestCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("semantic-attempts 를 1로 두면 보정 재시도 없이 첫 응답 그대로 끝난다")
        void honoursSemanticAttemptsOne() {
            wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
                .willReturn(okJson(completion("JSON이 아니다", "stop"))));

            OpenAiLlmClient client = clientWith(properties(2, new Retry(3, 1, 0.01, 0.02, 0.0)));

            assertThatThrownBy(() -> client.generate(call(SCHEMA)))
                .isInstanceOf(LlmParseException.class);

            // 2-6 baseline 측정이 이 설정에 의존한다 — 재시도가 실패를 가리면 Gemini 측정값과
            // 같은 자로 잴 수 없다.
            assertThat(requestCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("동시 호출 게이트")
    class ConcurrencyGate {

        @Test
        @DisplayName("max-concurrent-calls 만큼만 동시에 나간다")
        void serializesBeyondPermit() throws Exception {
            wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
                .willReturn(okJson(completion("{\\\"title\\\":\\\"t\\\"}", "stop"))
                    .withFixedDelay(300)));

            OpenAiLlmClient client = clientWith(properties(1, new Retry(3, 2, 0.01, 0.02, 0.0)));

            long startedAt = System.nanoTime();
            List.of(
                Thread.ofPlatform().start(() -> client.generate(call(SCHEMA))),
                Thread.ofPlatform().start(() -> client.generate(call(SCHEMA)))
            ).forEach(thread -> {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(requestCount()).isEqualTo(2);
            assertThat(elapsedMs)
                .as("슬롯이 1개면 300ms 응답 2건이 겹칠 수 없다")
                .isGreaterThanOrEqualTo(600);
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private OpenAiLlmClient client() {
        // 백오프를 짧게 둬 테스트가 실제로 몇 초씩 자지 않게 한다. 백오프 값 자체의 정확성은
        // LlmRetryExecutorTest 가 순수 함수로 따로 검증한다.
        return clientWith(properties(2, new Retry(3, 2, 0.01, 0.02, 0.0)));
    }

    private OpenAiLlmClient clientWith(AiLlmProperties properties) {
        return new OpenAiLlmClient(
            properties,
            new LlmResponseParser(new ObjectMapper()),
            new LlmRetryExecutor(properties),
            OpenAiLlmClient.buildChatModel(wireMock.baseUrl(), "test-api-key", properties.timeoutMs()));
    }

    private static AiLlmProperties properties(int maxConcurrentCalls, Retry retry) {
        return new AiLlmProperties("openai", 5_000, maxConcurrentCalls, retry,
            Map.of(AGENT, new Agent("gpt-5.6-luna", 0.7, 2048)),
            new OpenAi("test-api-key", "http://localhost"));
    }

    private static LlmCall<Plan> call(String schema) {
        return new LlmCall<>(AGENT, null, "경주 3일 코스의 제목을 정해줘", Plan.class, schema);
    }

    private void stubSuccess(String escapedContent, String finishReason) {
        wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
            .willReturn(okJson(completion(escapedContent, finishReason))));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
        return aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(body);
    }

    /** @param escapedContent JSON 문자열 안에 들어갈 형태로 이스케이프된 응답 본문 */
    private static String completion(String escapedContent, String finishReason) {
        return """
            {
              "id": "chatcmpl-test",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "gpt-5.6-luna",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "%s" },
                  "finish_reason": "%s"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30 }
            }
            """.formatted(escapedContent, finishReason);
    }

    private int requestCount() {
        return wireMock.findAll(postRequestedFor(urlPathEqualTo(COMPLETIONS_PATH))).size();
    }

    private JsonNode lastRequestBody() throws Exception {
        List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlPathEqualTo(COMPLETIONS_PATH)));
        assertThat(requests).isNotEmpty();
        return OBJECT_MAPPER.readTree(requests.get(requests.size() - 1).getBodyAsString());
    }
}
