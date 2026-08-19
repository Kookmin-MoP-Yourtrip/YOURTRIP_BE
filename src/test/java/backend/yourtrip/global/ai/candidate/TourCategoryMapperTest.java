package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link TourCategoryMapper} 단위 테스트 (ROADMAP 4-9).
 *
 * <p>코드 상수가 잔뜩 등장하므로 <b>이름을 주석으로 남긴다.</b> 코드만 적힌 단언은 나중에 읽을 때
 * 사전을 다시 뒤져야 한다.
 */
@DisplayName("TourCategoryMapper — cat3에서 스타일 태그 판정 (ROADMAP 4-9)")
class TourCategoryMapperTest {

    @Nested
    @DisplayName("설계 표 매핑")
    class DesignTable {

        @Test
        @DisplayName("폭포·계곡은 자연·조용함·한적함이다")
        void 자연_계열() {
            assertThat(TourCategoryMapper.styleTagsOf("A01010800"))   // 폭포
                .containsExactlyInAnyOrder(
                    StyleTag.NATURE, StyleTag.QUIET, StyleTag.UNCROWDED);
            assertThat(TourCategoryMapper.styleTagsOf("A01010900"))   // 계곡
                .containsExactlyInAnyOrder(
                    StyleTag.NATURE, StyleTag.QUIET, StyleTag.UNCROWDED);
        }

        @Test
        @DisplayName("전망대는 뷰맛집에 야경까지 붙는다 — 설계가 따로 적어 둔 예외다")
        void 전망대() {
            assertThat(TourCategoryMapper.styleTagsOf("A02050200"))   // 기념탑/기념비/전망대
                .containsExactlyInAnyOrder(StyleTag.GREAT_VIEW, StyleTag.NIGHT_VIEW);
        }

        @Test
        @DisplayName("해수욕장·섬은 뷰맛집이다")
        void 해안_계열() {
            assertThat(TourCategoryMapper.styleTagsOf("A01011200"))   // 해수욕장
                .containsExactlyInAnyOrder(StyleTag.NATURE, StyleTag.GREAT_VIEW);
            assertThat(TourCategoryMapper.styleTagsOf("A01011300"))   // 섬
                .containsExactlyInAnyOrder(StyleTag.NATURE, StyleTag.GREAT_VIEW);
        }

        @Test
        @DisplayName("고택·사찰·고궁은 한옥·역사·조용함이다")
        void 역사_계열() {
            assertThat(TourCategoryMapper.styleTagsOf("A02010800"))   // 사찰
                .containsExactlyInAnyOrder(
                    StyleTag.HISTORY, StyleTag.HANOK, StyleTag.QUIET);
            assertThat(TourCategoryMapper.styleTagsOf("A02010400"))   // 고택
                .containsExactlyInAnyOrder(
                    StyleTag.HISTORY, StyleTag.HANOK, StyleTag.QUIET);
            assertThat(TourCategoryMapper.styleTagsOf("A02010100"))   // 고궁
                .containsExactlyInAnyOrder(StyleTag.HISTORY, StyleTag.HANOK);
        }

        @Test
        @DisplayName("테마공원·전통체험은 아이동반·액티비티다")
        void 체험_계열() {
            assertThat(TourCategoryMapper.styleTagsOf("A02020600"))   // 테마공원
                .contains(StyleTag.KID_FRIENDLY, StyleTag.ACTIVITY);
            assertThat(TourCategoryMapper.styleTagsOf("A02030200"))   // 전통체험
                .contains(StyleTag.KID_FRIENDLY, StyleTag.ACTIVITY);
        }

        @Test
        @DisplayName("이색거리는 시끌벅적·도보접근이다")
        void 이색거리() {
            assertThat(TourCategoryMapper.styleTagsOf("A02030600"))
                .contains(StyleTag.LIVELY, StyleTag.WALKABLE);
        }

        @Test
        @DisplayName("박물관·미술관·공연장은 문화·실내다 — 우천 대안 축이다")
        void 문화시설() {
            assertThat(TourCategoryMapper.styleTagsOf("A02060100"))   // 박물관
                .containsExactlyInAnyOrder(StyleTag.CULTURE, StyleTag.INDOOR);
            assertThat(TourCategoryMapper.styleTagsOf("A02060500"))   // 미술관/화랑
                .containsExactlyInAnyOrder(StyleTag.CULTURE, StyleTag.INDOOR);
            assertThat(TourCategoryMapper.styleTagsOf("A02060600"))   // 공연장
                .containsExactlyInAnyOrder(StyleTag.CULTURE, StyleTag.INDOOR);
        }
    }

    @Nested
    @DisplayName("세 층 합집합 — 구체 규칙이 상위를 지우지 않는다")
    class LayerUnion {

