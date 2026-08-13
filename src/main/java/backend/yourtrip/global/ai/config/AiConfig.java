package backend.yourtrip.global.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 파이프라인의 설정 진입점.
 *
 * <p>{@code @ConfigurationPropertiesScan}을 앱 전역에 켜지 않고 여기서 명시적으로 등록하는 이유는,
 * 이 저장소의 나머지 설정이 전부 {@code @Value} 방식이라 전역 스캔을 켜면 <b>어떤 클래스가
 * 프로퍼티 바인딩 대상인지가 보이지 않게 되기 때문</b>이다. 등록 지점이 한 곳이면 목록이 곧 문서가 된다.
 *
 * <p><b>여기에 Spring AI 타입을 두지 않는다.</b> {@code OpenAiApi}·{@code OpenAiChatModel} 조립은
 * {@code ai.openai.OpenAiLlmClient} 안에서 끝내, 벤더 SDK가 어댑터 파일 하나를 벗어나지 않게 한다.
 * 이 규칙은 {@code LlmPortIsolationTest}가 테스트로 강제한다.
 */
@Configuration
@EnableConfigurationProperties(AiLlmProperties.class)
public class AiConfig {
}
