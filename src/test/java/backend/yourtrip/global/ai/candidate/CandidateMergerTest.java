package backend.yourtrip.global.ai.candidate;

import static backend.yourtrip.global.ai.candidate.CandidateFixtures.CHEOMSEONGDAE_LAT;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.CHEOMSEONGDAE_LON;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.CHEONMACHONG_LAT;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.CHEONMACHONG_LON;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.NAEMUL_LAT;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.NAEMUL_LON;
import static backend.yourtrip.global.ai.candidate.CandidateFixtures.withScope;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CandidateMerger — 후보 중복 정리 (ROADMAP 5-8)")
class CandidateMergerTest {

    @Nested
    @DisplayName("같은 소스 안 — 기본 쿼리와 스타일 쿼리의 중복")
    class WithinSource {

        @Test
        @DisplayName("같은 가게가 두 쿼리에 나오면 하나로 줄고 먼저 만난 쪽이 남는다")
        void keepsFirstOccurrence() {
            List<PlaceCandidate> deduped = CandidateMerger.dedupeWithinSource(List.of(
                CandidateFixtures.cafe("커피플레이스", "경북 경주시 포석로 1080", 2, null),
                CandidateFixtures.cafe("커피플레이스", "경북 경주시 포석로 1080", 4, StyleTag.ROOFTOP)));

            assertThat(deduped).hasSize(1);
            // 기본 쿼리(먼저 온 쪽)의 순위가 유지된다 — 서로 다른 쿼리의 순위를 비교해 min 을
            // 고르면 "스타일 쿼리의 3위와 기본 쿼리의 3위는 같은 등급이 아니다"를 어긴다.
            assertThat(deduped.get(0).seedRank()).isEqualTo(2);
        }

        @Test
        @DisplayName("먼저 만난 쪽에 없던 modifier 는 채워진다 — 정보가 늘어난 것이지 충돌이 아니다")
        void fillsMissingModifier() {
            List<PlaceCandidate> deduped = CandidateMerger.dedupeWithinSource(List.of(
                CandidateFixtures.cafe("커피플레이스", "경북 경주시 포석로 1080", 2, null),
                CandidateFixtures.cafe("커피플레이스", "경북 경주시 포석로 1080", 4, StyleTag.ROOFTOP)));

            assertThat(deduped.get(0).matchedModifier()).isEqualTo(StyleTag.ROOFTOP);
        }

        @Test
        @DisplayName("순위와 함께 지명 단계도 먼저 만난 쪽이 남는다 — 둘이 어긋나면 표식이 거짓이 된다")
        void keepsSeedScopeOfFirstOccurrence() {
            PlaceCandidate fromArea = withScope(
                CandidateFixtures.cafe("커피플레이스", "경북 경주시 포석로 1080", 2, null),
                SeedScope.AREA);
            PlaceCandidate fromCity = withScope(
                CandidateFixtures.cafe("커피플레이스", "경북 경주시 포석로 1080", 1, null),
                SeedScope.LOCATION);

            List<PlaceCandidate> deduped =
                CandidateMerger.dedupeWithinSource(List.of(fromArea, fromCity));

            assertThat(deduped).hasSize(1);
            assertThat(deduped.get(0).seedRank()).isEqualTo(2);
            assertThat(deduped.get(0).seedScope()).isEqualTo(SeedScope.AREA);
        }

        @Test
        @DisplayName("같은 상호라도 지점이 다르면 둘 다 남는다 — 프랜차이즈를 뭉치지 않는다")
        void keepsDifferentBranches() {
            List<PlaceCandidate> deduped = CandidateMerger.dedupeWithinSource(List.of(
                CandidateFixtures.cafe("스타벅스", "경북 경주시 포석로 1080", 1, null),
                CandidateFixtures.cafe("스타벅스", "경북 경주시 원화로 102", 3, null)));

            assertThat(deduped).hasSize(2);
        }
    }

    @Nested
    @DisplayName("소스 간 — 시더와 TourAPI 병합")
    class AcrossSources {

        @Test
        @DisplayName("같은 장소면 하나로 합치고 표식을 둘 다 붙인다")
        void mergesSamePlace() {
            List<PlaceCandidate> merged = CandidateMerger.mergeAcrossSources(
                List.of(CandidateFixtures.seeded("천마총", 2, CHEONMACHONG_LAT, CHEONMACHONG_LON)),
                List.of(CandidateFixtures.listed("천마총", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of(StyleTag.HISTORY))));

            assertThat(merged).hasSize(1);
            PlaceCandidate result = merged.get(0);
            // 좌표·명칭은 TourAPI 가 이기므로 source 는 LISTED, 시드 표식은 seedRank 가 남긴다.
            assertThat(result.source()).isEqualTo(CandidateSourceType.LISTED);
            assertThat(result.seedRank()).isEqualTo(2);
            assertThat(result.styleTags()).contains(StyleTag.HISTORY);
            assertThat(result.distanceKm()).isEqualTo(0.4);
        }

