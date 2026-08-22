package backend.yourtrip.global.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.candidate.StyleTag;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.route.SlotType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CandidateListRenderer (ROADMAP 6-4)")
class CandidateListRendererTest {

    private static final double LAT = 35.8386877792;
    private static final double LON = 129.2104983997;

    @Nested
    @DisplayName("채울 자리")
    class Slots {

        @Test
        @DisplayName("자리를 번호로 지목하게 한다 — 같은 타입이 두 자리면 타입으로는 구분할 수 없다")
        void numbersEverySlotPosition() {
            String rendered = CandidateListRenderer.renderSlots(day(
                SlotType.ATTRACTION, SlotType.MEAL, SlotType.CAFE, SlotType.ATTRACTION));

            assertThat(rendered).isEqualTo("""
                0. ATTRACTION
                1. MEAL
                2. CAFE
                3. ATTRACTION""");
        }
    }

    @Nested
    @DisplayName("후보 목록")
    class Candidates {

        @Test
        @DisplayName("번호는 0부터다 — CandidateSlot.at(listIndex)가 리스트 인덱스를 그대로 쓴다")
        void indexesFromZero() {
            String rendered = CandidateListRenderer.renderCandidates(day(SlotType.CAFE),
                pool(SlotType.CAFE, seeded("카페 A", 1), seeded("카페 B", 2)));

            assertThat(rendered).contains("0. [seed 1위] 카페 A").contains("1. [seed 2위] 카페 B");
        }

        @Test
        @DisplayName("출처 표식을 둘 다 붙인다 — 병합된 후보는 시드이면서 관광 등록이다")
        void marksBothSources() {
            PlaceCandidate merged = new PlaceCandidate(CandidateSourceType.LISTED, "대릉원", "경주시",
                LAT, LON, SlotType.ATTRACTION, Set.of(StyleTag.HISTORY), 1, null, 0.42, "A02");

            String rendered = CandidateListRenderer.renderCandidates(day(SlotType.ATTRACTION),
                pool(SlotType.ATTRACTION, merged));

            assertThat(rendered).contains("0. [seed 1위·관광] 대릉원 · 역사 · 0.4km");
        }

        @Test
        @DisplayName("시드에 못 든 관광 후보는 관광 표식만 붙는다")
        void marksOfficialOnly() {
            String rendered = CandidateListRenderer.renderCandidates(day(SlotType.ATTRACTION),
                pool(SlotType.ATTRACTION, listed("계림")));

            assertThat(rendered).contains("0. [관광] 계림");
        }

        @Test
        @DisplayName("스타일 태그는 enum 순서로 정렬한다 — Set.copyOf 의 순서는 실행마다 달라진다")
        void sortsStyleTagsDeterministically() {
            PlaceCandidate candidate = new PlaceCandidate(CandidateSourceType.LISTED, "대릉원",
                "경주시", LAT, LON, SlotType.ATTRACTION,
                Set.of(StyleTag.HISTORY, StyleTag.HANOK, StyleTag.NATURE), null, null, 0.4, "A02");
            CandidatePool pool = pool(SlotType.ATTRACTION, candidate);

            String first = CandidateListRenderer.renderCandidates(day(SlotType.ATTRACTION), pool);
            String second = CandidateListRenderer.renderCandidates(day(SlotType.ATTRACTION), pool);

            assertThat(first).isEqualTo(second).contains("한옥/자연/역사");
        }

        @Test
        @DisplayName("거리를 모르면 그 자리를 비운다 — anchor 좌표를 못 얻었을 때다")
        void omitsUnknownDistance() {
            PlaceCandidate candidate = new PlaceCandidate(CandidateSourceType.SEEDED, "카페 A",
                "경주시", LAT, LON, SlotType.CAFE, Set.of(), 1, null, null, "카페");

            String rendered = CandidateListRenderer.renderCandidates(day(SlotType.CAFE),
                pool(SlotType.CAFE, candidate));

            assertThat(rendered).isEqualTo("""
                ### CAFE (슬롯 0)
                0. [seed 1위] 카페 A""");
        }

        @Test
        @DisplayName("같은 타입이 여러 자리면 목록은 한 번만 싣고 자리 번호를 함께 적는다")
        void sharesOneListAcrossPositions() {
            String rendered = CandidateListRenderer.renderCandidates(
                day(SlotType.ATTRACTION, SlotType.MEAL, SlotType.ATTRACTION),
                pool(SlotType.ATTRACTION, listed("계림")));

            assertThat(rendered).contains("### ATTRACTION (슬롯 0, 2)");
            assertThat(rendered.split("### ATTRACTION", -1)).hasSize(2);
        }

        @Test
        @DisplayName("후보가 없는 슬롯도 목록에 남긴다 — 빈 목록은 실패가 아니라 degrade 다")
        void keepsEmptySlotVisible() {
            String rendered = CandidateListRenderer.renderCandidates(day(SlotType.CAFE),
                CandidatePool.empty());

            assertThat(rendered).contains("### CAFE (슬롯 0)").contains("후보 없음");
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private static PlannerDayPlan day(SlotType... slots) {
        return PlannerDayPlan.of(1, "황리단길 일대", "대릉원", List.of(slots));
    }

    private static CandidatePool pool(SlotType slotType, PlaceCandidate... candidates) {
        return new CandidatePool(List.of(new CandidateSlot(1, slotType, List.of(candidates))));
    }

    private static PlaceCandidate seeded(String name, int seedRank) {
        return new PlaceCandidate(CandidateSourceType.SEEDED, name, "경주시 황남동", LAT, LON,
            SlotType.CAFE, Set.of(), seedRank, null, 0.4, "음식점>카페");
    }

    private static PlaceCandidate listed(String name) {
        return new PlaceCandidate(CandidateSourceType.LISTED, name, "경주시", LAT, LON,
            SlotType.ATTRACTION, Set.of(StyleTag.NATURE), null, null, 1.2, "A01");
    }
}