        @Test
        @DisplayName("사찰은 여전히 역사관광지다 — 구체 규칙만 택하면 역사가 조용히 빠진다")
        void 구체_규칙이_상위를_지우지_않는다() {
            assertThat(TourCategoryMapper.styleTagsOf("A02010800"))   // 사찰
                .as("cat2 A0201(역사관광지)의 역사 태그가 살아 있어야 한다")
                .contains(StyleTag.HISTORY);
        }

        @Test
        @DisplayName("사전에 없는 cat3도 상위 성격을 물려받는다 — 코드가 늘어도 무표시가 되지 않는다")
        void 상위를_물려받는다() {
            assertThat(TourCategoryMapper.styleTagsOf("A02010300"))   // 문(사전에 개별 규칙 없음)
                .containsExactly(StyleTag.HISTORY);
            assertThat(TourCategoryMapper.styleTagsOf("A01010100"))   // 국립공원
                .containsExactly(StyleTag.NATURE);
            assertThat(TourCategoryMapper.styleTagsOf("A03020300"))   // 경기장
                .containsExactly(StyleTag.ACTIVITY);
        }

        @Test
        @DisplayName("상위가 비어 있는 계열은 구체 규칙만으로 정해진다")
        void 상위가_빈_계열() {
            // A0202(휴양관광지)에는 온천과 테마공원이 함께 있어 공통 태그가 없다.
            assertThat(TourCategoryMapper.styleTagsOf("A02020300"))   // 온천/욕장/스파
                .containsExactlyInAnyOrder(StyleTag.QUIET, StyleTag.INDOOR);
            assertThat(TourCategoryMapper.styleTagsOf("A02020200"))   // 관광단지
                .as("성격을 말할 수 없는 코드는 빈 집합이다")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("모르는 값 — 버리라는 뜻이 아니다")
    class Unknown {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "ZZ999999", "A04010100", "B02010100"})
        @DisplayName("사전에 없으면 빈 집합이다 — 표시가 없을 뿐 후보는 살아 있다")
        void 모르는_코드는_빈_집합이다(String cat3) {
            assertThat(TourCategoryMapper.styleTagsOf(cat3)).isEmpty();
        }

        @Test
        @DisplayName("null도 빈 집합이다")
        void null도_빈_집합이다() {
            assertThat(TourCategoryMapper.styleTagsOf(null)).isEmpty();
        }

        @Test
        @DisplayName("코드가 짧으면 부분 매칭이 걸리지 않는다")
        void 짧은_코드는_매칭되지_않는다() {
            assertThat(TourCategoryMapper.styleTagsOf("A0")).isEmpty();
            assertThat(TourCategoryMapper.styleTagsOf("A01"))
                .as("cat1만 온 경우는 cat1 규칙이 맞는 것이 옳다")
                .containsExactly(StyleTag.NATURE);
        }

        @Test
        @DisplayName("소문자·공백이 섞여도 판정한다")
        void 표기가_흔들려도_판정한다() {
            assertThat(TourCategoryMapper.styleTagsOf("  a02010800  "))
                .contains(StyleTag.HANOK);
        }
    }

    @Nested
    @DisplayName("공식 코드표 대조 — 죽은 규칙을 막는다")
    class AgainstOfficialCodes {

        /**
         * {@code cat-codes.tsv}는 4-7에서 {@code categoryCode2} 오퍼레이션으로 받아 온 원본이다.
         * 코드를 한 글자 잘못 적은 규칙은 <b>아무 오류 없이 영원히 매칭되지 않으므로</b> 이 대조가
         * 없으면 사람이 알아챌 방법이 없다.
         */
        @Test
        @DisplayName("사전의 모든 코드가 공식 코드표에 존재한다")
        void 사전의_코드가_실재한다() throws IOException {
            Set<String> official = officialCodes();

            assertThat(official).as("코드표 원본을 읽지 못하면 이 테스트는 의미가 없다").isNotEmpty();
            assertThat(TourCategoryMapper.mappedCodes())
                .as("공식 코드표에 없는 코드는 조용히 죽은 규칙이다")
                .allMatch(official::contains);
        }

        @Test
        @DisplayName("우리가 부르는 contentTypeId의 계열만 담는다 — 죽은 코드를 늘리지 않는다")
        void 부르지_않는_계열은_담지_않는다() {
            assertThat(TourCategoryMapper.mappedCodes())
                .as("A04 쇼핑·A05 음식·B02 숙박·C01 추천코스는 12·14·28이 돌려주지 않는다")
                .allMatch(code -> code.startsWith("A01") || code.startsWith("A02")
                    || code.startsWith("A03"));
        }

        private Set<String> officialCodes() throws IOException {
            try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("tour/cat-codes.tsv")) {
                if (stream == null) {
                    return new HashSet<>();
                }
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .map(line -> line.split("\t")[0].trim())
                    .collect(Collectors.toCollection(HashSet::new));
            }
        }
    }
}
