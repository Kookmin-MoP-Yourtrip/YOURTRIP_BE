package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.TourApiResult;
import backend.yourtrip.global.tour.TourPlace;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TourApiSource — 관광지 목록을 후보로 (ROADMAP 5-8)")
class TourApiSourceTest {

    private static final double ANCHOR_LAT = 35.8386877792;
    private static final double ANCHOR_LON = 129.2104983997;

    @Mock
    private TourApiClient tourApiClient;

    @InjectMocks
    private TourApiSource source;

    private static TourPlace place(String title, String cat3, Double latitude, Double longitude,
        Double distanceMeters) {
        return new TourPlace("126508", "12", title, "A02", "A0201", cat3,
            "경북 경주시 계림로 9", latitude, longitude, distanceMeters, null);
    }

    @Nested
    @DisplayName("슬롯별 contentTypeId — 조회 단위가 슬롯이 아니라 분류다")
    class ContentTypeMapping {

        @Test
        @DisplayName("ATTRACTION·VIEWPOINT·STROLL 은 한 묶음이다 — 실제로 가를 방법이 없다")
        void sightSlotsShareContentTypes() {
            // 4-4에서 네이버 분류 판정을 같은 묶음으로 정한 것과 같은 근거다.
            assertThat(TourApiSource.contentTypeIdsFor(SlotType.ATTRACTION))
                .isEqualTo(TourApiSource.contentTypeIdsFor(SlotType.VIEWPOINT))
                .isEqualTo(TourApiSource.contentTypeIdsFor(SlotType.STROLL))
                .containsExactlyInAnyOrder(TourApiClient.CONTENT_TYPE_ATTRACTION,
                    TourApiClient.CONTENT_TYPE_CULTURE);
        }

        @Test
        @DisplayName("EXPERIENCE 는 레포츠를 본다")
        void experienceUsesLeisure() {
            assertThat(TourApiSource.contentTypeIdsFor(SlotType.EXPERIENCE))
                .containsExactly(TourApiClient.CONTENT_TYPE_LEISURE);
        }

        @Test
        @DisplayName("상업 POI 슬롯은 TourAPI 를 아예 쓰지 않는다 — 커버리지가 얇다")
        void commercialSlotsSkipTourApi() {
            assertThat(TourApiSource.contentTypeIdsFor(SlotType.MEAL)).isEmpty();
            assertThat(TourApiSource.contentTypeIdsFor(SlotType.CAFE)).isEmpty();
            assertThat(TourApiSource.contentTypeIdsFor(SlotType.SHOPPING)).isEmpty();
        }

        @Test
        @DisplayName("여러 슬롯의 합집합이 day 의 실제 호출 목록이다 — 중복 호출이 생기지 않는다")
        void unionIsDeduplicated() {
            // 같은 day 에 ATTRACTION 과 VIEWPOINT 가 함께 있어도 12·14 를 두 번 부르지 않는다.
            // 슬롯마다 부르면 코스당 호출이 설계 예산(9회)의 네 배가 된다.
            assertThat(TourApiSource.contentTypeIdsFor(
                List.of(SlotType.ATTRACTION, SlotType.VIEWPOINT, SlotType.MEAL)))
                .containsExactlyInAnyOrder(TourApiClient.CONTENT_TYPE_ATTRACTION,
                    TourApiClient.CONTENT_TYPE_CULTURE);
        }

