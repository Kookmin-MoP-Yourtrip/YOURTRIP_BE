package backend.yourtrip.global.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import backend.yourtrip.global.ai.agent.CuratedChoiceValidator.CurationOutcome;
import backend.yourtrip.global.ai.agent.dto.CuratorResponse;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.pipeline.CuratedPlace;
import backend.yourtrip.global.ai.pipeline.CuratedSlot;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.route.SlotType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CuratedChoiceValidator (ROADMAP 6-7)")
class CuratedChoiceValidatorTest {

    private static final int DAY = 1;
    private static final double LAT = 35.8386877792;
    private static final double LON = 129.2104983997;

    /** 후보 목록: 0=대릉원(LISTED), 1=첨성대(SEEDED). 6-7 검증의 대조본이다. */
    private static final CandidatePool POOL = new CandidatePool(List.of(
        new CandidateSlot(DAY, SlotType.ATTRACTION, List.of(
            candidate("대릉원", CandidateSourceType.LISTED, null),
            candidate("첨성대", CandidateSourceType.SEEDED, 1)))));

    @Nested
    @DisplayName("멀쩡한 응답")
    class WellFormed {

        @Test
        @DisplayName("강등이 0건이고 선택이 그대로 남는다")
        void keepsValidChoices() {
            CurationOutcome outcome = validate(slot(0, "ATTRACTION",
                choice("LISTED", 0, "대릉원"), choice("SEEDED", 1, "첨성대")));

            assertThat(outcome.hasDemotions()).isFalse();
            assertThat(outcome.day().slots()).hasSize(1);
            assertThat(outcome.day().slots().getFirst().choices())
                .extracting(CuratedPlace::placeName).containsExactly("대릉원", "첨성대");
        }

        @Test
        @DisplayName("출처는 모델이 아니라 목록이 정한다 — 이 값이 5-6 메트릭의 source 태그가 된다")
        void takesSourceFromTheList() {
            CurationOutcome outcome = validate(slot(0, "ATTRACTION",
                choice("SEEDED", 0, "대릉원")));

            assertThat(outcome.day().slots().getFirst().choices().getFirst().source())
                .isEqualTo(CandidateSourceType.LISTED);
            assertThat(outcome.hasDemotions()).isFalse();
        }

        @Test
        @DisplayName("SUGGESTED 는 목록을 보지 않고 통과시킨다 — listIndex 는 비운다")
        void passesSuggestedThrough() {
            CurationOutcome outcome = validate(slot(0, "ATTRACTION",
                choice("SUGGESTED", 7, "황남빵 본점")));

            CuratedPlace place = outcome.day().slots().getFirst().choices().getFirst();
            assertThat(place.source()).isEqualTo(CandidateSourceType.SUGGESTED);
            assertThat(place.listIndex()).isNull();
            assertThat(outcome.hasDemotions()).isFalse();
        }
    }

    @Nested
    @DisplayName("강등")
    class Demotion {

        @Test
        @DisplayName("인덱스가 범위를 벗어나면 강등한다 — 버리지 않는 이유는 이름이 실존할 수 있어서다")
        void demotesOutOfRangeIndex() {
            CurationOutcome outcome = validate(slot(0, "ATTRACTION",
                choice("SEEDED", 9, "천마총")));

            assertDemotedTo("천마총", outcome);
            assertThat(outcome.demotions())
                .containsExactly(entry(DemotionReason.INDEX_OUT_OF_RANGE, 1));
        }

        @Test
        @DisplayName("목록에서 골랐다면서 listIndex 가 없으면 강등한다")
        void demotesMissingIndex() {
            CurationOutcome outcome = validate(slot(0, "ATTRACTION",
                choice("LISTED", null, "천마총")));

            assertThat(outcome.demotions())
                .containsExactly(entry(DemotionReason.INDEX_OUT_OF_RANGE, 1));
        }

        @Test
        @DisplayName("인덱스는 맞는데 이름이 다르면 강등한다 — 인덱스만 보면 통과하는 위조다")
        void demotesNameMismatch() {
            CurationOutcome outcome = validate(slot(0, "ATTRACTION",
                choice("LISTED", 0, "황남빵 본점")));

            assertDemotedTo("황남빵 본점", outcome);
            assertThat(outcome.demotions())
                .containsExactly(entry(DemotionReason.NAME_MISMATCH, 1));
        }

        @Test
        @DisplayName("알 수 없는 source 는 강등한다")
        void demotesUnknownSource() {
            CurationOutcome outcome = validate(slot(0, "ATTRACTION",
                choice("NAVER", 0, "대릉원")));

            assertThat(outcome.demotions())
                .containsExactly(entry(DemotionReason.UNKNOWN_SOURCE, 1));
        }

        @Test
        @DisplayName("슬롯 타입이 어긋나면 그 자리의 선택을 전부 강등한다 — 인덱스가 가리키는 목록이 다르다")
        void demotesEveryChoiceOnSlotMismatch() {
            CurationOutcome outcome = validate(slot(0, "CAFE",
                choice("LISTED", 0, "대릉원"), choice("SEEDED", 1, "첨성대")));

            assertThat(outcome.day().slots().getFirst().choices())
                .extracting(CuratedPlace::source)
                .containsOnly(CandidateSourceType.SUGGESTED);
            assertThat(outcome.demotions()).containsExactly(entry(DemotionReason.SLOT_MISMATCH, 2));
        }

