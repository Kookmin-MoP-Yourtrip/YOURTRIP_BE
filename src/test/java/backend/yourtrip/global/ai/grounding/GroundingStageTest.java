package backend.yourtrip.global.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.candidate.StyleTag;
import backend.yourtrip.global.ai.pipeline.CuratedDay;
import backend.yourtrip.global.ai.pipeline.CuratedPlace;
import backend.yourtrip.global.ai.pipeline.CuratedSlot;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.PlaceLookup;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link GroundingStage} 단위 테스트 (ROADMAP 5-2).
 *
 * <p>확인하는 것은 <b>"누가 카카오를 부르고 누가 안 부르는가"</b>와 <b>"실패했을 때 무엇이
 * 남는가"</b>다. executor 는 {@code Runnable::run} 으로 바꿔 결정론을 확보한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GroundingStage — 실존 확인과 좌표 확보 (ROADMAP 5-2)")
class GroundingStageTest {

    private static final double LAT = 35.8386877792;
    private static final double LON = 129.2104983997;

    @Mock
    private KakaoLocalClient kakaoLocalClient;

    private GroundingStage stage;

    @BeforeEach
    void setUp() {
        stage = new GroundingStage(kakaoLocalClient, Runnable::run);
    }

    private static PlaceCandidate seededCafe(String name, String address) {
        return new PlaceCandidate(CandidateSourceType.SEEDED, name, address, LAT, LON,
            SlotType.CAFE, Set.of(StyleTag.ROOFTOP), 1, StyleTag.ROOFTOP, 0.4,
            "음식점>카페,디저트");
    }

    private static CandidatePool poolWith(PlaceCandidate... candidates) {
        return new CandidatePool(
            List.of(new CandidateSlot(1, SlotType.CAFE, List.of(candidates))));
    }

    private static CuratedDay curated(CuratedPlace... choices) {
        return new CuratedDay(1, List.of(new CuratedSlot(SlotType.CAFE, List.of(choices))));
    }

    private static CuratedPlace fromList(int listIndex, String name) {
        return new CuratedPlace(CandidateSourceType.SEEDED, listIndex, name);
    }

    private static CuratedPlace suggested(String name) {
        return new CuratedPlace(CandidateSourceType.SUGGESTED, null, name);
    }

    private static Document document(String name, String x, String y) {
        return document(name, x, y, "CE7");
    }

    private static Document document(String name, String x, String y, String categoryGroupCode) {
        return new Document("1", name, "음식점 > 카페", categoryGroupCode, "카페", "",
            "경북 경주시 황남동", "경북 경주시 포석로 1080", x, y,
            "http://place.map.kakao.com/1", null);
    }

    @Nested
    @DisplayName("승계 — 목록 후보는 카카오를 부르지 않는다")
    class Inheritance {

        @Test
        @DisplayName("SEEDED 는 목록의 좌표·주소를 코드가 그대로 옮긴다")
        void inheritsFromCandidateList() {
            List<GroundedDay> days = stage.ground("경주",
                List.of(curated(fromList(0, "커피플레이스"))),
                poolWith(seededCafe("커피플레이스", "경북 경주시 포석로 1080")),
                CourseDeadline.unbounded());

            GroundedPlace place = days.get(0).slots().get(0).preferred().orElseThrow();
            assertThat(place.name()).isEqualTo("커피플레이스");
            assertThat(place.latitude()).isEqualTo(LAT);
            assertThat(place.source()).isEqualTo(CandidateSourceType.SEEDED);
            // 스타일 쿼리 유래 표식이 살아 있어야 8-7의 삭제 로그가 SEO 편승을 잴 수 있다.
            assertThat(place.matchedModifier()).isEqualTo(StyleTag.ROOFTOP);
            verifyNoInteractions(kakaoLocalClient);
        }

        @Test
        @DisplayName("승계한 장소는 URL 이 비어 5-10 의 보강 대상이 된다")
        void inheritedPlaceNeedsUrl() {
            List<GroundedDay> days = stage.ground("경주",
                List.of(curated(fromList(0, "커피플레이스"))),
                poolWith(seededCafe("커피플레이스", "경북 경주시 포석로 1080")),
                CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).preferred().orElseThrow().needsPlaceUrl())
                .isTrue();
        }

