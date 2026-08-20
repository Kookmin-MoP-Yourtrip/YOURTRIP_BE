package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PlaceCandidate / CandidateSlot — 후보 값 타입 (ROADMAP 5-8)")
class PlaceCandidateTest {

    @Nested
    @DisplayName("후보가 스스로 지키는 계약")
    class Invariants {

        @Test
        @DisplayName("좌표 없는 후보는 만들 수 없다 — 거르는 책임은 소스에 있다")
        void rejectsInvalidCoordinate() {
            assertThatThrownBy(() -> new PlaceCandidate(CandidateSourceType.SEEDED, "대릉원", "",
                Double.NaN, 129.0, SlotType.ATTRACTION, Set.of(), 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("위도");
        }

        @Test
        @DisplayName("SUGGESTED 는 후보 목록에 존재할 수 없다 — 목록 밖에서 나오는 제안이다")
        void rejectsSuggestedSource() {
            assertThatThrownBy(() -> new PlaceCandidate(CandidateSourceType.SUGGESTED, "대릉원", "",
                35.8, 129.2, SlotType.ATTRACTION, Set.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUGGESTED");
        }

        @Test
        @DisplayName("이름이 비면 거부한다 — 6-7 위조 검증이 이 값으로 대조한다")
        void rejectsBlankName() {
            assertThatThrownBy(() -> new PlaceCandidate(CandidateSourceType.SEEDED, "  ", "",
                35.8, 129.2, SlotType.ATTRACTION, Set.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("seedRank 는 1부터다 — 시드에 없으면 0이 아니라 null 이다")
        void rejectsZeroSeedRank() {
            assertThatThrownBy(() -> new PlaceCandidate(CandidateSourceType.SEEDED, "대릉원", "",
                35.8, 129.2, SlotType.ATTRACTION, Set.of(), 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seedRank");
        }

        @Test
        @DisplayName("두 표식은 서로 독립이다 — 병합 후보는 LISTED 이면서 시드에도 든다")
        void seededAndOfficialAreIndependent() {
            PlaceCandidate mergedCandidate = new PlaceCandidate(CandidateSourceType.LISTED, "대릉원",
                "", 35.8, 129.2, SlotType.ATTRACTION, Set.of(), 2, null, 0.4, "A02010700");

            assertThat(mergedCandidate.official()).isTrue();
            assertThat(mergedCandidate.seeded()).isTrue();
        }

        @Test
        @DisplayName("TourAPI 단독 후보는 official 이지만 seeded 는 아니다")
        void tourApiOnlyIsNotSeeded() {
            PlaceCandidate tourOnly = CandidateFixtures.listed("골굴사",
                CandidateFixtures.NAEMUL_LAT, CandidateFixtures.NAEMUL_LON, 1.2, Set.of());

            assertThat(tourOnly.official()).isTrue();
            assertThat(tourOnly.seeded()).isFalse();
        }
    }

    @Nested
    @DisplayName("CandidateSlot.at — Curator 가 지목한 listIndex")
    class ListIndexLookup {

        private final CandidateSlot slot = new CandidateSlot(1, SlotType.ATTRACTION, java.util.List.of(
            CandidateFixtures.seeded("대릉원", 1, CandidateFixtures.CHEONMACHONG_LAT,
                CandidateFixtures.CHEONMACHONG_LON),
            CandidateFixtures.seeded("첨성대", 2, CandidateFixtures.CHEOMSEONGDAE_LAT,
                CandidateFixtures.CHEOMSEONGDAE_LON)));

        @Test
        @DisplayName("범위 안이면 그 후보를 돌려준다")
        void returnsCandidateInRange() {
            assertThat(slot.at(1)).map(PlaceCandidate::name).contains("첨성대");
        }

        @Test
        @DisplayName("범위 밖·음수·null 은 빈 값이다 — 호출자는 버리지 않고 SUGGESTED 로 강등한다")
        void returnsEmptyOutOfRange() {
            assertThat(slot.at(2)).isEmpty();
            assertThat(slot.at(-1)).isEmpty();
            assertThat(slot.at(null)).isEmpty();
        }

        @Test
        @DisplayName("빈 슬롯은 어떤 인덱스도 돌려주지 않는다 — 후보 공급 전면 실패 경로")
        void emptySlotReturnsNothing() {
            assertThat(CandidateSlot.empty(1, SlotType.MEAL).at(0)).isEqualTo(Optional.empty());
        }
    }
}