        @Test
        @DisplayName("자리 종류는 Planner 것을 유지한다 — 정본은 Planner 다")
        void keepsPlannerSlotType() {
            CurationOutcome outcome = validate(slot(0, "CAFE", choice("LISTED", 0, "대릉원")));

            assertThat(outcome.day().slots().getFirst().slotType()).isEqualTo(SlotType.ATTRACTION);
        }
    }

    @Nested
    @DisplayName("폐기 — 강등이 아니라 버린다")
    class Discard {

        @Test
        @DisplayName("없는 자리를 지목하면 그 슬롯을 버린다 — 놓을 자리가 없어 강등할 수도 없다")
        void discardsUnknownSlotIndex() {
            CurationOutcome outcome = validate(slot(5, "ATTRACTION", choice("LISTED", 0, "대릉원")));

            assertThat(outcome.day().slots()).hasSize(1);
            assertThat(outcome.day().slots().getFirst().choices()).isEmpty();
            assertThat(outcome.demotions()).isEmpty();
        }

        @Test
        @DisplayName("같은 자리를 두 번 채우면 먼저 온 것을 쓴다")
        void keepsFirstOfDuplicateSlots() {
            CurationOutcome outcome = CuratedChoiceValidator.validate(day(SlotType.ATTRACTION),
                POOL, new CuratorResponse(DAY, List.of(
                    slot(0, "ATTRACTION", choice("LISTED", 0, "대릉원")),
                    slot(0, "ATTRACTION", choice("SEEDED", 1, "첨성대")))));

            assertThat(outcome.day().slots().getFirst().choices())
                .extracting(CuratedPlace::placeName).containsExactly("대릉원");
        }

        @Test
        @DisplayName("상호명이 비면 버린다 — SUGGESTED 로 내려도 카카오에 물어볼 것이 없다")
        void discardsBlankName() {
            CurationOutcome outcome = validate(slot(0, "ATTRACTION",
                choice("SUGGESTED", null, " "), choice("LISTED", 0, "대릉원")));

            assertThat(outcome.day().slots().getFirst().choices())
                .extracting(CuratedPlace::placeName).containsExactly("대릉원");
            assertThat(outcome.demotions()).isEmpty();
        }

        @Test
        @DisplayName("선택이 3개를 넘으면 앞에서부터 자른다 — 순서가 곧 선호도다")
        void trimsExtraChoices() {
            CurationOutcome outcome = validate(slot(0, "ATTRACTION",
                choice("LISTED", 0, "대릉원"), choice("SEEDED", 1, "첨성대"),
                choice("SUGGESTED", null, "황남빵"), choice("SUGGESTED", null, "교촌마을")));

            assertThat(outcome.day().slots().getFirst().choices())
                .hasSize(CuratedChoiceValidator.MAX_CHOICES)
                .extracting(CuratedPlace::placeName)
                .containsExactly("대릉원", "첨성대", "황남빵");
        }
    }

    @Nested
    @DisplayName("자리 구성은 Planner 가 정한다")
    class SlotComposition {

        @Test
        @DisplayName("응답이 비어도 Planner 의 자리는 전부 남는다 — 7-3 이 그 자리를 채운다")
        void keepsEverySlotWhenResponseIsEmpty() {
            CurationOutcome outcome = CuratedChoiceValidator.validate(
                day(SlotType.ATTRACTION, SlotType.MEAL, SlotType.CAFE), POOL, null);

            assertThat(outcome.day().slots())
                .hasSize(3)
                .extracting(CuratedSlot::slotType)
                .containsExactly(SlotType.ATTRACTION, SlotType.MEAL, SlotType.CAFE);
            assertThat(outcome.day().slots()).allSatisfy(
                slot -> assertThat(slot.choices()).isEmpty());
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private static CurationOutcome validate(CuratorResponse.Slot slot) {
        return CuratedChoiceValidator.validate(day(SlotType.ATTRACTION), POOL,
            new CuratorResponse(DAY, List.of(slot)));
    }

    private static void assertDemotedTo(String placeName, CurationOutcome outcome) {
        assertThat(outcome.day().slots().getFirst().choices())
            .singleElement()
            .satisfies(place -> {
                assertThat(place.source()).isEqualTo(CandidateSourceType.SUGGESTED);
                assertThat(place.listIndex()).isNull();
                assertThat(place.placeName()).isEqualTo(placeName);
            });
    }

    private static PlannerDayPlan day(SlotType... slots) {
        return PlannerDayPlan.of(DAY, "황리단길 일대", "대릉원", List.of(slots));
    }

    private static CuratorResponse.Slot slot(int slotIndex, String slotType,
        CuratorResponse.Choice... choices) {
        return new CuratorResponse.Slot(slotIndex, slotType, List.of(choices));
    }

    private static CuratorResponse.Choice choice(String source, Integer listIndex,
        String placeName) {
        return new CuratorResponse.Choice(source, listIndex, placeName);
    }

    private static PlaceCandidate candidate(String name, CandidateSourceType source,
        Integer seedRank) {
        return new PlaceCandidate(source, name, "경주시 황남동", LAT, LON, SlotType.ATTRACTION,
            Set.of(), seedRank, null, 0.4, "A02");
    }
}
