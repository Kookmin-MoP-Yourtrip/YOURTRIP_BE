package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link StyleModifierDictionary} 단위 테스트 (ROADMAP 4-3).
 *
 * <p>순수 함수라 Spring 컨텍스트도 Mockito도 없다 — {@code route} 패키지 테스트와 같은 관례.
 */
@DisplayName("StyleModifierDictionary — 키워드에서 스타일 modifier 선정 (ROADMAP 4-3)")
class StyleModifierDictionaryTest {

    @Nested
    @DisplayName("설계 표 매핑")
    class Mapping {

        @Test
        @DisplayName("연인은 야경·루프탑을 부른다 — 설계 표의 상위 두 개")
        void 연인() {
            assertThat(StyleModifierDictionary.modifiersFor(List.of(KeywordType.COUPLE)))
                .containsExactly(StyleTag.NIGHT_VIEW, StyleTag.ROOFTOP);
        }

        @Test
        @DisplayName("가족은 주차·아이동반을 부른다")
        void 가족() {
            assertThat(StyleModifierDictionary.modifiersFor(List.of(KeywordType.FAMILY)))
                .containsExactly(StyleTag.PARKING_AVAILABLE, StyleTag.KID_FRIENDLY);
        }

        @Test
        @DisplayName("가성비는 태그가 하나뿐이라 하나만 나온다 — 억지로 두 개를 채우지 않는다")
        void 가성비() {
            assertThat(StyleModifierDictionary.modifiersFor(List.of(KeywordType.COST_EFFECTIVE)))
                .containsExactly(StyleTag.CHEAP);
        }

        @Test
        @DisplayName("설계 표에 없던 mood 3개는 4-9 어휘로 채웠다")
        void 확장된_mood() {
            assertThat(StyleModifierDictionary.modifiersFor(List.of(KeywordType.NATURE)))
                .containsExactly(StyleTag.NATURE, StyleTag.UNCROWDED);
            assertThat(StyleModifierDictionary.modifiersFor(List.of(KeywordType.CULTURE)))
                .containsExactly(StyleTag.CULTURE, StyleTag.INDOOR);
            assertThat(StyleModifierDictionary.modifiersFor(List.of(KeywordType.ACTIVITY)))
                .containsExactly(StyleTag.ACTIVITY);
        }
    }

    @Nested
    @DisplayName("매핑을 비운 키워드 — 비운 것도 결정이다")
    class Unmapped {

        @Test
        @DisplayName("여행기간은 스타일 축이 아니라 modifier를 만들지 않는다")
        void 여행기간은_비어_있다() {
            assertThat(StyleModifierDictionary.modifiersFor(
                List.of(KeywordType.ONE_DAY, KeywordType.TWO_DAYS,
                    KeywordType.WEEKEND, KeywordType.LONG)))
                .isEmpty();
        }

        @Test
        @DisplayName("맛집탐방·쇼핑은 슬롯 구성으로 표현되므로 쿼리를 만들지 않는다")
        void 슬롯으로_표현되는_키워드는_비어_있다() {
            assertThat(StyleModifierDictionary.modifiersFor(
                List.of(KeywordType.FOOD, KeywordType.SHOPPING, KeywordType.NORMAL)))
                .isEmpty();
        }

        @Test
        @DisplayName("매핑 없는 키워드가 섞여도 있는 것만 골라낸다")
        void 섞여_있어도_동작한다() {
            assertThat(StyleModifierDictionary.modifiersFor(
                List.of(KeywordType.WEEKEND, KeywordType.COUPLE, KeywordType.FOOD)))
                .containsExactly(StyleTag.NIGHT_VIEW, StyleTag.ROOFTOP);
        }
    }

    @Nested
    @DisplayName("여러 키워드 — 중복 등장이 우선한다")
    class Ranking {

        @Test
        @DisplayName("두 키워드가 함께 가리키는 태그가 1순위다 — 취향이 겹치는 지점이라 신호가 강하다")
        void 중복_등장을_우선한다() {
            // COUPLE: 야경 루프탑 통창 조용함 뷰맛집 / HEALING: 조용함 한적함 뷰맛집
            // 겹치는 것은 조용함·뷰맛집 둘. 겹치지 않는 야경(선언 순서상 더 앞)보다 앞에 와야 한다.
            List<StyleTag> modifiers = StyleModifierDictionary.modifiersFor(
                List.of(KeywordType.COUPLE, KeywordType.HEALING));

            assertThat(modifiers)
                .as("야경은 COUPLE에만 있으므로 두 번 등장한 태그에 밀려야 한다")
                .containsExactly(StyleTag.QUIET, StyleTag.GREAT_VIEW);
        }

