package backend.yourtrip.global.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.openai.api.ResponseFormat.JsonSchema;
import org.springframework.ai.openai.api.ResponseFormat.Type;

/**
 * ROADMAP.md 0단계(0-3)의 검증 테스트.
 *
 * <p>멀티 에이전트 파이프라인 설계(docs/tasks/ai-course-create/TASK-AI-MULTI-AGENT.md §6·§7)는
 * <b>"JSON 스키마를 프롬프트 텍스트가 아니라 디코딩 레벨에서 강제한다"</b>는 전제 위에 서 있다.
 * 그 전제가 성립해야 ① 프롬프트에서 스키마·출력예시 ~36줄이 사라지고 ② 파싱 실패율이 near-zero가 되며
 * ③ "의미 재시도"를 1회로 제한할 수 있다. 전제가 깨지면(= Spring AI가 스키마를 프롬프트에 끼워 넣는
 * 방식이면) Spring AI를 포기하고 OpenAI 공식 SDK로 어댑터를 구현해야 한다.
 *
 * <p>그래서 이 테스트가 확인하는 것은 "응답이 잘 오는가"가 아니라 <b>스키마가 어디에 실려 나가는가</b>다.
 *
 * <p>검증은 두 단계다.
 * <ul>
 *   <li><b>{@link #schemaIsSentAsApiFieldNotInPrompt()}</b> — WireMock으로 요청 본문을 가로채
 *       {@code response_format.json_schema}에 실렸는지, {@code messages[].content}에 섞이지
 *       <b>않았는지</b> 확인한다. API 키가 필요 없고 비용이 0이라 일반 빌드에 포함한다.</li>
 *   <li><b>{@link #realApiHonorsStrictJsonSchema()}</b> — 실제 OpenAI를 1회 호출해 선택한 모델이
 *       strict json_schema를 실제로 지원하는지 확인한다. 키와 비용이 필요하므로
 *       {@code @Tag("benchmark")}로 일반 빌드에서 제외한다
 *       ({@code ./gradlew benchmarkTest --tests '*SpringAiStructuredOutput*' --rerun}).</li>
 * </ul>
 *
 * <p>Spring 컨텍스트를 띄우지 않는다. 확인하려는 것이 Spring AI 라이브러리의 전송 동작이지 이 앱의
 * 빈 배선이 아니고, 컨텍스트를 띄우면 DB/Redis가 필요해져 검증의 초점이 흐려지기 때문이다.
 * 같은 이유로 spring-dotenv도 동작하지 않아 레포 루트 {@code .env}를 직접 읽는다
 * (AiHallucinationBaselineTest와 같은 방식).
 */
@DisplayName("Spring AI 구조화 출력 전송 방식 검증 (ROADMAP 0-3)")
class SpringAiStructuredOutputVerificationTest {

