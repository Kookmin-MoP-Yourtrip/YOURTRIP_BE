package backend.yourtrip.global.ai.candidate;

import static backend.yourtrip.global.ai.candidate.CandidateFixtures.CHEOMSEONGDAE_LAT;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.CHEOMSEONGDAE_LON;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.CHEONMACHONG_LAT;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.CHEONMACHONG_LON;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.NAEMUL_LAT;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.NAEMUL_LON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CandidateOrdering — Curator 입력 목록의 순서와 cap (ROADMAP 5-8)")
class CandidateOrderingTest {

    private static final Set<StyleTag> HEALING = Set.of(StyleTag.QUIET, StyleTag.UNCROWDED);

    @Nested
    @DisplayName("세 그룹의 사전식 순서")
    class Grouping {

        @Test
        @DisplayName("시드 후보가 항상 앞이다 — 스타일이 맞는 TourAPI 후보보다도 앞")
        void seededComesFirst() {
            PlaceCandidate styleMatched = CandidateFixtures.listed("골굴사",
                NAEMUL_LAT, NAEMUL_LON, 0.1, Set.of(StyleTag.QUIET));
            PlaceCandidate seeded = CandidateFixtures.seeded("천마총", 5,
                CHEONMACHONG_LAT, CHEONMACHONG_LON);

            List<PlaceCandidate> ordered =
                CandidateOrdering.order(List.of(styleMatched, seeded), HEALING);

            assertThat(ordered).extracting(PlaceCandidate::name).containsExactly("천마총", "골굴사");
        }

        @Test
        @DisplayName("시드 안에서는 seedRank 오름차순이다")
        void seededSortedByRank() {
            List<PlaceCandidate> ordered = CandidateOrdering.order(List.of(
                CandidateFixtures.seeded("셋째", 3, CHEONMACHONG_LAT, CHEONMACHONG_LON),
                CandidateFixtures.seeded("첫째", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON),
                CandidateFixtures.seeded("둘째", 2, CHEONMACHONG_LAT, CHEONMACHONG_LON)), Set.of());

            assertThat(ordered).extracting(PlaceCandidate::name)
                .containsExactly("첫째", "둘째", "셋째");
        }

        @Test
        @DisplayName("스타일이 맞는 후보가 나머지보다 앞이다 — 더 가깝더라도")
        void styleMatchedBeatsCloserRest() {
            PlaceCandidate nearButPlain = CandidateFixtures.listed("가까운곳",
                CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.1, Set.of(StyleTag.LIVELY));
            PlaceCandidate farButMatching = CandidateFixtures.listed("먼곳",
                CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, 9.0, Set.of(StyleTag.UNCROWDED));

            List<PlaceCandidate> ordered =
                CandidateOrdering.order(List.of(nearButPlain, farButMatching), HEALING);

            assertThat(ordered).extracting(PlaceCandidate::name).containsExactly("먼곳", "가까운곳");
        }

        @Test
        @DisplayName("사용자 키워드가 없으면 스타일 그룹이 생기지 않는다 — 전부 거리순")
        void noPreferredTagsMeansNoStyleGroup() {
            PlaceCandidate near = CandidateFixtures.listed("가까운곳",
                CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.1, Set.of(StyleTag.UNCROWDED));
            PlaceCandidate far = CandidateFixtures.listed("먼곳",
                CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, 9.0, Set.of(StyleTag.UNCROWDED));

            assertThat(CandidateOrdering.order(List.of(far, near), Set.of()))
                .extracting(PlaceCandidate::name).containsExactly("가까운곳", "먼곳");
        }

        @Test
        @DisplayName("거리를 모르는 후보는 맨 뒤다 — 0으로 취급하면 근거 없이 앞을 차지한다")
        void unknownDistanceGoesLast() {
            PlaceCandidate unknown = CandidateFixtures.listed("거리모름",
                NAEMUL_LAT, NAEMUL_LON, null, Set.of());
            PlaceCandidate far = CandidateFixtures.listed("멀지만아는곳",
                CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, 15.0, Set.of());

            assertThat(CandidateOrdering.order(List.of(unknown, far), Set.of()))
                .extracting(PlaceCandidate::name).containsExactly("멀지만아는곳", "거리모름");
        }
    }

    @Nested
    @DisplayName("식사 자리의 술집 — 버리지 않고 뒤로 민다 (ROADMAP 5-3)")
    class BarDeprioritization {

        private static PlaceCandidate meal(String name, String category, Integer seedRank) {
            return new PlaceCandidate(CandidateSourceType.SEEDED, name,
                "경북 경주시 " + name + "로 1", CHEONMACHONG_LAT, CHEONMACHONG_LON,
                SlotType.MEAL, Set.of(), seedRank, null, null, category);
        }

