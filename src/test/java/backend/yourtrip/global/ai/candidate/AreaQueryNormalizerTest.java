package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AreaQueryNormalizer (이슈 #110)")
class AreaQueryNormalizerTest {

    @Nested
    @DisplayName("구분자")
    class Separators {

        @ParameterizedTest(name = "{0} -> 황리단길")
        @ValueSource(strings = {
            "황리단길·대릉원 일대",
            "황리단길ㆍ대릉원 일대",
            "황리단길･대릉원 일대",
            "황리단길/대릉원 일대",
            "황리단길,대릉원 일대"})
        @DisplayName("앞부분만 남긴다 — 어느 코드포인트를 쓸지는 모델이 정한다")
        void keepsFirstSegment(String area) {
            assertThat(AreaQueryNormalizer.toSearchTerm(area)).isEqualTo("황리단길");
        }

        @Test
        @DisplayName("구분자가 여럿이면 가장 앞의 것에서 자른다")
        void cutsAtEarliestSeparator() {
            assertThat(AreaQueryNormalizer.toSearchTerm("공산성/금강변·신관동"))
                .isEqualTo("공산성");
        }

        @Test
        @DisplayName("앞부분에 공백이 있어도 그대로 지킨다 — 지명의 일부다")
        void keepsInnerSpace() {
            assertThat(AreaQueryNormalizer.toSearchTerm("해운대 해변·달맞이길 일대"))
                .isEqualTo("해운대 해변");
        }
    }

    @Nested
    @DisplayName("권역 접미어")
    class Suffixes {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "순천만습지 일대, 순천만습지",
            "무섬마을 일대, 무섬마을",
            "부석사 방면, 부석사",
            "경포호 주변, 경포호",
            "선비촌 근처, 선비촌"})
        @DisplayName("구분자가 없는 권역명에서 접미어를 뗀다 — 이 규칙이 실제로 일하는 자리다")
        void stripsAreaSuffix(String area, String expected) {
            assertThat(AreaQueryNormalizer.toSearchTerm(area)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} 은 그대로")
        @ValueSource(strings = {"황리단길", "안목해변 커피거리", "문화의거리"})
        @DisplayName("접미어 후보가 지명에 박혀 있으면 떼지 않는다 — 이 규칙의 가장 큰 위험이다")
        void keepsSuffixLikeSyllablesInsideNames(String area) {
            assertThat(AreaQueryNormalizer.toSearchTerm(area)).isEqualTo(area);
        }

        @Test
        @DisplayName("접미어만 남으면 떼지 않는다 — 빈 검색어는 원문보다 나쁘다")
        void keepsSuffixOnlyInput() {
            assertThat(AreaQueryNormalizer.toSearchTerm("일대")).isEqualTo("일대");
        }

        @Test
        @DisplayName("구분자와 접미어가 함께 있으면 둘 다 처리한다")
        void handlesBoth() {
            assertThat(AreaQueryNormalizer.toSearchTerm("보문호·보문관광단지 일대"))
                .isEqualTo("보문호");
        }
    }

    @Nested
    @DisplayName("빈 값과 경계")
    class Edges {

        @Test
        @DisplayName("null 과 공백은 그대로 돌려준다 — 판단은 호출부가 한다")
        void passesThroughBlank() {
            assertThat(AreaQueryNormalizer.toSearchTerm(null)).isNull();
            assertThat(AreaQueryNormalizer.toSearchTerm("   ")).isEqualTo("   ");
        }

        @Test
        @DisplayName("구분자로 시작하면 줄인 결과가 비므로 원문을 지킨다")
        void keepsOriginalWhenReducedToBlank() {
            assertThat(AreaQueryNormalizer.toSearchTerm("·대릉원 일대"))
                .isEqualTo("·대릉원 일대");
        }

        @Test
        @DisplayName("줄일 것이 없는 단순 지역명은 그대로다")
        void keepsPlainName() {
            assertThat(AreaQueryNormalizer.toSearchTerm("경주")).isEqualTo("경주");
            assertThat(AreaQueryNormalizer.toSearchTerm("  경주  ")).isEqualTo("경주");
        }
    }

    @Nested
    @DisplayName("시더 쿼리에 실제로 반영된다")
    class Integration {

        @Test
        @DisplayName("권역 라벨이 검색 가능한 지명으로 바뀐 채 쿼리에 실린다")
        void appliesToSeedQuery() {
            assertThat(NaverLocalSeedSource.buildQuery("황리단길·대릉원 일대",
                backend.yourtrip.global.ai.route.SlotType.CAFE, null))
                .isEqualTo("황리단길 카페");
        }

        @Test
        @DisplayName("modifier 어순은 그대로 지킨다 — 4-3 실측으로 확정한 표기다")
        void keepsModifierOrder() {
            assertThat(NaverLocalSeedSource.buildQuery("황리단길·대릉원 일대",
                backend.yourtrip.global.ai.route.SlotType.CAFE, StyleTag.ROOFTOP))
                .isEqualTo("황리단길 루프탑 카페");
        }
    }
}
