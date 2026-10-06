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

@DisplayName("CuratedChoiceValidator (ROADMAP 6-7, #194)")
class CuratedChoiceValidatorTest {

    private static final int DAY = 1;
    private static final double LAT = 35.8386877792;
    private static final double LON = 129.2104983997;

    /** 후보 목록: ATTRACTION 0=대릉원(LISTED)·1=첨성대(SEEDED), MEAL 0=교리김밥(SEEDED). */
    private static final CandidatePool POOL = new CandidatePool(List.of(
        new CandidateSlot(DAY, SlotType.ATTRACTION, List.of(
            candidate("대릉원", CandidateSourceType.LISTED, null, SlotType.ATTRACTION),
            candidate("첨성대", CandidateSourceType.SEEDED, 1, SlotType.ATTRACTION))),
        new CandidateSlot(DAY, SlotType.MEAL, List.of(
            candidate("교리김밥", CandidateSourceType.SEEDED, 1, SlotType.MEAL)))));

    @Nested
    @DisplayName("멀쩡한 응답")
    class WellFormed {

        @Test
        @DisplayName("목록 번호만 받아도 선택이 남고 강등은 0건이다")
        void keepsValidChoices() {
            CurationOutcome outcome = validate(slot(0, choice(0, null), choice(1, null)));

            assertThat(outcome.hasDemotions()).isFalse();
            assertThat(outcome.day().slots()).hasSize(1);
            assertThat(outcome.day().slots().getFirst().choices())
                .extracting(CuratedPlace::placeName).containsExactly("대릉원", "첨성대");
        }

        @Test
        @DisplayName("출처와 이름은 목록이 정한다 — 모델이 이름을 적어 보내도 쓰지 않는다")
        void takesSourceAndNameFromTheList() {
            CurationOutcome outcome = validate(slot(0, choice(0, "황남빵 본점")));

            assertThat(outcome.day().slots().getFirst().choices()).singleElement()
                .satisfies(place -> {
                    assertThat(place.source()).isEqualTo(CandidateSourceType.LISTED);
                    assertThat(place.listIndex()).isZero();
                    assertThat(place.placeName()).isEqualTo("대릉원");
                });
            assertThat(outcome.hasDemotions()).isFalse();
        }

        @Test
        @DisplayName("번호가 없고 이름이 있으면 목록 밖 제안(SUGGESTED)이다")
        void passesSuggestedThrough() {
            CurationOutcome outcome = validate(slot(0, choice(null, " 황남빵 본점 ")));

            CuratedPlace place = outcome.day().slots().getFirst().choices().getFirst();
            assertThat(place.source()).isEqualTo(CandidateSourceType.SUGGESTED);
            assertThat(place.listIndex()).isNull();
            assertThat(place.placeName()).isEqualTo("황남빵 본점");
            assertThat(outcome.hasDemotions()).isFalse();
        }

        @Test
        @DisplayName("번호는 Planner 가 정한 그 자리 종류의 목록에서 찾는다 — 응답은 자리 종류를 적지 않는다")
        void resolvesIndexAgainstPlannerSlotType() {
            CurationOutcome outcome = CuratedChoiceValidator.validate(
                day(SlotType.ATTRACTION, SlotType.MEAL), POOL,
                new CuratorResponse(List.of(slot(1, choice(0, null)))));

            assertThat(outcome.day().slots().get(1).choices())
                .extracting(CuratedPlace::placeName).containsExactly("교리김밥");
        }
    }

    @Nested
    @DisplayName("강등")
    class Demotion {

        @Test
        @DisplayName("번호가 범위를 벗어났는데 이름이 있으면 강등한다 — 이름이 실존할 수 있어서다")
        void demotesOutOfRangeIndexWithName() {
            CurationOutcome outcome = validate(slot(0, choice(9, "천마총")));

            assertDemotedTo("천마총", outcome);
            assertThat(outcome.demotions())
                .containsExactly(entry(DemotionReason.INDEX_OUT_OF_RANGE, 1));
        }
    }

    @Nested
    @DisplayName("폐기 — 강등이 아니라 버린다")
    class Discard {

        @Test
        @DisplayName("번호가 범위를 벗어났는데 이름이 없으면 버린다 — 카카오에 물어볼 검색어가 없다")
        void discardsOutOfRangeIndexWithoutName() {
            CurationOutcome outcome = validate(slot(0, choice(9, null), choice(1, null)));

            assertThat(outcome.day().slots().getFirst().choices())
                .extracting(CuratedPlace::placeName).containsExactly("첨성대");
            assertThat(outcome.demotions()).isEmpty();
        }

        @Test
        @DisplayName("번호도 이름도 없으면 버린다")
        void discardsChoiceWithoutIndexAndName() {
            CurationOutcome outcome = validate(slot(0, choice(null, " "), choice(0, null)));

            assertThat(outcome.day().slots().getFirst().choices())
                .extracting(CuratedPlace::placeName).containsExactly("대릉원");
            assertThat(outcome.demotions()).isEmpty();
        }

        @Test
        @DisplayName("없는 자리를 지목하면 그 슬롯을 버린다 — 놓을 자리가 없어 강등할 수도 없다")
        void discardsUnknownSlotIndex() {
            CurationOutcome outcome = validate(slot(5, choice(0, null)));

            assertThat(outcome.day().slots()).hasSize(1);
            assertThat(outcome.day().slots().getFirst().choices()).isEmpty();
            assertThat(outcome.demotions()).isEmpty();
        }

        @Test
        @DisplayName("같은 자리를 두 번 채우면 먼저 온 것을 쓴다")
        void keepsFirstOfDuplicateSlots() {
            CurationOutcome outcome = CuratedChoiceValidator.validate(day(SlotType.ATTRACTION),
                POOL, new CuratorResponse(List.of(
                    slot(0, choice(0, null)),
                    slot(0, choice(1, null)))));

            assertThat(outcome.day().slots().getFirst().choices())
                .extracting(CuratedPlace::placeName).containsExactly("대릉원");
        }

        @Test
        @DisplayName("선택이 3개를 넘으면 앞에서부터 자른다 — 순서가 곧 선호도다")
        void trimsExtraChoices() {
            CurationOutcome outcome = validate(slot(0,
                choice(0, null), choice(1, null),
                choice(null, "황남빵"), choice(null, "교촌마을")));

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
            new CuratorResponse(List.of(slot)));
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

    private static CuratorResponse.Slot slot(int slotIndex, CuratorResponse.Choice... choices) {
        return new CuratorResponse.Slot(slotIndex, List.of(choices));
    }

    private static CuratorResponse.Choice choice(Integer listIndex, String placeName) {
        return new CuratorResponse.Choice(listIndex, placeName);
    }

    private static PlaceCandidate candidate(String name, CandidateSourceType source,
        Integer seedRank, SlotType slotType) {
        return new PlaceCandidate(source, name, "경주시 황남동", LAT, LON, slotType,
            Set.of(), seedRank, null, 0.4, "A02");
    }
}