        @Test
        @DisplayName("관광 슬롯이 없는 day 는 호출 목록이 빈다")
        void noSightSlotsMeansNoCalls() {
            assertThat(TourApiSource.contentTypeIdsFor(List.of(SlotType.MEAL, SlotType.CAFE)))
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("응답 변환")
    class Mapping {

        @Test
        @DisplayName("cat3 를 스타일 태그로 옮기고 거리는 API 가 준 dist 를 쓴다")
        void mapsCategoryAndDistance() {
            when(tourApiClient.search(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(
                new TourApiResult.Found(List.of(
                    place("불국사", "A02010800", 35.79, 129.33, 1250.0))));

            CandidateBatch batch = source.fetch(ANCHOR_LAT, ANCHOR_LON,
                TourApiClient.CONTENT_TYPE_ATTRACTION);

            PlaceCandidate candidate = batch.candidates().get(0);
            assertThat(candidate.source()).isEqualTo(CandidateSourceType.LISTED);
            assertThat(candidate.seedRank()).isNull();
            assertThat(candidate.styleTags())
                .containsExactlyInAnyOrder(StyleTag.HANOK, StyleTag.QUIET, StyleTag.HISTORY);
            assertThat(candidate.distanceKm()).isCloseTo(1.25, within(0.0001));
            assertThat(candidate.rawCategory()).isEqualTo("A02010800");
        }

        @Test
        @DisplayName("레포츠는 EXPERIENCE 를 기본 슬롯으로 받는다")
        void leisureDefaultsToExperience() {
            when(tourApiClient.search(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(
                new TourApiResult.Found(List.of(
                    place("경주 카트장", "A03021000", 35.79, 129.33, 900.0))));

            assertThat(source.fetch(ANCHOR_LAT, ANCHOR_LON, TourApiClient.CONTENT_TYPE_LEISURE)
                .candidates().get(0).slotType()).isEqualTo(SlotType.EXPERIENCE);
        }

        @Test
        @DisplayName("문화시설은 볼거리 슬롯군으로 받는다")
        void cultureDefaultsToAttraction() {
            when(tourApiClient.search(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(
                new TourApiResult.Found(List.of(
                    place("국립경주박물관", "A02060100", 35.83, 129.22, 700.0))));

            assertThat(source.fetch(ANCHOR_LAT, ANCHOR_LON, TourApiClient.CONTENT_TYPE_CULTURE)
                .candidates().get(0).slotType()).isEqualTo(SlotType.ATTRACTION);
        }

        @Test
        @DisplayName("dist 가 비면 거리를 비운다")
        void nullDistanceStaysNull() {
            when(tourApiClient.search(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(
                new TourApiResult.Found(List.of(place("불국사", "A02010800", 35.79, 129.33, null))));

            assertThat(source.fetch(ANCHOR_LAT, ANCHOR_LON, TourApiClient.CONTENT_TYPE_ATTRACTION)
                .candidates().get(0).distanceKm()).isNull();
        }

        @Test
        @DisplayName("좌표 없는 항목은 후보가 되지 못한다")
        void dropsPlacesWithoutCoordinates() {
            when(tourApiClient.search(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(
                new TourApiResult.Found(List.of(
                    place("좌표없음", "A02010800", null, null, 100.0),
                    place("불국사", "A02010800", 35.79, 129.33, 1250.0))));

            assertThat(source.fetch(ANCHOR_LAT, ANCHOR_LON, TourApiClient.CONTENT_TYPE_ATTRACTION)
                .candidates()).extracting(PlaceCandidate::name).containsExactly("불국사");
        }

        @Test
        @DisplayName("매핑에 없는 cat3 는 태그 없이 통과한다 — 필터가 아니라 표시다")
        void unmappedCategoryPassesThrough() {
            CandidateBatch batch;
            when(tourApiClient.search(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(
                new TourApiResult.Found(List.of(place("무명", "Z99999999", 35.79, 129.33, 500.0))));

            batch = source.fetch(ANCHOR_LAT, ANCHOR_LON, TourApiClient.CONTENT_TYPE_ATTRACTION);

            assertThat(batch.candidates()).hasSize(1);
            assertThat(batch.candidates().get(0).styleTags()).isEmpty();
        }
    }

    @Nested
    @DisplayName("실패는 값으로 돌려준다")
    class Failures {

        @Test
        @DisplayName("0건은 EMPTY 다")
        void emptyResult() {
            when(tourApiClient.search(anyDouble(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(new TourApiResult.Empty());

            assertThat(source.fetch(ANCHOR_LAT, ANCHOR_LON, TourApiClient.CONTENT_TYPE_ATTRACTION)
                .outcome()).isEqualTo(CandidateOutcome.EMPTY);
        }

        @Test
        @DisplayName("일일 한도 초과는 FAILED 로 사유까지 남긴다")
        void quotaExceeded() {
            when(tourApiClient.search(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(
                new TourApiResult.Failed(ApiFailureCause.QUOTA_EXCEEDED, "22"));

            CandidateBatch batch =
                source.fetch(ANCHOR_LAT, ANCHOR_LON, TourApiClient.CONTENT_TYPE_ATTRACTION);

            assertThat(batch.outcome()).isEqualTo(CandidateOutcome.FAILED);
            assertThat(batch.cause()).isEqualTo(ApiFailureCause.QUOTA_EXCEEDED);
        }
    }
}
