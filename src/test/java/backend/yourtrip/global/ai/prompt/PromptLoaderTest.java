package backend.yourtrip.global.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PromptLoader (ROADMAP 6-1)")
class PromptLoaderTest {

    private final PromptLoader loader = new PromptLoader();

    @Nested
    @DisplayName("eager 로드")
    class EagerLoad {

        @Test
        @DisplayName("생성자에서 전 리소스를 읽는다 — 스프링 없이 조립해도 비어 있지 않다")
        void loadsEveryResourceInConstructor() {
            for (PromptTemplate template : PromptTemplate.values()) {
                assertThat(loader.render(template, everyPlaceholderOf(template))).isNotBlank();
            }
            for (ResponseSchema schema : ResponseSchema.values()) {
                assertThat(loader.schema(schema)).isNotBlank();
            }
        }

        @Test
        @DisplayName("응답 스키마의 루트는 객체다 — 최상위 배열은 실 API가 400으로 거부한다(0-3b)")
        void schemaRootIsObject() {
            for (ResponseSchema schema : ResponseSchema.values()) {
                assertThat(loader.schema(schema)).contains("\"type\": \"object\"");
            }
        }
    }

    @Nested
    @DisplayName("렌더링")
    class Render {

        @Test
        @DisplayName("이름으로 값을 채운다")
        void fillsByName() {
            String rendered = loader.render(PromptTemplate.PLANNER_USER,
                Map.of("location", "경주", "days", "3", "keywords", "이동수단: 뚜벅이"));

            assertThat(rendered)
                .contains("여행지: 경주")
                .contains("여행 일수: 3")
                .contains("이동수단: 뚜벅이")
                .doesNotContain("{{");
        }

        @Test
        @DisplayName("값이 없는 플레이스홀더는 예외 — 빈 자리가 그대로 LLM에 실려 나가는 것을 막는다")
        void rejectsMissingValue() {
            assertThatThrownBy(() -> loader.render(PromptTemplate.PLANNER_USER,
                Map.of("location", "경주", "days", "3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keywords");
        }

        @Test
        @DisplayName("치환값 안의 {{...}}는 다시 치환하지 않는다 — 입력이 프롬프트를 바꾸면 안 된다")
        void doesNotRecurseIntoValues() {
            String rendered = loader.render(PromptTemplate.PLANNER_USER,
                Map.of("location", "{{days}}", "days", "3", "keywords", "없음"));

            assertThat(rendered).contains("여행지: {{days}}");
        }

        @Test
        @DisplayName("치환값의 $ 와 백슬래시가 역참조로 해석되지 않는다")
        void treatsValueAsLiteral() {
            String rendered = loader.render(PromptTemplate.PLANNER_USER,
                Map.of("location", "$1 \\ 경주", "days", "3", "keywords", "없음"));

            assertThat(rendered).contains("여행지: $1 \\ 경주");
        }
    }

    @Nested
    @DisplayName("프롬프트 파일 자체의 계약")
    class TemplateContract {

        @Test
        @DisplayName("표기가 틀린 플레이스홀더가 없다 — 있으면 기동 시점에 걸린다")
        void everyPlaceholderIsWellFormed() {
            assertThatCode(PromptLoader::new).doesNotThrowAnyException();
        }
    }

    /** 그 템플릿이 요구하는 이름 전부에 더미 값을 채운 맵. */
    private static Map<String, String> everyPlaceholderOf(PromptTemplate template) {
        return switch (template) {
            case PLANNER_SYSTEM, CURATOR_SYSTEM -> Map.of();
            case PLANNER_USER -> Map.of("location", "경주", "days", "3", "keywords", "없음");
            case CURATOR_USER -> Map.of("day", "1", "area", "황리단길", "theme", "산책",
                "concept", "느긋한 경주", "keywords", "없음", "slots", "0. CAFE", "candidates", "-");
        };
    }
}