        @Test
        @DisplayName("좌표가 가까워도 이름이 다르면 합치지 않는다 — 288m 떨어진 다른 유적이다")
        void doesNotMergeNearbyDifferentPlaces() {
            List<PlaceCandidate> merged = CandidateMerger.mergeAcrossSources(
                List.of(CandidateFixtures.seeded("천마총", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON)),
                List.of(CandidateFixtures.listed("내물왕릉", NAEMUL_LAT, NAEMUL_LON, 0.3, Set.of())));

            assertThat(merged).hasSize(2);
        }

        @Test
        @DisplayName("이름이 같아도 멀면 합치지 않는다 — 전국에 여럿인 향교·사찰")
        void doesNotMergeSameNameFarApart() {
            List<PlaceCandidate> merged = CandidateMerger.mergeAcrossSources(
                List.of(CandidateFixtures.seeded("향교", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON)),
                List.of(CandidateFixtures.listed("향교", CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, 2.0,
                    Set.of())));

            assertThat(merged).hasSize(2);
        }

        @Test
        @DisplayName("TourAPI 항목 하나는 최대 한 번만 병합된다 — 같은 장소가 목록에 두 번 실리지 않는다")
        void consumesEachOfficialOnce() {
            // 시드 둘이 같은 관광지를 가리키는 상황(별칭·부속 시설). 소진 처리가 없으면
            // 같은 TourAPI 항목이 두 후보로 복제된다.
            List<PlaceCandidate> merged = CandidateMerger.mergeAcrossSources(
                List.of(
                    CandidateFixtures.seeded("천마총", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON),
                    CandidateFixtures.seeded("천마총", 3, CHEONMACHONG_LAT, CHEONMACHONG_LON)),
                List.of(CandidateFixtures.listed("천마총", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of())));

            assertThat(merged).hasSize(2);
            assertThat(merged).filteredOn(PlaceCandidate::official).hasSize(1);
        }

        @Test
        @DisplayName("매칭 안 된 항목은 양쪽 다 풀에 남는다 — 이 비대칭이 두 소스를 같이 쓰는 이유다")
        void keepsUnmatchedFromBothSides() {
            List<PlaceCandidate> merged = CandidateMerger.mergeAcrossSources(
                List.of(CandidateFixtures.seeded("황리단길", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON)),
                List.of(CandidateFixtures.listed("골굴사", NAEMUL_LAT, NAEMUL_LON, 5.0, Set.of())));

            assertThat(merged).extracting(PlaceCandidate::name)
                .containsExactly("황리단길", "골굴사");
        }

        @Test
        @DisplayName("병합 후보의 지명 단계는 시드에서 온다 — seedRank 를 시드에서 받는 것과 한 쌍이다")
        void inheritsSeedScopeFromSeed() {
            PlaceCandidate cityWideSeed = withScope(
                CandidateFixtures.seeded("천마총", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON),
                SeedScope.LOCATION);

            List<PlaceCandidate> merged = CandidateMerger.mergeAcrossSources(
                List.of(cityWideSeed),
                List.of(CandidateFixtures.listed("천마총", CHEONMACHONG_LAT, CHEONMACHONG_LON, 6.3,
                    Set.of())));

            assertThat(merged).hasSize(1);
            assertThat(merged.get(0).seedRank()).isEqualTo(1);
            assertThat(merged.get(0).fromCityWideQuery()).isTrue();
        }

        @Test
        @DisplayName("TourAPI 가 비면 시더 목록이 그대로다 — 관광 슬롯이 아니거나 fail-open 경로")
        void passesThroughWhenNoOfficials() {
            List<PlaceCandidate> seeds =
                List.of(CandidateFixtures.seeded("황리단길", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON));

            assertThat(CandidateMerger.mergeAcrossSources(seeds, List.of())).isEqualTo(seeds);
        }
    }

    @Nested
    @DisplayName("본체와 부속 — 진포함 AND 거리 (이슈 #106)")
    class Subordinates {

        @Test
        @DisplayName("본체가 남고 부속이 사라진다 — 5-9 에서 둘 다 목록에 실리던 쌍이다")
        void collapsesSubordinateIntoPrimary() {
            List<PlaceCandidate> collapsed = CandidateMerger.collapseSubordinates(List.of(
                CandidateFixtures.listed("대릉원", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of()),
                CandidateFixtures.listed("대릉원 주차장", NAEMUL_LAT, NAEMUL_LON, 0.5, Set.of())));

            assertThat(collapsed).hasSize(1);
            assertThat(collapsed.get(0).name()).isEqualTo("대릉원");
        }

        @Test
        @DisplayName("부속이 목록 앞에 있어도 본체가 남는다 — 순서가 아니라 이름이 정한다")
        void primaryWinsRegardlessOfInputOrder() {
            List<PlaceCandidate> collapsed = CandidateMerger.collapseSubordinates(List.of(
                CandidateFixtures.listed("대릉원 주차장", NAEMUL_LAT, NAEMUL_LON, 0.5, Set.of()),
                CandidateFixtures.listed("대릉원", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of())));

            assertThat(collapsed).hasSize(1);
            assertThat(collapsed.get(0).name()).isEqualTo("대릉원");
        }