        @Test
        @DisplayName("동점이면 설계 표 순위로 깬다 — enum 선언 순서를 쓰면 표를 배신한다")
        void 동점은_설계_표_순위로_깬다() {
            assertThat(StyleModifierDictionary.modifiersFor(List.of(KeywordType.SENSIBILITY)))
                .as("통창·한옥·레트로·루프탑이 전부 1회씩이라 설계 표 순서가 갈라야 한다")
                .containsExactly(StyleTag.PANORAMIC_WINDOW, StyleTag.HANOK);

            assertThat(StyleModifierDictionary.modifiersFor(List.of(KeywordType.COUPLE)))
                .as("enum 선언 순서였다면 야경 다음이 뷰맛집이 되어 설계 표(야경→루프탑)와 어긋난다")
                .containsExactly(StyleTag.NIGHT_VIEW, StyleTag.ROOFTOP);
        }

        @Test
        @DisplayName("키워드 순서가 바뀌어도 같은 결과다")
        void 입력_순서에_흔들리지_않는다() {
            List<KeywordType> keywords = new ArrayList<>(
                List.of(KeywordType.COUPLE, KeywordType.HEALING, KeywordType.CAR));
            List<StyleTag> first = StyleModifierDictionary.modifiersFor(keywords);

            Collections.reverse(keywords);

            assertThat(StyleModifierDictionary.modifiersFor(keywords)).isEqualTo(first);
        }

        @Test
        @DisplayName("최대 2개를 넘지 않는다 — 이 상수가 곧 네이버 쿼터 예산이다")
        void 상한을_지킨다() {
            List<StyleTag> modifiers = StyleModifierDictionary.modifiersFor(
                List.of(KeywordType.COUPLE, KeywordType.FAMILY, KeywordType.SENSIBILITY,
                    KeywordType.NATURE, KeywordType.CULTURE));

            assertThat(modifiers).hasSizeLessThanOrEqualTo(StyleModifierDictionary.MAX_MODIFIERS);
        }
    }

    @Nested
    @DisplayName("충돌 — 피하고 싶다는 표시가 이긴다")
    class Conflict {

        @Test
        @DisplayName("한쪽이 원하고 다른 쪽이 피하는 태그는 버린다")
        void 감점_태그는_가점을_이긴다() {
            // FRIENDS 는 시끌벅적을 원하고 COUPLE 은 피한다.
            List<StyleTag> modifiers = StyleModifierDictionary.modifiersFor(
                List.of(KeywordType.FRIENDS, KeywordType.COUPLE));

            assertThat(modifiers)
                .as("원하지 않는 곳으로 데려가는 실수가 그저 그런 곳으로 데려가는 실수보다 나쁘다")
                .doesNotContain(StyleTag.LIVELY);
        }

        @Test
        @DisplayName("혼자 + 가족이면 단체가능이 빠진다")
        void 단체가능_충돌() {
            assertThat(StyleModifierDictionary.modifiersFor(
                List.of(KeywordType.SOLO, KeywordType.FAMILY)))
                .doesNotContain(StyleTag.GROUP_FRIENDLY);
        }

        @Test
        @DisplayName("가성비 + 프리미엄이면 둘 다 빠지고 남은 것만 나온다")
        void 예산_충돌() {
            List<StyleTag> modifiers = StyleModifierDictionary.modifiersFor(
                List.of(KeywordType.COST_EFFECTIVE, KeywordType.PREMIUM));

            assertThat(modifiers)
                .doesNotContain(StyleTag.CHEAP, StyleTag.EXPENSIVE)
                .containsExactly(StyleTag.GREAT_VIEW);
        }
    }

    @Nested
    @DisplayName("경계")
    class EdgeCases {

        @Test
        @DisplayName("null·빈 목록은 빈 결과다 — 기본 쿼리만 쓴다")
        void 비어_있으면_빈_결과다() {
            assertThat(StyleModifierDictionary.modifiersFor(null)).isEmpty();
            assertThat(StyleModifierDictionary.modifiersFor(List.of())).isEmpty();
        }

        @Test
        @DisplayName("검색어가 없는 태그는 절대 고르지 않는다")
        void 검색_불가_태그를_거른다() {
            for (KeywordType keyword : KeywordType.values()) {
                assertThat(StyleModifierDictionary.modifiersFor(List.of(keyword)))
                    .allMatch(StyleTag::isSearchable);
            }
        }

        @Test
        @DisplayName("모든 키워드 조합에서 상한과 검색 가능성이 유지된다")
        void 전체_키워드를_넣어도_안전하다() {
            List<StyleTag> modifiers = StyleModifierDictionary.modifiersFor(
                List.of(KeywordType.values()));

            assertThat(modifiers).hasSizeLessThanOrEqualTo(StyleModifierDictionary.MAX_MODIFIERS);
            assertThat(modifiers).allMatch(StyleTag::isSearchable);
        }
    }
}