        @Test
        @DisplayName("listIndex 가 범위를 벗어나면 카카오 검증 경로로 간다 — 버리지 않는다")
        void outOfRangeIndexFallsBackToLookup() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(document("커피플레이스", "129.21", "35.83")));

            List<GroundedDay> days = stage.ground("경주",
                List.of(curated(fromList(9, "커피플레이스"))),
                poolWith(seededCafe("커피플레이스", "경북 경주시 포석로 1080")),
                CourseDeadline.unbounded());

            verify(kakaoLocalClient).lookupBestPlace(eq("커피플레이스"), eq("경주"));
            assertThat(days.get(0).slots().get(0).preferred().orElseThrow().source())
                .isEqualTo(CandidateSourceType.SUGGESTED);
        }

        @Test
        @DisplayName("후보 풀이 비어도 SUGGESTED 만으로 성립한다 — 초안 구조로 degrade")
        void emptyPoolStillWorks() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(document("황남빵", "129.21", "35.83")));

            List<GroundedDay> days = stage.ground("경주", List.of(curated(suggested("황남빵"))),
                CandidatePool.empty(), CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).survivors()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("SUGGESTED 검증 — 실패는 그 후보만 죽인다")
    class SuggestedVerification {

        @Test
        @DisplayName("검증에 성공하면 좌표와 함께 place_url 도 승계한다")
        void inheritsPlaceUrlOnHit() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(document("황남빵", "129.21", "35.83")));

            GroundedPlace place = stage.ground("경주", List.of(curated(suggested("황남빵"))),
                    CandidatePool.empty(), CourseDeadline.unbounded())
                .get(0).slots().get(0).preferred().orElseThrow();

            // 같은 장소를 5-10 이 다시 부르지 않게 하기 위한 승계다.
            assertThat(place.placeUrl()).isEqualTo("http://place.map.kakao.com/1");
            assertThat(place.needsPlaceUrl()).isFalse();
        }

        @Test
        @DisplayName("이름이 안 맞으면 탈락하고 차순위가 올라온다")
        void nameMismatchFallsThroughToNextChoice() {
            when(kakaoLocalClient.lookupBestPlace(eq("있을리없는집"), anyString()))
                .thenReturn(new PlaceLookup.NameMismatch("전혀다른가게"));

            List<GroundedDay> days = stage.ground("경주",
                List.of(curated(suggested("있을리없는집"), fromList(0, "커피플레이스"))),
                poolWith(seededCafe("커피플레이스", "경북 경주시 포석로 1080")),
                CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).survivors())
                .extracting(GroundedPlace::name).containsExactly("커피플레이스");
        }

        @Test
        @DisplayName("429 는 그 후보만 탈락시킨다 — 15건 중 하나가 죽었다고 코스를 죽이지 않는다")
        void quotaFailureDropsOnlyThatCandidate() {
            when(kakaoLocalClient.lookupBestPlace(eq("황남빵"), anyString()))
                .thenReturn(new PlaceLookup.Failed(ApiFailureCause.QUOTA_EXCEEDED, "429"));
            when(kakaoLocalClient.lookupBestPlace(eq("십원빵"), anyString()))
                .thenReturn(new PlaceLookup.Found(document("십원빵", "129.21", "35.83")));

            List<GroundedDay> days = stage.ground("경주",
                List.of(curated(suggested("황남빵"), suggested("십원빵"))),
                CandidatePool.empty(), CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).survivors())
                .extracting(GroundedPlace::name).containsExactly("십원빵");
        }

        @Test
        @DisplayName("좌표가 없는 응답은 탈락한다 — 0.0/0.0 으로 성공을 위장하지 않는다")
        void missingCoordinateDropsCandidate() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(document("황남빵", "", null)));

            List<GroundedDay> days = stage.ground("경주", List.of(curated(suggested("황남빵"))),
                CandidatePool.empty(), CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("같은 이름을 여러 자리가 제안해도 카카오는 한 번만 부른다")
        void deduplicatesLookupsByName() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(document("황남빵", "129.21", "35.83")));

            stage.ground("경주", List.of(
                    new CuratedDay(1, List.of(
                        new CuratedSlot(SlotType.CAFE, List.of(suggested("황남빵"))),
                        new CuratedSlot(SlotType.MEAL, List.of(suggested("황남빵")))))),
                CandidatePool.empty(), CourseDeadline.unbounded());

            verify(kakaoLocalClient, times(1)).lookupBestPlace(eq("황남빵"), anyString());
        }

        @Test
        @DisplayName("예산이 소진됐으면 검증을 아예 시작하지 않는다 — 승계 후보는 그대로 살아남는다")
        void expiredDeadlineSkipsLookups() {
            List<GroundedDay> days = stage.ground("경주",
                List.of(curated(fromList(0, "커피플레이스"), suggested("황남빵"))),
                poolWith(seededCafe("커피플레이스", "경북 경주시 포석로 1080")),
                CourseDeadline.startingNow(java.time.Duration.ZERO));

            verify(kakaoLocalClient, never()).lookupBestPlace(anyString(), anyString());
            assertThat(days.get(0).slots().get(0).survivors())
                .extracting(GroundedPlace::name).containsExactly("커피플레이스");
        }
    }

    @Nested
    @DisplayName("슬롯별 업종 하드 제약 (ROADMAP 5-3)")
    class CategoryConstraint {

        @Test
        @DisplayName("카페 자리에 온 음식점은 탈락한다 — 가점 +2 로는 막지 못하던 어긋남이다")
        void rejectsWrongCategoryGroup() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(
                    document("황남국밥", "129.21", "35.83", "FD6")));

            List<GroundedDay> days = stage.ground("경주", List.of(curated(suggested("황남국밥"))),
                CandidatePool.empty(), CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("허용 코드면 통과한다")
        void acceptsAllowedGroup() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(
                    document("커피플레이스", "129.21", "35.83", "CE7")));

            List<GroundedDay> days = stage.ground("경주",
                List.of(curated(suggested("커피플레이스"))), CandidatePool.empty(),
                CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).survivors()).hasSize(1);
        }

        @Test
        @DisplayName("그룹 코드가 없는 응답은 통과시킨다 — 모르는 것을 불일치로 취급하지 않는다")
        void passesWhenGroupCodeMissing() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(document("이름없는가게", "129.21", "35.83", "")));

            List<GroundedDay> days = stage.ground("경주",
                List.of(curated(suggested("이름없는가게"))), CandidatePool.empty(),
                CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).survivors()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("전 day 중복 제거")
    class Deduplication {

        @Test
        @DisplayName("day 가 달라도 같은 장소는 한 번만 배치된다 — Curator 는 다른 day 를 모른다")
        void samePlaceAcrossDaysIsDroppedOnce() {
            CandidatePool pool = new CandidatePool(List.of(
                new CandidateSlot(1, SlotType.CAFE,
                    List.of(seededCafe("커피플레이스", "경북 경주시 포석로 1080"))),
                new CandidateSlot(2, SlotType.CAFE,
                    List.of(seededCafe("커피플레이스", "경북 경주시 포석로 1080")))));

            List<GroundedDay> days = stage.ground("경주", List.of(
                new CuratedDay(1, List.of(
                    new CuratedSlot(SlotType.CAFE, List.of(fromList(0, "커피플레이스"))))),
                new CuratedDay(2, List.of(
                    new CuratedSlot(SlotType.CAFE, List.of(fromList(0, "커피플레이스")))))
            ), pool, CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).survivors()).hasSize(1);
            assertThat(days.get(1).slots().get(0).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("같은 상호라도 지점이 다르면 둘 다 남는다")
        void differentBranchesBothSurvive() {
            CandidatePool pool = new CandidatePool(List.of(
                new CandidateSlot(1, SlotType.CAFE, List.of(
                    seededCafe("스타벅스", "경북 경주시 포석로 1080"),
                    seededCafe("스타벅스", "경북 경주시 원화로 102")))));

            List<GroundedDay> days = stage.ground("경주", List.of(
                new CuratedDay(1, List.of(new CuratedSlot(SlotType.CAFE,
                    List.of(fromList(0, "스타벅스"), fromList(1, "스타벅스")))))
            ), pool, CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).survivors()).hasSize(2);
        }
    }
}