        @Test
        @DisplayName("부속이 갖고 있던 스타일 태그는 본체에 합쳐진다 — 버리는 게 아니라 흡수다")
        void absorbsStyleTagsOfSubordinate() {
            List<PlaceCandidate> collapsed = CandidateMerger.collapseSubordinates(List.of(
                CandidateFixtures.listed("대릉원", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of(StyleTag.HISTORY)),
                CandidateFixtures.listed("대릉원 주차장", NAEMUL_LAT, NAEMUL_LON, 0.5,
                    Set.of(StyleTag.QUIET))));

            assertThat(collapsed.get(0).styleTags())
                .containsExactlyInAnyOrder(StyleTag.HISTORY, StyleTag.QUIET);
        }

        @Test
        @DisplayName("이름이 진포함이어도 멀면 둘 다 남는다 — 거리 조건이 AND 로 살아 있다")
        void keepsBothWhenFarApart() {
            List<PlaceCandidate> collapsed = CandidateMerger.collapseSubordinates(List.of(
                CandidateFixtures.listed("대릉원", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of()),
                CandidateFixtures.listed("대릉원 주차장", CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, 0.9,
                    Set.of())));

            assertThat(collapsed).hasSize(2);
        }

        @Test
        @DisplayName("같은 상호의 다른 지점은 둘 다 남는다 — 진포함으로 좁힌 이유다")
        void keepsFranchiseBranches() {
            List<PlaceCandidate> collapsed = CandidateMerger.collapseSubordinates(List.of(
                CandidateFixtures.listed("스타벅스", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of()),
                CandidateFixtures.listed("스타벅스", NAEMUL_LAT, NAEMUL_LON, 0.5, Set.of())));

            assertThat(collapsed)
                .as("288m 떨어진 같은 이름 — similar 였다면 하나로 합쳐졌다")
                .hasSize(2);
        }

        @Test
        @DisplayName("본체 없이 홀로 있는 부속은 지우지 않는다 — 이름이 아니라 짝이 근거다")
        void keepsStandaloneSubordinate() {
            List<PlaceCandidate> collapsed = CandidateMerger.collapseSubordinates(List.of(
                CandidateFixtures.listed("대릉원 주차장", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of()),
                CandidateFixtures.listed("첨성대", CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, 0.9,
                    Set.of())));

            assertThat(collapsed).hasSize(2);
        }

        @Test
        @DisplayName("체인은 가장 짧은 본체 하나로 모인다")
        void collapsesChainIntoShortestPrimary() {
            List<PlaceCandidate> collapsed = CandidateMerger.collapseSubordinates(List.of(
                CandidateFixtures.listed("갑사철당간", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of()),
                CandidateFixtures.listed("갑사", NAEMUL_LAT, NAEMUL_LON, 0.5, Set.of()),
                CandidateFixtures.listed("공주 갑사 철당간", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of())));

            assertThat(collapsed).extracting(PlaceCandidate::name).containsExactly("갑사");
        }

        @Test
        @DisplayName("돌려주는 순서는 입력 순서다 — 시드 먼저를 무너뜨리면 정렬 결과가 흔들린다")
        void preservesInputOrder() {
            List<PlaceCandidate> collapsed = CandidateMerger.collapseSubordinates(List.of(
                CandidateFixtures.seeded("황리단길", 1, CHEONMACHONG_LAT, CHEONMACHONG_LON),
                CandidateFixtures.listed("대릉원", NAEMUL_LAT, NAEMUL_LON, 0.5, Set.of()),
                CandidateFixtures.listed("대릉원 주차장", NAEMUL_LAT, NAEMUL_LON, 0.5, Set.of()),
                CandidateFixtures.listed("첨성대", CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, 0.9,
                    Set.of())));

            assertThat(collapsed).extracting(PlaceCandidate::name)
                .containsExactly("황리단길", "대릉원", "첨성대");
        }

        @Test
        @DisplayName("합칠 것이 없으면 목록이 그대로다")
        void passesThroughWhenNothingToCollapse() {
            List<PlaceCandidate> candidates = List.of(
                CandidateFixtures.listed("대릉원", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of()),
                CandidateFixtures.listed("첨성대", CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, 0.9,
                    Set.of()));

            assertThat(CandidateMerger.collapseSubordinates(candidates)).isEqualTo(candidates);
        }

        @Test
        @DisplayName("빈 목록과 한 건짜리 목록에서 죽지 않는다")
        void handlesTrivialInputs() {
            assertThat(CandidateMerger.collapseSubordinates(null)).isEmpty();
            assertThat(CandidateMerger.collapseSubordinates(List.of())).isEmpty();

            List<PlaceCandidate> single = List.of(
                CandidateFixtures.listed("대릉원", CHEONMACHONG_LAT, CHEONMACHONG_LON, 0.4,
                    Set.of()));
            assertThat(CandidateMerger.collapseSubordinates(single)).isEqualTo(single);
        }
    }
}
