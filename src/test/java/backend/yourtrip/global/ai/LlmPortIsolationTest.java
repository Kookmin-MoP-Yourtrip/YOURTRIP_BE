package backend.yourtrip.global.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 포트가 벤더 SDK로부터 격리돼 있는지 확인한다 (ROADMAP 2-5).
 *
 * <p>LLM 포트 설계는 "에이전트 코드가 벤더 SDK 타입을 한 개도 import하지 않는다"를 이 작업의
 * 성과로 내세운다. 그런데 <b>주장만으로는 지켜지지 않는다</b> — 6~9단계에서 에이전트를 만들다
 * 보면 {@code OpenAiChatOptions} 하나만 잠깐 쓰고 싶은 순간이 반드시 온다. 그때 이 테스트가
 * 빨간불이 되는 것이 규칙을 지키는 유일한 방법이다.
 *
 * <p>ArchUnit을 도입하지 않은 이유는 규칙이 하나뿐이라서다. 의존성을 하나 늘리는 대신
 * 소스 텍스트를 직접 읽는다 — 이 정도 규모에서는 그게 더 정직하다.
 */
@DisplayName("포트 격리 — 벤더 SDK 유출 검사 (ROADMAP 2-5)")
class LlmPortIsolationTest {

    private static final Path AI_PACKAGE = Path.of("src/main/java/backend/yourtrip/global/ai");

    /** 벤더 SDK로 취급하는 패키지. 하나라도 포트 쪽에 나타나면 추상화가 새고 있는 것이다. */
    private static final List<String> VENDOR_PACKAGES = List.of(
        "org.springframework.ai",  // Spring AI (현재 어댑터의 전송 계층)
        "com.google.genai",        // Gemini SDK (8단계에서 삭제 예정)
        "com.openai"               // OpenAI 공식 SDK (0단계 폴백 후보였다)
    );

    /** 벤더 SDK가 허용되는 유일한 곳. 어댑터 구현이 여기 산다. */
    private static final String ADAPTER_PACKAGE_DIR = "openai";

    @Test
    @DisplayName("어댑터 패키지 밖의 어떤 파일도 벤더 SDK를 import하지 않는다")
    void portDoesNotLeakVendorTypes() throws IOException {
        List<Path> portSources = sourcesOutsideAdapter();

        assertThat(portSources)
            .as("검사 대상이 비어 있으면 이 테스트는 아무것도 증명하지 못한다")
            .isNotEmpty();

        for (Path source : portSources) {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            for (String vendorPackage : VENDOR_PACKAGES) {
                assertThat(content)
                    .as("%s 가 벤더 SDK(%s)를 import하면 포트가 무의미해진다",
                        AI_PACKAGE.relativize(source), vendorPackage)
                    .doesNotContain("import " + vendorPackage);
            }
        }
    }

    @Test
    @DisplayName("어댑터는 실제로 벤더 SDK를 쓴다 — 검사기가 헛돌지 않는다는 확인")
    void adapterActuallyUsesVendorSdk() throws IOException {
        Path adapter = AI_PACKAGE.resolve(ADAPTER_PACKAGE_DIR).resolve("OpenAiLlmClient.java");

        assertThat(Files.readString(adapter, StandardCharsets.UTF_8))
            .as("여기서 벤더 SDK가 안 보이면 위 테스트의 통과는 검사기 버그일 수 있다")
            .contains("import org.springframework.ai");
    }

    private static List<Path> sourcesOutsideAdapter() throws IOException {
        assertThat(AI_PACKAGE)
            .as("테스트는 프로젝트 루트를 작업 디렉터리로 실행된다")
            .exists();

        try (Stream<Path> files = Files.walk(AI_PACKAGE)) {
            return files
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !AI_PACKAGE.relativize(path).startsWith(ADAPTER_PACKAGE_DIR))
                .toList();
        }
    }
}
