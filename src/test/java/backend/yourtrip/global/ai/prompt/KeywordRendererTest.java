package backend.yourtrip.global.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("KeywordRenderer (ROADMAP 6-5)")
class KeywordRendererTest {

    @Nested
    @DisplayName("렌더링")
    class Render {

        @Test
        @DisplayName("카테고리 순서가 고정된다 — 같은 입력이면 프롬프트도 같아야 한다")
        void keepsCategoryOrder() {
            String rendered = KeywordRenderer.render(List.of(
                KeywordType.PREMIUM, KeywordType.HEALING, KeywordType.COUPLE, KeywordType.WALK));

            assertThat(rendered).isEqualTo("이동수단: 뚜벅이 / 동행: 연인 / 분위기: 힐링 / 예산: 프리미엄");
        }

        @Test
        @DisplayName("같은 카테고리에 여럿이면 쉼표로 잇는다")
        void joinsWithinCategory() {
            assertThat(KeywordRenderer.render(List.of(KeywordType.HEALING, KeywordType.SENSIBILITY)))
                .isEqualTo("분위기: 힐링, 감성");
        }

        @Test
        @DisplayName("duration 키워드는 싣지 않는다 — 여행 일수의 정본은 요청의 days다")
        void dropsDuration() {
            String rendered = KeywordRenderer.render(
                List.of(KeywordType.TWO_DAYS, KeywordType.WEEKEND, KeywordType.SOLO));

            assertThat(rendered).isEqualTo("동행: 혼자");
        }

        @Test
        @DisplayName("고르지 않은 카테고리는 아예 빼고 '없음'이라 적지 않는다")
        void omitsUnselectedCategories() {
            assertThat(KeywordRenderer.render(List.of(KeywordType.CAR)))
                .isEqualTo("이동수단: 자차")
                .doesNotContain("동행");
        }

        @Test
        @DisplayName("키워드가 없거나 duration뿐이면 '지정 없음'")
        void handlesEmpty() {
            assertThat(KeywordRenderer.render(List.of())).isEqualTo("지정 없음");
            assertThat(KeywordRenderer.render(null)).isEqualTo("지정 없음");
            assertThat(KeywordRenderer.render(List.of(KeywordType.LONG))).isEqualTo("지정 없음");
        }
    }

    @Nested
    @DisplayName("duration 모순 감지")
    class DurationConflict {

        @Test
        @DisplayName("'하루'인데 3일이면 어긋남으로 본다")
        void detectsOneDayConflict() {
            assertThat(KeywordRenderer.durationConflict(List.of(KeywordType.ONE_DAY), 3))
                .isPresent()
                .get().asString().contains("하루").contains("3일");
        }

        @Test
        @DisplayName("'1박 2일'과 2일은 어긋나지 않는다")
        void acceptsMatchingDuration() {
            assertThat(KeywordRenderer.durationConflict(List.of(KeywordType.TWO_DAYS), 2))
                .isEmpty();
        }

        @Test
        @DisplayName("'주말'·'장기'는 일수가 모호해 판정하지 않는다")
        void ignoresAmbiguousDuration() {
            assertThat(KeywordRenderer.durationConflict(List.of(KeywordType.WEEKEND), 5)).isEmpty();
            assertThat(KeywordRenderer.durationConflict(List.of(KeywordType.LONG), 1)).isEmpty();
        }

        @Test
        @DisplayName("duration 키워드가 없으면 판정할 것도 없다")
        void ignoresWhenAbsent() {
            assertThat(KeywordRenderer.durationConflict(List.of(KeywordType.HEALING), 3)).isEmpty();
            assertThat(KeywordRenderer.durationConflict(null, 3)).isEmpty();
        }
    }
}
