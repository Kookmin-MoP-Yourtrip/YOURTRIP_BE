package backend.yourtrip.global.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import backend.yourtrip.global.ai.config.AiLlmProperties.Agent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * {@code AiLlmProperties} 바인딩 검증 (ROADMAP 2-3).
 *
 * <p><b>픽스처가 아니라 실제 배포되는 {@code application.yml}을 읽는다.</b> 이 테스트가 확인하려는
 * 것은 "record가 바인딩 가능한 모양인가"가 아니라 <b>"우리가 실제로 배포하는 설정이 유효한가"</b>이기
 * 때문이다. 픽스처를 쓰면 yml 오타를 정확히 놓친다.
 *
 * <p>{@code @SpringBootTest}를 쓰지 않는 이유는 이 저장소의 통합 테스트가 아직 실제 DB/Redis를
 * 요구해서다({@code src/test/resources/application-test.yml} 주석). 확인하려는 것이 프로퍼티
 * 바인딩이지 빈 배선이 아니므로 {@code Binder}로 충분하다 —
 * {@code SpringAiStructuredOutputVerificationTest}가 세운 선례와 같은 판단이다.
 */
@DisplayName("AiLlmProperties 바인딩 (ROADMAP 2-3)")
class AiLlmPropertiesTest {

    /** 0단계에서 확정한 에이전트 키. 6·9단계에서 실제 에이전트가 이 이름으로 설정을 찾는다. */
    private static final List<String> EXPECTED_AGENTS = List.of("planner", "curator", "place-profile");

    @Test
    @DisplayName("배포되는 application.yml 의 llm 블록이 검증을 통과하며 바인딩된다")
    void bindsShippedApplicationYml() throws IOException {
        AiLlmProperties properties = bindFromApplicationYml();

        assertThat(properties.provider()).isEqualTo("openai");
        assertThat(properties.timeoutMs()).isPositive();
        assertThat(properties.maxConcurrentCalls()).isPositive();
        assertThat(properties.retry().attempts()).isGreaterThanOrEqualTo(1);
        assertThat(properties.agents()).containsOnlyKeys(EXPECTED_AGENTS.toArray(String[]::new));
    }

    @Test
    @DisplayName("API 키가 없어도 바인딩이 성공한다 — 키 없는 환경에서 기동이 깨지지 않는 조건")
    void bindsWithoutApiKey() throws IOException {
        // OPENAI_API_KEY 를 프로퍼티 소스에 넣지 않았으므로 ${OPENAI_API_KEY:} 의 기본값(빈 문자열)이 쓰인다.
        AiLlmProperties properties = bindFromApplicationYml();

        assertThat(properties.openai().apiKey())
            .as("키가 없으면 빈 문자열이어야 한다 — 여기서 예외가 나면 2단계가 동작 변화를 만든다")
            .isEmpty();
        assertThat(properties.openai().baseUrl()).startsWith("https://");
    }

    @Test
    @DisplayName("에이전트마다 model 과 max-output-tokens 가 지정돼 있다")
    void everyAgentIsFullyConfigured() throws IOException {
        Map<String, Agent> agents = bindFromApplicationYml().agents();

        assertThat(agents.values()).allSatisfy(agent -> {
            assertThat(agent.model()).isNotBlank();
            // 절단이 파싱 실패의 실제 원인이었으므로(BASELINE-ARTIFACT-ANALYSIS 판정 3)
            // 출력 상한이 비어 있으면 안 된다.
            assertThat(agent.maxOutputTokens()).isNotNull().isPositive();
            // temperature 는 비어 있는 것이 정상이다 — gpt-5 계열이 커스텀 값을 400으로 거부한다.
            // 값이 있다면 온도를 받는 모델로 바꿨다는 뜻이므로 범위만 확인한다.
            if (agent.temperature() != null) {
                assertThat(agent.temperature()).isBetween(0.0, 2.0);
            }
        });
    }

    @Test
    @DisplayName("현재 배치된 모델들은 커스텀 temperature 를 받지 않으므로 설정이 비어 있어야 한다")
    void temperatureIsUnsetForGpt5Models() throws IOException {
        Map<String, Agent> agents = bindFromApplicationYml().agents();

        assertThat(agents.values())
            .filteredOn(agent -> agent.model().startsWith("gpt-5"))
            .as("값을 넣으면 400 'Only the default (1) value is supported' 로 요청이 실패한다")
            .allSatisfy(agent -> assertThat(agent.temperature()).isNull());
    }

    @Test
    @DisplayName("없는 agentName 을 조회하면 사용 가능한 키를 알려주며 즉시 실패한다")
    void unknownAgentFailsLoudly() throws IOException {
        AiLlmProperties properties = bindFromApplicationYml();

        assertThatThrownBy(() -> properties.agent("plannner"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("planner");
    }

    @Test
    @DisplayName("모델 ID 가 비어 있으면 기동 시점에 바인딩이 실패한다")
    void blankModelIsRejectedAtBindTime() {
        Map<String, Object> broken = Map.ofEntries(
            Map.entry("llm.provider", "openai"),
            Map.entry("llm.timeout-ms", 20000),
            Map.entry("llm.max-concurrent-calls", 2),
            Map.entry("llm.retry.attempts", 3),
            Map.entry("llm.retry.semantic-attempts", 2),
            Map.entry("llm.retry.initial-delay-seconds", 0.5),
            Map.entry("llm.retry.max-delay-seconds", 4.0),
            Map.entry("llm.retry.jitter", 0.3),
            Map.entry("llm.agents.planner.model", "   "),
            Map.entry("llm.agents.planner.temperature", 0.7),
            Map.entry("llm.agents.planner.max-output-tokens", 2048)
        );
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("broken", withOpenAiDefaults(broken)));

        assertThatThrownBy(() -> bind(sources))
            .as("@Validated 가 기동 시점에 잡아주는 것이 @Value 대비 얻는 것이다")
            .isInstanceOf(BindException.class);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private static AiLlmProperties bindFromApplicationYml() throws IOException {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        yaml.afterPropertiesSet();

        Map<String, Object> flat = new java.util.HashMap<>();
        yaml.getObject().forEach((key, value) -> flat.put(String.valueOf(key), value));

        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("application.yml", flat));
        return bind(sources);
    }

    /**
     * {@code ${OPENAI_API_KEY:}} 같은 플레이스홀더를 같은 프로퍼티 소스 기준으로 해석한다.
     * 소스에 값이 없으면 콜론 뒤의 기본값(빈 문자열)이 쓰이는데, 그게 바로 이 테스트가
     * 확인하려는 "키 없는 환경" 상황이다.
     */
    private static AiLlmProperties bind(MutablePropertySources sources) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        Binder binder = new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources));

        return binder.bind("llm", Bindable.of(AiLlmProperties.class),
            new ValidationBindHandler(validator)).get();
    }

    private static Map<String, Object> withOpenAiDefaults(Map<String, Object> base) {
        Map<String, Object> merged = new java.util.HashMap<>(base);
        merged.put("llm.openai.api-key", "");
        merged.put("llm.openai.base-url", "https://api.openai.com");
        return merged;
    }
}
