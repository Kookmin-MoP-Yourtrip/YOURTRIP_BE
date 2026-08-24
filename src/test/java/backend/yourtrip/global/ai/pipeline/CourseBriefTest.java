package backend.yourtrip.global.ai.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.route.TravelMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 묻는 것은 <b>"사용자가 고른 이동수단이 최적화까지 도달하는가"</b>다 (ROADMAP 7-1).
 *
 * <p>{@code WALK}·{@code CAR}는 {@code travelMode} 카테고리 키워드로 요청에 이미 실려 오지만,
 * 그 값을 {@link TravelMode}로 옮기는 자리가 지금까지 없었다. 옮기지 않으면 뚜벅이 여행도
 * {@code UNSPECIFIED}(시속 15km)로 계산돼 <b>이동시간이 어긋난 채 시각이 확정된다.</b>
 */
@DisplayName("CourseBrief — 파이프라인 입력 (ROADMAP 7-1)")
class CourseBriefTest {

    @Nested
    @DisplayName("이동수단을 키워드에서 읽는다")
    class TravelModeFromKeywords {

        @Test
        @DisplayName("뚜벅이를 고르면 WALK 다")
        void walk() {
            assertThat(CourseBrief.of("경주", 2, List.of(KeywordType.WALK, KeywordType.SOLO))
                .travelMode()).isEqualTo(TravelMode.WALK);
        }

        @Test
        @DisplayName("자차를 고르면 CAR 다")
        void car() {
            assertThat(CourseBrief.of("경주", 2, List.of(KeywordType.CAR)).travelMode())
                .isEqualTo(TravelMode.CAR);
        }

        @Test
        @DisplayName("고르지 않았으면 UNSPECIFIED 다")
        void none() {
            assertThat(CourseBrief.of("경주", 2, List.of(KeywordType.SOLO)).travelMode())
                .isEqualTo(TravelMode.UNSPECIFIED);
        }

        @Test
        @DisplayName("둘 다 골랐으면 모순이므로 UNSPECIFIED 다 — 임의로 한쪽을 고르지 않는다")
        void both() {
            assertThat(CourseBrief.of("경주", 2, List.of(KeywordType.WALK, KeywordType.CAR))
                .travelMode()).isEqualTo(TravelMode.UNSPECIFIED);
        }
    }

    @Nested
    @DisplayName("입력을 방어한다")
    class Validation {

        @Test
        @DisplayName("location 은 필수다 — 모든 검색어의 접두사가 된다")
        void locationIsRequired() {
            assertThatThrownBy(() -> CourseBrief.of(null, 1, List.of()))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("days 가 0 이하면 거부한다 — LLM 호출 수이자 병렬 팬아웃이다")
        void daysMustBePositive() {
            assertThatThrownBy(() -> CourseBrief.of("경주", 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("keywords 가 null 이어도 빈 목록으로 흡수한다")
        void nullKeywords() {
            CourseBrief brief = CourseBrief.of("경주", 1, null);

            assertThat(brief.keywords()).isEmpty();
            assertThat(brief.travelMode()).isEqualTo(TravelMode.UNSPECIFIED);
        }
    }
}
