package backend.yourtrip.global.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 프롬프트에서 <b>사라져야 할 규칙이 실제로 사라졌는지</b> 고정한다 (ROADMAP 6-6).
 *
 * <p>기존 95줄 프롬프트의 절반은 <b>이제 코드가 보장하는 것</b>을 LLM에게 부탁하는 지시문이었다.
 * 부탁은 지켜졌는지 확인할 방법이 없지만 코드는 불변식이다. 그 지시문이 다시 기어들어오면
 * ① 지켜지지 않아도 아무도 모르고 ② 코드의 계산과 어긋나는 요구를 모델이 따르려다 품질이 나빠진다.
 *
 * <table>
 *   <tr><th>사라진 규칙</th><th>지금 보장하는 곳</th></tr>
 *   <tr><td>시각 배치·오름차순·시간 겹침 금지</td><td>{@code RouteOptimizer} 시간 모델(3-4)</td></tr>
 *   <tr><td>동선 역주행 금지</td><td>{@code RouteOptimizer} 완전탐색(3-3)</td></tr>
 *   <tr><td>day당 식사 1회</td><td>{@code PlannerPlanNormalizer}(6-3)</td></tr>
 *   <tr><td>JSON 형식·코드블록 금지·스키마 밖 필드 금지</td><td>{@code response_format.json_schema}(0-3)</td></tr>
 *   <tr><td>장소 중복 금지</td><td>{@code GroundingStage} 전 day dedupe(5-2)</td></tr>
 * </table>
 */
@DisplayName("프롬프트에서 사라진 규칙 (ROADMAP 6-6)")
class PromptContentTest {

    private final PromptLoader loader = new PromptLoader();

    /** 프롬프트에 다시 나타나면 안 되는 표현 → 그 규칙을 지금 보장하는 곳. */
    private static final Map<String, String> FORBIDDEN = Map.of(
        "오름차순", "RouteOptimizer 가 시각을 계산한다",
        "겹치", "시간 겹침은 시간 모델의 불변식이다",
        "역주행", "동선은 완전탐색이 정한다",
        "startTime", "장소별 방문 시각은 코드가 붙인다",
        "daySchedules", "옛 단일 호출 스키마의 잔재다",
        "placeLocation", "PlaceDto 에 없던 죽은 지시문이었다",
        "JSON", "형식은 response_format.json_schema 가 강제한다",
        "코드 블록", "구조화 출력에서는 발생하지 않는다",
        "큰따옴표", "이스케이프는 디코딩 계층의 문제다");

    @Nested
    @DisplayName("금칙 표현")
    class Forbidden {

        @Test
        @DisplayName("코드가 보장하는 규칙을 프롬프트가 다시 부탁하지 않는다")
        void promptsDoNotRepeatCodeGuarantees() {
            for (PromptTemplate template : PromptTemplate.values()) {
                String body = raw(template);
                FORBIDDEN.forEach((word, reason) -> assertThat(body)
                    .as("%s 에 '%s' 가 남아 있다 — %s", template.getPath(), word, reason)
                    .doesNotContain(word));
            }
        }
    }

    @Nested
    @DisplayName("남아야 할 것")
    class Kept {

        @Test
        @DisplayName("Planner 는 권역과 랜드마크를 설명한다 — 이것이 6단계가 새로 요구하는 것이다")
        void plannerExplainsAreaAndAnchor() {
            assertThat(raw(PromptTemplate.PLANNER_SYSTEM))
                .contains("area")
                .contains("anchor")
                .contains("장소명을 한 개도 만들지 않는다");
        }

        @Test
        @DisplayName("Curator 는 선별 규칙과 목록 읽는 법을 설명한다")
        void curatorExplainsSelectionRules() {
            assertThat(raw(PromptTemplate.CURATOR_SYSTEM))
                .contains("번호는 0부터 시작한다")
                .contains("seed")
                .contains("SUGGESTED")
                .contains("listIndex");
        }

        @Test
        @DisplayName("상호명 표기 규칙은 남는다 — 코드가 대신할 수 없는 몇 안 되는 형식 규칙이다")
        void curatorKeepsPlaceNameRule() {
            assertThat(raw(PromptTemplate.CURATOR_SYSTEM)).contains("괄호");
        }
    }

    @Nested
    @DisplayName("요청마다 새로 만드는 부분")
    class VariablePart {

        /**
         * <b>줄 수가 줄었다고 주장하지 않는다.</b> 두 프롬프트의 합계는 오히려 기존 95줄보다 길다 —
         * 권역 설계와 후보 선별이라는 <b>기존 프롬프트에 없던 책임</b>을 새로 설명하기 때문이다.
         * 실제로 줄어든 것은 <b>요청마다 새로 조립되는 부분</b>이고, 고정 규칙은 system 으로 갈려
         * 나중에 컨텍스트 캐싱의 대상이 된다.
         */
        @Test
        @DisplayName("user 프롬프트는 가변 데이터만 담는다 — 규칙은 한 줄도 없다")
        void userPromptsCarryDataOnly() {
            assertThat(raw(PromptTemplate.PLANNER_USER).lines().count()).isLessThan(10);
            assertThat(raw(PromptTemplate.CURATOR_USER).lines().count()).isLessThan(20);
        }

        @Test
        @DisplayName("모든 플레이스홀더는 user 프롬프트에만 있다 — system 은 요청마다 같아야 캐싱된다")
        void placeholdersLiveInUserPromptsOnly() {
            List<PromptTemplate> systemPrompts =
                List.of(PromptTemplate.PLANNER_SYSTEM, PromptTemplate.CURATOR_SYSTEM);

            for (PromptTemplate template : systemPrompts) {
                assertThat(raw(template))
                    .as("%s 에 플레이스홀더가 있으면 요청마다 달라져 캐싱 경계가 무너진다",
                        template.getPath())
                    .doesNotContain("{{");
            }
        }
    }

    /** 치환 없이 원문 그대로. system 프롬프트는 플레이스홀더가 없어 빈 맵으로 렌더링된다. */
    private String raw(PromptTemplate template) {
        return switch (template) {
            case PLANNER_SYSTEM, CURATOR_SYSTEM -> loader.render(template, Map.of());
            case PLANNER_USER -> loader.render(template,
                Map.of("location", "", "days", "", "keywords", ""));
            case CURATOR_USER -> loader.render(template, Map.of("day", "", "area", "",
                "theme", "", "concept", "", "keywords", "", "slots", "", "candidates", ""));
        };
    }
}
