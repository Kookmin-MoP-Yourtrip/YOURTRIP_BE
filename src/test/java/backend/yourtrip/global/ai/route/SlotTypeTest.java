package backend.yourtrip.global.ai.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@code SlotType} 단위 테스트 (ROADMAP 3-1).
 *
 * <p><b>이 테스트는 계산을 검증하지 않는다 — 값을 고정한다.</b> {@code SlotType}이 가진 다섯 필드는
 * 서로 다른 세 단계가 읽는다: 체류시간(기본·최대)은 3단계 시간 모델과 탄력 체류가,
 * {@code popularityWeight}는 4-5의 {@code rankScore}가, {@code allowedCategoryCodes}는 5-3의
 * 카테고리 하드 제약이 쓴다.
 * 값 하나를 무심코 고치면 <b>세 곳의 동작이 조용히 함께 움직인다.</b> 여기서 전부 못박아 두면
 * 그 변경이 반드시 이 테스트를 깨뜨려 리뷰 대상이 된다.
 *
 * <p>그래서 "MEAL은 75분이어야 한다" 같은 항목이 동어반복처럼 보여도 남겨둔다. 이 테스트의 목적은
 * 옳음의 증명이 아니라 <b>변경 감지</b>다.
 */
@DisplayName("SlotType — 슬롯별 체류시간·가중치·허용 카테고리 (ROADMAP 3-1)")
class SlotTypeTest {

    @Nested
    @DisplayName("모든 상수가 지켜야 하는 불변식")
    class Invariants {

        @ParameterizedTest
        @EnumSource(SlotType.class)
        @DisplayName("체류시간은 양수다 — 0이면 시간 모델에서 두 장소가 같은 시각에 겹친다")
        void stayMinutesArePositive(SlotType slotType) {
            assertThat(slotType.getDefaultStayMinutes())
                .as("%s 의 체류시간", slotType)
                .isPositive();
        }

        @ParameterizedTest
        @EnumSource(SlotType.class)
        @DisplayName("최대 체류는 기본 체류 이상이다 — 뒤집히면 stretch 여력(max − 기본)이 음수가 된다")
        void maxStayIsAtLeastDefaultStay(SlotType slotType) {
            assertThat(slotType.getMaxStayMinutes())
                .as("%s 의 최대 체류시간", slotType)
                .isGreaterThanOrEqualTo(slotType.getDefaultStayMinutes());
        }

        @ParameterizedTest
        @EnumSource(SlotType.class)
        @DisplayName("searchHint 는 비어 있지 않다 — Curator 폴백 검색어가 공백이면 검색이 무의미해진다")
        void searchHintIsNotBlank(SlotType slotType) {
            assertThat(slotType.getSearchHint())
                .as("%s 의 폴백 검색어", slotType)
                .isNotBlank();
        }

        @ParameterizedTest
        @EnumSource(SlotType.class)
        @DisplayName("popularityWeight 는 0 이상 1 이하다 — rankScore 의 다른 항을 압도하면 안 된다")
        void popularityWeightIsWithinUnitRange(SlotType slotType) {
            assertThat(slotType.getPopularityWeight())
                .as("%s 의 인기도 가중치", slotType)
                .isBetween(0.0, 1.0);
        }

        @ParameterizedTest
        @EnumSource(SlotType.class)
        @DisplayName("허용 카테고리가 비어 있지 않다 — 빈 집합은 5-3 하드 제약에서 전량 탈락을 뜻한다")
        void allowedCategoryCodesAreNotEmpty(SlotType slotType) {
            assertThat(slotType.getAllowedCategoryCodes())
                .as("%s 의 허용 카테고리", slotType)
                .isNotEmpty();
        }

