package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.ai.route.SlotType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link NaverCategoryMapper} 단위 테스트 (ROADMAP 4-4).
 *
 * <p>표기가 <b>4-2 실호출로 받은 실제 값</b>인 케이스는 주석으로 표시했다. 나머지는 네이버 분류
 * 체계에서 예상되는 형태이며, 5-9 후보 공급 실측에서 실제 분포를 확인하고 보강한다.
 */
@DisplayName("NaverCategoryMapper — 분류 문자열에서 슬롯 타입 판정 (ROADMAP 4-4)")
class NaverCategoryMapperTest {

    @Nested
    @DisplayName("실측 값 — 4-2가 돌려준 실제 분류")
    class RealSamples {

        @Test
        @DisplayName("음식점>카페,디저트는 CAFE다 — 최상위만 보면 MEAL이 되어 카페가 카페 슬롯에서 탈락한다")
        void 최상위가_음식점이어도_카페면_카페다() {
            assertThat(NaverCategoryMapper.toSlotType("음식점>카페,디저트"))
                .contains(SlotType.CAFE);
        }

        @Test
        @DisplayName("카페,디저트>베이커리는 CAFE다")
        void 베이커리도_카페다() {
            assertThat(NaverCategoryMapper.toSlotType("카페,디저트>베이커리"))
                .contains(SlotType.CAFE);
        }

        @Test
        @DisplayName("브런치카페는 구분자가 없어도 CAFE다 — 계층이 없는 값이 실제로 온다")
        void 구분자가_없어도_판정한다() {
            assertThat(NaverCategoryMapper.toSlotType("브런치카페"))
                .contains(SlotType.CAFE);
        }
    }

    @Nested
    @DisplayName("가장 구체적인 분류가 이긴다")
    class MostSpecificWins {

        @Test
        @DisplayName("뒤쪽이 매핑에 없으면 앞쪽으로 떨어진다 — 설계가 의도한 동작은 보존된다")
        void 세부_분류가_없으면_상위로_떨어진다() {
            assertThat(NaverCategoryMapper.toSlotType("음식점>한식>국밥"))
                .contains(SlotType.MEAL);
        }

        @ParameterizedTest
        @ValueSource(strings = {"음식점>한식", "음식점>중식>짜장면", "음식점>일식", "한식>국밥"})
        @DisplayName("일반 음식점은 MEAL이다")
        void 음식점(String category) {
            assertThat(NaverCategoryMapper.toSlotType(category)).contains(SlotType.MEAL);
        }

        @ParameterizedTest
        @ValueSource(strings = {"관광,명소>유적지", "문화,예술>박물관", "관광,명소>전망대", "여행>공원"})
        @DisplayName("관광·문화 계열은 ATTRACTION으로 모은다")
        void 관광(String category) {
            assertThat(NaverCategoryMapper.toSlotType(category)).contains(SlotType.ATTRACTION);
        }

        @ParameterizedTest
        @ValueSource(strings = {"쇼핑,유통>백화점", "쇼핑,유통>전통시장", "쇼핑,유통>아울렛"})
        @DisplayName("쇼핑 계열은 SHOPPING이다")
        void 쇼핑(String category) {
            assertThat(NaverCategoryMapper.toSlotType(category)).contains(SlotType.SHOPPING);
        }

        @ParameterizedTest
        @ValueSource(strings = {"레저,스포츠>체험", "레저,스포츠>놀이시설"})
        @DisplayName("레저 계열은 ACTIVITY다")
        void 레저(String category) {
            assertThat(NaverCategoryMapper.toSlotType(category)).contains(SlotType.EXPERIENCE);
        }
    }

