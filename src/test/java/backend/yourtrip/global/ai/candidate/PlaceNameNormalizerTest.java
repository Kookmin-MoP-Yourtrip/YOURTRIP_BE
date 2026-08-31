package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link PlaceNameNormalizer} 단위 테스트 (ROADMAP 4-5 · 4-6).
 *
 * <p>이 규칙은 1-2에서 <b>실측 거짓 음성</b>을 보고 만든 것이라, 그때 문제였던 표기를 그대로
 * 케이스로 남긴다. 근거: {@code BASELINE-ARTIFACT-ANALYSIS.md} 판정 1·2
 */
@DisplayName("PlaceNameNormalizer — 이름 정규화와 유사 판정 (ROADMAP 4-5)")
class PlaceNameNormalizerTest {

    @Nested
    @DisplayName("정규화")
    class Normalize {

        @Test
        @DisplayName("공백·중점·문장부호를 걷어낸다 — 실측 거짓 음성의 원인이었다")
        void stripsNoise() {
            assertThat(PlaceNameNormalizer.normalize("동궁과 월지")).isEqualTo("동궁과월지");
            assertThat(PlaceNameNormalizer.normalize("허균·허난설헌 기념공원"))
                .isEqualTo("허균허난설헌기념공원");
            assertThat(PlaceNameNormalizer.normalize("카페 A-1 (본점)")).isEqualTo("카페a1본점");
        }

        @Test
        @DisplayName("대소문자를 통일한다")
        void lowercases() {
            assertThat(PlaceNameNormalizer.normalize("CAFE Onion")).isEqualTo("cafeonion");
        }

        @Test
        @DisplayName("null은 빈 문자열이다")
        void handlesNull() {
            assertThat(PlaceNameNormalizer.normalize(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("기본 로케일에 흔들리지 않는다")
    class LocaleIndependence {

        private final Locale original = Locale.getDefault();

        @AfterEach
        void restoreLocale() {
            Locale.setDefault(original);
        }

        @Test
        @DisplayName("터키어 로케일에서도 결과가 같다 — 서버 로케일이 dedupe를 바꾸면 안 된다")
        void isStableUnderTurkishLocale() {
            String expected = PlaceNameNormalizer.normalize("CAFE INDIGO");

            Locale.setDefault(Locale.forLanguageTag("tr"));

            assertThat(PlaceNameNormalizer.normalize("CAFE INDIGO")).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("유사 판정 — 포함 관계")
    class Similar {

        @Test
        @DisplayName("정식 명칭과 상호명처럼 한쪽이 길어도 같다고 본다")
        void treatsContainmentAsMatch() {
            assertThat(PlaceNameNormalizer.similar("경주 동궁과 월지", "동궁과월지")).isTrue();
            assertThat(PlaceNameNormalizer.similar("천마총", "천마총(대릉원)")).isTrue();
        }

        @Test
        @DisplayName("전혀 다른 이름은 다르다")
        void rejectsUnrelatedNames() {
            assertThat(PlaceNameNormalizer.similar("천마총", "경주 쌈밥거리")).isFalse();
        }

        @Test
        @DisplayName("비교할 이름이 없으면 같다고 하지 않는다 — 모르는 것을 같다고 하면 후보가 사라진다")
        void refusesEmptyNames() {
            assertThat(PlaceNameNormalizer.similar("", "천마총")).isFalse();
            assertThat(PlaceNameNormalizer.similar("천마총", null)).isFalse();
            assertThat(PlaceNameNormalizer.similar("  ", "천마총")).isFalse();
        }

        @Test
        @DisplayName("포함 규칙은 느슨하다 — 이 테스트가 CandidateMatcher가 거리와 AND로 묶는 이유다")
        void isDeliberatelyLoose() {
            assertThat(PlaceNameNormalizer.similar("왕릉", "경주 내물왕릉"))
                .as("이 판정만으로 합치면 전국의 왕릉이 하나가 된다")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("진포함 판정 — 부속 POI (이슈 #106)")
    class ProperlyContains {

        @Test
        @DisplayName("본체 이름에 수식이 붙어 길어진 부속을 잡는다 — 5-9 실측 표본")
        void detectsSubordinateNames() {
            assertThat(PlaceNameNormalizer.properlyContains("영주댐전망대", "영주댐전망대주차장1"))
                .isTrue();
            assertThat(PlaceNameNormalizer.properlyContains("공주 갑사 철당간", "갑사")).isTrue();
        }

        @Test
        @DisplayName("완전 일치는 부속이 아니다 — 같은 상호의 다른 지점을 뭉치지 않기 위해서다")
        void rejectsExactMatch() {
            assertThat(PlaceNameNormalizer.properlyContains("스타벅스", "스타벅스")).isFalse();
        }

        @Test
        @DisplayName("정규화하면 같아지는 표기도 완전 일치다 — similar와 갈리는 지점")
        void rejectsMatchThatOnlyNormalizationSeparates() {
            assertThat(PlaceNameNormalizer.similar("동궁과 월지", "동궁과월지"))
                .as("소스 간 병합은 이 쌍을 합쳐야 한다")
                .isTrue();
            assertThat(PlaceNameNormalizer.properlyContains("동궁과 월지", "동궁과월지"))
                .as("부속 판정은 이 쌍을 합치면 안 된다")
                .isFalse();
        }

        @Test
        @DisplayName("전혀 다른 이름은 다르다")
        void rejectsUnrelatedNames() {
            assertThat(PlaceNameNormalizer.properlyContains("천마총", "경주 쌈밥거리")).isFalse();
        }

        @Test
        @DisplayName("비교할 이름이 없으면 부속이라고 하지 않는다")
        void refusesEmptyNames() {
            assertThat(PlaceNameNormalizer.properlyContains("", "천마총")).isFalse();
            assertThat(PlaceNameNormalizer.properlyContains("천마총", null)).isFalse();
            assertThat(PlaceNameNormalizer.properlyContains("  ", "천마총")).isFalse();
        }

        @Test
        @DisplayName("진포함도 느슨하다 — CandidateMatcher가 거리와 AND로 묶는 이유는 그대로다")
        void isStillDeliberatelyLoose() {
            assertThat(PlaceNameNormalizer.properlyContains("왕릉", "경주 내물왕릉"))
                .as("이 판정만으로 합치면 전국의 왕릉이 하나가 된다")
                .isTrue();
        }
    }
}
