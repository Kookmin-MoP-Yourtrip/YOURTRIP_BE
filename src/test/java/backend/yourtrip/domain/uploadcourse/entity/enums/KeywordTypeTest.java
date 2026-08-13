package backend.yourtrip.domain.uploadcourse.entity.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link KeywordType#buildKeywordsJson} 회귀 테스트.
 *
 * <p>keywords를 생략한 요청이 여기까지 도달해 {@code new HashSet<>(null)}에서 NPE를 냈고,
 * 이를 받는 핸들러가 없어 원시 500이 나갔다. DTO에 {@code @NotEmpty}를 걸었지만 그 검증을
 * 거치지 않는 호출부(벤치마크 하네스)가 있어 이 메서드 자체의 방어도 필요하다.
 */
class KeywordTypeTest {

    @Test
    @DisplayName("null을 넘겨도 NPE 없이 빈 JSON을 만든다")
    void handlesNullWithoutNpe() {
        assertThatCode(() -> KeywordType.buildKeywordsJson(null))
            .doesNotThrowAnyException();

        assertThat(KeywordType.buildKeywordsJson(null)).isEqualTo("{ }");
    }

    @Test
    @DisplayName("빈 리스트는 빈 JSON을 만든다")
    void handlesEmptyList() {
        assertThat(KeywordType.buildKeywordsJson(List.of())).isEqualTo("{ }");
    }

    @Test
    @DisplayName("선택한 키워드를 카테고리별로 묶는다")
    void groupsSelectedKeywordsByCategory() {
        String json = KeywordType.buildKeywordsJson(
            List.of(KeywordType.WALK, KeywordType.COUPLE, KeywordType.HEALING));

        assertThat(json)
            .contains("travelMode")
            .contains("companionType")
            .contains("mood");
    }
}