    @Nested
    @DisplayName("모르는 값 — 버리지 않고 표시한다")
    class Unknown {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "생활,편의>세탁소", "의료>병원", "교통>주차장"})
        @DisplayName("매핑에 없으면 empty다 — '버려라'가 아니라 '모르겠다'는 뜻이다")
        void 모르는_분류는_empty다(String category) {
            assertThat(NaverCategoryMapper.toSlotType(category)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"교통>주차장", "생활,편의>세차장", "교통>기차역", "교통>자차렌트"})
        @DisplayName("한 글자 토큰이 만든 오판정을 막는다 — 차(찻집)가 주차장을 카페로 만들었다")
        void 한_글자_토큰_오판정을_막는다(String category) {
            assertThat(NaverCategoryMapper.toSlotType(category))
                .as("부분 문자열 매칭에서 한 글자 토큰은 무관한 분류를 대량으로 삼킨다")
                .isEmpty();
        }

        @Test
        @DisplayName("null도 empty다")
        void null도_empty다() {
            assertThat(NaverCategoryMapper.toSlotType(null)).isEmpty();
        }

        @Test
        @DisplayName("모르는 분류는 어떤 슬롯에도 통과한다 — 네이버가 분류를 늘려도 후보가 사라지지 않는다")
        void 모르는_분류는_통과한다() {
            for (SlotType slotType : SlotType.values()) {
                assertThat(NaverCategoryMapper.isCompatibleWith("생활,편의>세탁소", slotType))
                    .as("%s 슬롯", slotType)
                    .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("슬롯 적합성 — 5-3의 하드 제약이 쓸 판정")
    class Compatibility {

        @Test
        @DisplayName("점심 슬롯에 카페가 들어가지 않는다 — 이 제약이 이 매핑의 존재 이유다")
        void 슬롯이_다르면_막는다() {
            assertThat(NaverCategoryMapper.isCompatibleWith("음식점>카페,디저트", SlotType.MEAL))
                .isFalse();
            assertThat(NaverCategoryMapper.isCompatibleWith("음식점>한식", SlotType.CAFE))
                .isFalse();
            assertThat(NaverCategoryMapper.isCompatibleWith("쇼핑,유통>백화점", SlotType.MEAL))
                .isFalse();
        }

        @Test
        @DisplayName("관광·전망대·산책로는 서로 통한다 — 네이버 분류가 셋을 구분하지 못한다")
        void 관광_계열은_서로_통한다() {
            for (SlotType slotType : new SlotType[] {
                SlotType.ATTRACTION, SlotType.VIEWPOINT, SlotType.STROLL}) {
                assertThat(NaverCategoryMapper.isCompatibleWith("관광,명소>유적지", slotType))
                    .as("%s 슬롯에 관광 분류가 막히면 정당한 후보가 전부 탈락한다", slotType)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("관광 계열이라도 식사·카페 슬롯에는 못 들어간다")
        void 관광은_식사_슬롯에_못_간다() {
            assertThat(NaverCategoryMapper.isCompatibleWith("관광,명소>유적지", SlotType.MEAL))
                .isFalse();
            assertThat(NaverCategoryMapper.isCompatibleWith("관광,명소>유적지", SlotType.CAFE))
                .isFalse();
        }

        @Test
        @DisplayName("체험은 관광과 별개다 — 액티비티 슬롯의 성격이 다르다")
        void 체험은_관광과_다르다() {
            assertThat(NaverCategoryMapper.isCompatibleWith("레저,스포츠>체험", SlotType.EXPERIENCE))
                .isTrue();
            assertThat(NaverCategoryMapper.isCompatibleWith("레저,스포츠>체험", SlotType.ATTRACTION))
                .isFalse();
        }

        @Test
        @DisplayName("술집 계열은 MEAL이되 따로 표시된다 — 점심에 호프집을 막을 재료다")
        void 술집_계열을_가려낸다() {
            assertThat(NaverCategoryMapper.toSlotType("음식점>술집>호프"))
                .as("저녁 슬롯에는 정당한 후보이므로 MEAL 매핑 자체는 맞다")
                .contains(SlotType.MEAL);
            assertThat(NaverCategoryMapper.isBarLike("음식점>술집>호프")).isTrue();
            assertThat(NaverCategoryMapper.isBarLike("음식점>이자카야")).isTrue();
            assertThat(NaverCategoryMapper.isBarLike("음식점>한식>국밥"))
                .as("일반 식당까지 표시하면 감점이 무의미해진다")
                .isFalse();
            assertThat(NaverCategoryMapper.isBarLike(null)).isFalse();
        }

        @Test
        @DisplayName("같은 슬롯끼리는 당연히 통한다")
        void 같은_슬롯은_통한다() {
            assertThat(NaverCategoryMapper.isCompatibleWith("음식점>한식", SlotType.MEAL)).isTrue();
            assertThat(NaverCategoryMapper.isCompatibleWith("브런치카페", SlotType.CAFE)).isTrue();
        }
    }
}