        @Test
        @DisplayName("시드 1위 술집도 평범한 시드 뒤로 간다 — 시드 여부보다 먼저 판정한다")
        void barGoesBehindEvenWhenSeededFirst() {
            List<PlaceCandidate> ordered = CandidateOrdering.order(List.of(
                meal("황리단길호프", "음식점>술집>호프", 1),
                meal("황남밀면", "음식점>한식>국수", 4)), Set.of());

            assertThat(ordered).extracting(PlaceCandidate::name)
                .containsExactly("황남밀면", "황리단길호프");
        }

        @Test
        @DisplayName("그 슬롯에 술집밖에 없으면 여전히 쓰인다 — 감점이지 하드 드롭이 아니다")
        void barSurvivesWhenAlone() {
            assertThat(CandidateOrdering.order(
                List.of(meal("황리단길호프", "음식점>술집>호프", 1)), Set.of())).hasSize(1);
        }

        @Test
        @DisplayName("카페 슬롯의 술집은 밀지 않는다 — 애초에 4-4가 슬롯 단계에서 거른다")
        void onlyMealSlotIsAffected() {
            PlaceCandidate bar = new PlaceCandidate(CandidateSourceType.SEEDED, "와인바",
                "경북 경주시 포석로 1", CHEONMACHONG_LAT, CHEONMACHONG_LON, SlotType.CAFE,
                Set.of(), 1, null, null, "음식점>와인바");
            PlaceCandidate cafe = new PlaceCandidate(CandidateSourceType.SEEDED, "커피플레이스",
                "경북 경주시 원화로 1", CHEONMACHONG_LAT, CHEONMACHONG_LON, SlotType.CAFE,
                Set.of(), 2, null, null, "음식점>카페,디저트");

            assertThat(CandidateOrdering.order(List.of(bar, cafe), Set.of()))
                .extracting(PlaceCandidate::name).containsExactly("와인바", "커피플레이스");
        }
    }

    @Nested
    @DisplayName("결정론")
    class Determinism {

        @Test
        @DisplayName("입력 순서가 어떻든 같은 목록이 나온다 — listIndex 가 재현돼야 6-7이 성립한다")
        void independentOfInputOrder() {
            // 뒤의 둘은 거리가 같아 동점이다 — 이름으로만 갈린다.
            List<PlaceCandidate> input = new ArrayList<>(List.of(
                CandidateFixtures.listed("나장소", CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, 1.0, Set.of()),
                CandidateFixtures.listed("가장소", NAEMUL_LAT, NAEMUL_LON, 1.0, Set.of()),
                CandidateFixtures.seeded("시드", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON)));

            List<String> expected = CandidateOrdering.order(input, Set.of()).stream()
                .map(PlaceCandidate::name).toList();

            for (int i = 0; i < 5; i++) {
                Collections.shuffle(input, new java.util.Random(i));
                assertThat(CandidateOrdering.order(input, Set.of()))
                    .extracting(PlaceCandidate::name).isEqualTo(expected);
            }
            // 거리가 같은 두 후보의 동점은 이름으로 깨진다.
            assertThat(expected).containsExactly("시드", "가장소", "나장소");
        }
    }

    @Nested
    @DisplayName("cap")
    class Cap {

        @Test
        @DisplayName("잘리는 건 항상 맨 뒤 — 먼 곳부터 사라지고 시드는 남는다")
        void truncatesFromTail() {
            List<PlaceCandidate> candidates = new ArrayList<>();
            candidates.add(CandidateFixtures.seeded("시드", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON));
            for (int i = 0; i < 40; i++) {
                candidates.add(CandidateFixtures.listed("먼곳" + i,
                    CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, 10.0 + i, Set.of()));
            }

            List<PlaceCandidate> ordered = CandidateOrdering.order(candidates, Set.of());

            assertThat(ordered).hasSize(CandidateOrdering.DEFAULT_CAP);
            assertThat(ordered.get(0).name()).isEqualTo("시드");
            assertThat(ordered).extracting(PlaceCandidate::name).doesNotContain("먼곳39");
        }

        @Test
        @DisplayName("cap 이 목록보다 크면 그대로다")
        void noTruncationWhenUnderCap() {
            List<PlaceCandidate> candidates =
                List.of(CandidateFixtures.seeded("시드", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON));

            assertThat(CandidateOrdering.order(candidates, Set.of())).hasSize(1);
        }

        @Test
        @DisplayName("cap 0 이하는 거부한다 — 빈 목록을 만드는 건 fail-open 이 아니라 버그다")
        void rejectsNonPositiveCap() {
            assertThatThrownBy(() -> CandidateOrdering.order(List.of(), Set.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