        @ParameterizedTest
        @EnumSource(SlotType.class)
        @DisplayName("허용 카테고리 집합은 불변이다 — enum 상수는 전역 단일 인스턴스라 오염되면 영구적이다")
        void allowedCategoryCodesAreImmutable(SlotType slotType) {
            assertThatThrownBy(() -> slotType.getAllowedCategoryCodes().add("XX9"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("고정된 값")
    class FixedValues {

        @Test
        @DisplayName("체류시간은 SlotType 설계 그대로다")
        void stayMinutesMatchDesignDocument() {
            assertThat(SlotType.EXPERIENCE.getDefaultStayMinutes()).isEqualTo(120);
            assertThat(SlotType.ATTRACTION.getDefaultStayMinutes()).isEqualTo(90);
            assertThat(SlotType.MEAL.getDefaultStayMinutes()).isEqualTo(75);
            assertThat(SlotType.CAFE.getDefaultStayMinutes()).isEqualTo(60);
            assertThat(SlotType.SHOPPING.getDefaultStayMinutes()).isEqualTo(60);
            assertThat(SlotType.STROLL.getDefaultStayMinutes()).isEqualTo(60);
            assertThat(SlotType.VIEWPOINT.getDefaultStayMinutes()).isEqualTo(45);
        }

        @Test
        @DisplayName("최대 체류시간은 이슈 #135 산정 그대로다 — 5슬롯 최악 격차 120분을 흡수하는 하한")
        void maxStayMinutesMatchIssueCalculation() {
            // 좁히면 이른 저녁이 잔여 위반으로 남고, 넓히면 "카페에 2시간" 같은 어색한 체류가 나온다.
            assertThat(SlotType.EXPERIENCE.getMaxStayMinutes()).isEqualTo(180);
            assertThat(SlotType.ATTRACTION.getMaxStayMinutes()).isEqualTo(150);
            assertThat(SlotType.MEAL.getMaxStayMinutes()).isEqualTo(90);
            assertThat(SlotType.CAFE.getMaxStayMinutes()).isEqualTo(90);
            assertThat(SlotType.SHOPPING.getMaxStayMinutes()).isEqualTo(90);
            assertThat(SlotType.STROLL.getMaxStayMinutes()).isEqualTo(90);
            assertThat(SlotType.VIEWPOINT.getMaxStayMinutes()).isEqualTo(60);
        }

        @Test
        @DisplayName("식사와 카페는 업종 코드가 정확히 하나씩이다 — 5-3 하드 제약의 핵심")
        void mealAndCafeAllowExactlyOneCategory() {
            // "점심에 호프집"이 사라지는 근거가 이 두 줄이다. FD6=음식점, CE7=카페.
            assertThat(SlotType.MEAL.getAllowedCategoryCodes()).containsExactly("FD6");
            assertThat(SlotType.CAFE.getAllowedCategoryCodes()).containsExactly("CE7");
        }

        @Test
        @DisplayName("관광·체험은 관광명소와 문화시설을 함께 받는다")
        void attractionAndActivityAllowTourismCodes() {
            assertThat(SlotType.ATTRACTION.getAllowedCategoryCodes())
                .containsExactlyInAnyOrder("AT4", "CT1");
            assertThat(SlotType.EXPERIENCE.getAllowedCategoryCodes())
                .containsExactlyInAnyOrder("AT4", "CT1");
        }

        @Test
        @DisplayName("쇼핑만 유통 계열 코드를 받는다")
        void shoppingAllowsRetailCodes() {
            assertThat(SlotType.SHOPPING.getAllowedCategoryCodes())
                .containsExactlyInAnyOrder("MT1", "CS2");
        }

        @Test
        @DisplayName("인기도 가중치가 높은 것은 식사·카페뿐이다 — 블로그 수가 변별력을 갖는 업종")
        void onlyFoodSlotsTrustBlogVolume() {
            assertThat(SlotType.MEAL.getPopularityWeight()).isEqualTo(1.0);
            assertThat(SlotType.CAFE.getPopularityWeight()).isEqualTo(1.0);

            // 관광지는 "경복궁의 블로그 수가 많다"가 아무 정보도 주지 않아 낮다.
            assertThat(SlotType.ATTRACTION.getPopularityWeight()).isEqualTo(0.2);
            assertThat(SlotType.VIEWPOINT.getPopularityWeight()).isEqualTo(0.2);
            assertThat(SlotType.STROLL.getPopularityWeight()).isEqualTo(0.2);

            assertThat(SlotType.EXPERIENCE.getPopularityWeight()).isEqualTo(0.6);
            assertThat(SlotType.SHOPPING.getPopularityWeight()).isEqualTo(0.6);
        }

        @Test
        @DisplayName("상수는 일곱 개다 — 추가·삭제는 DROP_ORDER 와 프롬프트 어휘를 함께 건드려야 한다")
        void hasSevenConstants() {
            assertThat(SlotType.values()).hasSize(7);
        }
    }
}
