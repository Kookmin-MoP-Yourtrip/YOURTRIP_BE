package backend.yourtrip.global.ai.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.route.SlotType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 묻는 것은 <b>"Curator 가 비운 자리에 무엇이 들어가는가"</b> 하나다 (ROADMAP 7-3).
 *
 * <p>특히 {@code listIndex} 를 단언하는 이유는 그 값이 <b>조용히 틀릴 수 있는 종류의 값</b>이기
 * 때문이다 — 1-based 로 어긋나도 예외가 나지 않고, {@code GroundingStage} 가 좌표 승계에
 * 실패해 그 후보를 {@code SUGGESTED} 로 강등시킬 뿐이다. 그러면 폴백이 카카오 호출을 늘린다.
 */
@DisplayName("DeterministicCuration — Curator 가 비운 슬롯의 결정론적 채움 (ROADMAP 7-3)")
class DeterministicCurationTest {

    private static PlaceCandidate seeded(String name, int seedRank) {
        return new PlaceCandidate(CandidateSourceType.SEEDED, name, "경북 경주시 " + name + "로 1",
            35.8386877792, 129.2104983997, SlotType.ATTRACTION, Set.of(), seedRank, null, null,
            "관광,명소>유적지");
    }

    private static CandidatePool poolOf(int day, SlotType slotType, String... names) {
        List<PlaceCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            candidates.add(seeded(names[i], i + 1));
        }
        return new CandidatePool(List.of(new CandidateSlot(day, slotType, candidates)));
    }

    /** 기존 단언은 채워진 목록만 보므로 얇게 감싼다 — 집계는 아래 SlotCounts 절이 따로 묻는다. */
    private static List<CuratedDay> fill(List<CuratedDay> curated, CandidatePool pool) {
        return DeterministicCuration.fill(curated, pool).days();
    }

    private static List<CuratedDay> emptyDay(int day, SlotType... slotTypes) {
        List<CuratedSlot> slots = new ArrayList<>();
        for (SlotType slotType : slotTypes) {
            slots.add(new CuratedSlot(slotType, List.of()));
        }
        return List.of(new CuratedDay(day, slots));
    }

    @Nested
    @DisplayName("빈 자리만 채운다")
    class FillsOnlyEmptySlots {

        @Test
        @DisplayName("선택이 비어 있으면 후보 목록 상위 3개가 그 자리에 들어간다")
        void fillsEmptySlotWithTopThree() {
            CandidatePool pool = poolOf(1, SlotType.ATTRACTION, "천마총", "첨성대", "동궁과 월지", "불국사");

            List<CuratedDay> filled =
                fill(emptyDay(1, SlotType.ATTRACTION), pool);

            assertThat(filled).hasSize(1);
            assertThat(filled.get(0).slots().get(0).choices())
                .extracting(CuratedPlace::placeName)
                .containsExactly("천마총", "첨성대", "동궁과 월지");
        }

        @Test
        @DisplayName("이미 선택이 있는 자리는 손대지 않는다 — Curator 의 선호 순서가 최종 순위다")
        void keepsExistingChoices() {
            CandidatePool pool = poolOf(1, SlotType.ATTRACTION, "천마총", "첨성대", "동궁과 월지");
            CuratedPlace chosen = new CuratedPlace(CandidateSourceType.SUGGESTED, null, "석굴암");
            List<CuratedDay> curated = List.of(new CuratedDay(1,
                List.of(new CuratedSlot(SlotType.ATTRACTION, List.of(chosen)))));

            List<CuratedDay> filled = fill(curated, pool);

            assertThat(filled.get(0).slots().get(0).choices()).containsExactly(chosen);
        }

        @Test
        @DisplayName("한 day 안에서 빈 자리와 채워진 자리가 섞여 있어도 자리 구성과 순서가 그대로다")
        void preservesSlotStructure() {
            CandidatePool pool = new CandidatePool(List.of(
                new CandidateSlot(1, SlotType.MEAL, List.of(seeded("황남밀면", 1)))));
            CuratedPlace chosen = new CuratedPlace(CandidateSourceType.SUGGESTED, null, "석굴암");
            List<CuratedDay> curated = List.of(new CuratedDay(1, List.of(
                new CuratedSlot(SlotType.ATTRACTION, List.of(chosen)),
                new CuratedSlot(SlotType.MEAL, List.of()))));

            List<CuratedDay> filled = fill(curated, pool);

            assertThat(filled.get(0).slots())
                .extracting(CuratedSlot::slotType)
                .containsExactly(SlotType.ATTRACTION, SlotType.MEAL);
            assertThat(filled.get(0).slots().get(1).choices())
                .extracting(CuratedPlace::placeName)
                .containsExactly("황남밀면");
        }
    }

    @Nested
    @DisplayName("승계에 필요한 값을 정확히 싣는다")
    class CarriesLookupKeys {

        @Test
        @DisplayName("listIndex 는 0-based 리스트 인덱스 그대로다 — 어긋나면 좌표 승계가 깨진다")
        void listIndexIsZeroBased() {
            CandidatePool pool = poolOf(2, SlotType.CAFE, "커피명가", "슬로우커피", "동경관");

            List<CuratedDay> filled =
                fill(emptyDay(2, SlotType.CAFE), pool);

            assertThat(filled.get(0).slots().get(0).choices())
                .extracting(CuratedPlace::listIndex)
                .containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("source 는 목록이 정한다 — 그래야 카카오를 부르지 않고 통과한다")
        void sourceComesFromCandidate() {
            CandidatePool pool = poolOf(1, SlotType.ATTRACTION, "천마총");

            List<CuratedDay> filled =
                fill(emptyDay(1, SlotType.ATTRACTION), pool);

            assertThat(filled.get(0).slots().get(0).choices())
                .extracting(CuratedPlace::source)
                .containsExactly(CandidateSourceType.SEEDED);
        }
    }

    @Nested
    @DisplayName("채울 것이 없을 때")
    class NothingToFill {

        @Test
        @DisplayName("후보가 3개 미만이면 있는 만큼만 채운다")
        void fillsFewerThanThree() {
            CandidatePool pool = poolOf(1, SlotType.ATTRACTION, "천마총", "첨성대");

            List<CuratedDay> filled =
                fill(emptyDay(1, SlotType.ATTRACTION), pool);

            assertThat(filled.get(0).slots().get(0).choices()).hasSize(2);
        }

        @Test
        @DisplayName("후보 목록이 비면 그 자리는 빈 채로 남는다 — 카테고리 검색 폴백은 보류했다")
        void leavesSlotEmptyWhenPoolIsEmpty() {
            List<CuratedDay> filled =
                fill(emptyDay(1, SlotType.ATTRACTION), CandidatePool.empty());

            assertThat(filled.get(0).slots()).hasSize(1);
            assertThat(filled.get(0).slots().get(0).choices()).isEmpty();
        }

        @Test
        @DisplayName("풀이 null 이어도 예외가 아니라 입력 구조가 그대로 나온다")
        void toleratesNullPool() {
            List<CuratedDay> filled =
                fill(emptyDay(1, SlotType.ATTRACTION, SlotType.MEAL), null);

            assertThat(filled.get(0).slots()).hasSize(2);
            assertThat(filled.get(0).slots()).allSatisfy(
                slot -> assertThat(slot.choices()).isEmpty());
        }

        @Test
        @DisplayName("입력이 비면 빈 목록이다")
        void emptyInput() {
            assertThat(fill(List.of(), CandidatePool.empty())).isEmpty();
            assertThat(fill(null, CandidatePool.empty())).isEmpty();
        }
    }

    @Nested
    @DisplayName("슬롯 집계 — 세 값이 전체를 나눈다 (ROADMAP 7-3 관측)")
    class SlotCounts {

        @Test
        @DisplayName("Curator 가 고른 자리와 폴백이 채운 자리를 갈라 센다")
        void splitsCuratorAndFallback() {
            CandidatePool pool = new CandidatePool(List.of(
                new CandidateSlot(1, SlotType.MEAL, List.of(seeded("황남밀면", 1)))));
            CuratedPlace chosen = new CuratedPlace(CandidateSourceType.SUGGESTED, null, "석굴암");
            List<CuratedDay> curated = List.of(new CuratedDay(1, List.of(
                new CuratedSlot(SlotType.ATTRACTION, List.of(chosen)),
                new CuratedSlot(SlotType.MEAL, List.of()))));

            Map<SlotFillOutcome, Integer> counts =
                DeterministicCuration.fill(curated, pool).slotCounts();

            assertThat(counts).containsEntry(SlotFillOutcome.CURATOR, 1)
                .containsEntry(SlotFillOutcome.FALLBACK, 1)
                .doesNotContainKey(SlotFillOutcome.UNFILLED);
        }

        @Test
        @DisplayName("후보가 없어 못 채운 자리는 UNFILLED 다 — hard fail 의 선행 지표다")
        void countsUnfillable() {
            Map<SlotFillOutcome, Integer> counts = DeterministicCuration
                .fill(emptyDay(1, SlotType.ATTRACTION, SlotType.MEAL), CandidatePool.empty())
                .slotCounts();

            assertThat(counts).containsEntry(SlotFillOutcome.UNFILLED, 2);
        }

        @Test
        @DisplayName("집계의 합이 전체 슬롯 수와 같다 — 분모를 따로 들 필요가 없다")
        void countsPartitionEverySlot() {
            CandidatePool pool = poolOf(1, SlotType.ATTRACTION, "천마총");
            List<CuratedDay> curated = List.of(new CuratedDay(1, List.of(
                new CuratedSlot(SlotType.ATTRACTION, List.of()),
                new CuratedSlot(SlotType.MEAL, List.of()),
                new CuratedSlot(SlotType.CAFE,
                    List.of(new CuratedPlace(CandidateSourceType.SUGGESTED, null, "커피명가"))))));

            Map<SlotFillOutcome, Integer> counts =
                DeterministicCuration.fill(curated, pool).slotCounts();

            assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
        }

        @Test
        @DisplayName("fallbackSlots 는 폴백이 없으면 0 이다")
        void fallbackSlotsIsZeroWhenCuratorDidItsJob() {
            List<CuratedDay> curated = List.of(new CuratedDay(1, List.of(new CuratedSlot(
                SlotType.ATTRACTION,
                List.of(new CuratedPlace(CandidateSourceType.SUGGESTED, null, "석굴암"))))));

            assertThat(DeterministicCuration.fill(curated, CandidatePool.empty()).fallbackSlots())
                .isZero();
        }
    }

    @Nested
    @DisplayName("day 를 가려 본다")
    class MatchesByDay {

        @Test
        @DisplayName("다른 day 의 후보를 끌어오지 않는다")
        void doesNotBorrowFromAnotherDay() {
            CandidatePool pool = poolOf(1, SlotType.ATTRACTION, "천마총", "첨성대");

            List<CuratedDay> filled =
                fill(emptyDay(2, SlotType.ATTRACTION), pool);

            assertThat(filled.get(0).slots().get(0).choices()).isEmpty();
        }
    }
}