    /**
     * Planner 응답 스키마의 축소판. 실제 스키마 대신 이걸 쓰는 이유는, 이 테스트가 확인하려는 것이
     * 스키마의 내용이 아니라 <b>스키마가 실려 나가는 위치</b>이기 때문이다.
     *
     * <p>{@code additionalProperties: false}와 {@code required} 전체 명시는 OpenAI strict 모드의
     * 요구사항이라 그대로 지킨다 — 빠뜨리면 400이 떨어져 0-3b가 엉뚱한 이유로 실패한다.
     */
    private static final String PLANNER_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "title":   { "type": "string" },
            "concept": { "type": "string" }
          },
          "required": ["title", "concept"],
          "additionalProperties": false
        }
        """;

    /** 스키마에만 등장하고 프롬프트에는 절대 넣지 않는 표식. 프롬프트 오염 여부를 이 문자열로 판별한다. */
    private static final String SCHEMA_ONLY_MARKER = "additionalProperties";

    private static final String COMPLETIONS_PATH = "/v1/chat/completions";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    @Test
    @DisplayName("JSON 스키마는 프롬프트가 아니라 response_format API 필드로 전송된다")
    void schemaIsSentAsApiFieldNotInPrompt() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(chatCompletionResponse())));

        OpenAiChatModel chatModel = chatModelPointingAt(wireMock.baseUrl(), "test-api-key", "gpt-5-nano");

        chatModel.call(new Prompt("경주 3일 코스의 컨셉과 제목을 정해줘"));

        List<LoggedRequest> requests =
            wireMock.findAll(postRequestedFor(urlPathEqualTo(COMPLETIONS_PATH)));
        assertThat(requests)
            .as("Spring AI가 OpenAI chat completions 엔드포인트를 호출했는지")
            .hasSize(1);

        JsonNode body = OBJECT_MAPPER.readTree(requests.get(0).getBodyAsString());

        // 실제 전송된 본문을 남긴다. 이 테스트의 결론("스키마가 API 필드로 나간다")은 문서에
        // 근거로 인용되므로, 초록불만이 아니라 근거 자체가 눈에 보여야 한다.
        System.out.println("[0-3a] 실제 전송된 요청 본문:\n"
            + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(body));

        // ① 스키마가 API 필드에 실렸는가
        JsonNode responseFormat = body.path("response_format");
        assertThat(responseFormat.path("type").asText())
            .as("response_format.type")
            .isEqualTo("json_schema");
        assertThat(responseFormat.path("json_schema").path("schema").path("properties").has("title"))
            .as("스키마 본문이 response_format.json_schema.schema 에 실려야 한다")
            .isTrue();
        assertThat(responseFormat.path("json_schema").path("strict").asBoolean())
            .as("strict 모드가 켜져야 디코딩 레벨 강제가 된다")
            .isTrue();

        // ② 스키마가 프롬프트로 새지 않았는가 — 이게 이 테스트의 핵심 주장이다
        String allMessageContent = body.path("messages").toString();
        assertThat(allMessageContent)
            .as("스키마가 messages[].content 로 새면 프롬프트 토큰을 먹고 디코딩 레벨 강제가 아니게 된다")
            .doesNotContain(SCHEMA_ONLY_MARKER);
    }

    @Test
    @Tag("benchmark")
    @DisplayName("실제 OpenAI 호출에서 선택한 모델이 strict json_schema를 지원한다")
    void realApiHonorsStrictJsonSchema() throws Exception {
        String apiKey = resolveEnv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
            "이 검증은 실제 OPENAI_API_KEY 가 필요하다 (.env 또는 환경변수)");

        String model = resolveEnv("OPENAI_VERIFY_MODEL");
        if (model == null || model.isBlank()) {
            model = "gpt-5-nano";
        }

        OpenAiChatModel chatModel =
            chatModelPointingAt("https://api.openai.com", apiKey, model);

        ChatResponse response =
            chatModel.call(new Prompt("경주 3일 여행 코스의 컨셉과 제목을 정해줘"));

        String content = response.getResult().getOutput().getText();
        System.out.println("[0-3b] model=" + model + " content=" + content);

        // 스키마 준수 여부를 파싱으로 확인한다. strict 가 실제로 걸렸다면 파싱이 실패할 수 없다.
        JsonNode parsed = OBJECT_MAPPER.readTree(content);
        assertThat(parsed.hasNonNull("title")).as("스키마의 required 필드 title").isTrue();
        assertThat(parsed.hasNonNull("concept")).as("스키마의 required 필드 concept").isTrue();
        assertThat(parsed.properties())
            .as("additionalProperties:false 이므로 스키마 밖 필드가 없어야 한다")
            .hasSize(2);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private static OpenAiChatModel chatModelPointingAt(String baseUrl, String apiKey, String model) {
        OpenAiApi api = OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .build();

        ResponseFormat jsonSchemaFormat = ResponseFormat.builder()
            .type(Type.JSON_SCHEMA)
            .jsonSchema(JsonSchema.builder()
                .name("planner_output")
                .schema(PLANNER_SCHEMA)
                .strict(true)
                .build())
            .build();

        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(model)
                .responseFormat(jsonSchemaFormat)
                .build())
            .build();
    }

    /** Spring 컨텍스트가 없어 spring-dotenv 가 동작하지 않으므로 실제 환경변수 → .env 순으로 찾는다. */
    private static String resolveEnv(String key) throws IOException {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        Path dotEnv = Path.of(".env");
        if (!Files.exists(dotEnv)) {
            return null;
        }
        for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0 && trimmed.substring(0, eq).trim().equals(key)) {
                return trimmed.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static String chatCompletionResponse() {
        return """
            {
              "id": "chatcmpl-verification",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "gpt-5-nano",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "{\\"title\\":\\"경주, 천년의 밤을 걷다\\",\\"concept\\":\\"도보 중심 구시가지\\"}"
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30 }
            }
            """;
    }
}
