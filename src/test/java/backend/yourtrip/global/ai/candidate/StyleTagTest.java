package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StyleTag#appliesTo} — 수식어를 어느 슬롯 질의에 붙일지 (이슈 #179).
 *
 * <p>규칙의 근거는 질의 감사 실측이다(기준선 30요청, 수식어 질의 84%가 빈손). 여기서는 감사가
 * 직접 잰 6개 태그의 판정과 세 갈래의 경계를 고정한다.
 */
@DisplayName("StyleTag.appliesTo — 수식어 적용 슬롯 (#179)")
class StyleTagTest {

    private static Set<SlotType> slotsOf(StyleTag tag) {
        return Arrays.stream(SlotType.values()).filter(tag::appliesTo)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(SlotType.class)));
    }

    @Test
    @DisplayName("가게 속성(조용한·루프탑·주차·프리미엄)은 식음·쇼핑에만 붙는다")
    void venueTagsOnlyOnVenueSlots() {
        Set<SlotType> venue = EnumSet.of(SlotType.MEAL, SlotType.CAFE, SlotType.SHOPPING);
        for (StyleTag tag : Set.of(StyleTag.QUIET, StyleTag.ROOFTOP, StyleTag.PARKING_AVAILABLE,
            StyleTag.EXPENSIVE)) {
            assertThat(slotsOf(tag)).as(tag.name()).isEqualTo(venue);
        }
    }

    @Test
    @DisplayName("자연은 관광명소·산책로에도 붙고 체험·전망대에는 안 붙는다")
    void natureReachesAttractionAndStroll() {
        assertThat(slotsOf(StyleTag.NATURE)).isEqualTo(EnumSet.of(SlotType.MEAL, SlotType.CAFE,
            SlotType.SHOPPING, SlotType.ATTRACTION, SlotType.STROLL));
    }

    @Test
    @DisplayName("역세권은 어느 슬롯에도 붙지 않는다 — 전 슬롯에서 0건이 97~100%였다")
    void nearStationNowhere() {
        assertThat(slotsOf(StyleTag.NEAR_STATION)).isEmpty();
    }

    @Test
    @DisplayName("체험·전망대에는 어떤 수식어도 붙지 않는다")
    void noModifierOnExperienceOrViewpoint() {
        for (StyleTag tag : StyleTag.values()) {
            assertThat(tag.appliesTo(SlotType.EXPERIENCE)).as(tag.name()).isFalse();
            assertThat(tag.appliesTo(SlotType.VIEWPOINT)).as(tag.name()).isFalse();
        }
    }
}
