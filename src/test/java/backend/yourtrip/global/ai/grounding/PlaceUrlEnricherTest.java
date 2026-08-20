package backend.yourtrip.global.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.PlaceLookup;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PlaceUrlEnricher} 단위 테스트 (ROADMAP 5-10).
 *
 * <p>좌표는 4-7 실호출 값을 쓴다 — 300m 임계값이 무엇을 가르는지가 테스트 안에서만 성립하는
 * 이야기가 되지 않도록.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlaceUrlEnricher — URL 이 빈 장소에만, 틀리느니 비운다 (ROADMAP 5-10)")
class PlaceUrlEnricherTest {

    private static final double CHEONMACHONG_LAT = 35.8386877792;
    private static final double CHEONMACHONG_LON = 129.2104983997;
    private static final double NAEMUL_LAT = 35.8362047083;   // 288m 거리
    private static final double NAEMUL_LON = 129.2095707739;
    private static final double CHEOMSEONGDAE_LAT = 35.8347222;  // 500m 이상
    private static final double CHEOMSEONGDAE_LON = 129.2192222;

    private static final String URL = "http://place.map.kakao.com/1";

    @Mock
    private KakaoLocalClient kakaoLocalClient;

    private SimpleMeterRegistry meterRegistry;
    private PlaceUrlEnricher enricher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        enricher = new PlaceUrlEnricher(kakaoLocalClient, new AiCourseMetrics(meterRegistry),
            Runnable::run);
    }

    private double counted(String result) {
        return meterRegistry.get(AiCourseMetrics.PLACE_URL).tag("result", result)
            .counter().count();
    }

    private static GroundedPlace place(String name, String url) {
        return new GroundedPlace(name, SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON,
            "경북 경주시 계림로 9", url, CandidateSourceType.SEEDED, null);
    }

    private static Document document(double latitude, double longitude) {
        return new Document("1", "천마총", "여행 > 관광명소", "AT4", "관광명소", "",
            "경북 경주시 황남동", "경북 경주시 계림로 9",
            String.valueOf(longitude), String.valueOf(latitude), URL, null);
    }

    @Nested
    @DisplayName("대상 선별 — 배치된 장소 중 URL 이 빈 것만")
    class Targeting {

        @Test
        @DisplayName("이미 URL 이 있는 장소는 부르지 않는다 — SUGGESTED 는 그라운딩에서 받았다")
        void skipsPlacesThatAlreadyHaveUrl() {
            List<GroundedPlace> result = enricher.enrich("경주",
                List.of(place("천마총", URL)), CourseDeadline.unbounded());

            verifyNoInteractions(kakaoLocalClient);
            assertThat(result.get(0).placeUrl()).isEqualTo(URL);
        }

        @Test
        @DisplayName("URL 이 빈 장소만 골라 한 번씩 부른다")
        void callsOnlyForMissingUrls() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(document(CHEONMACHONG_LAT, CHEONMACHONG_LON)));

            enricher.enrich("경주",
                List.of(place("천마총", URL), place("첨성대", null), place("대릉원", "  ")),
                CourseDeadline.unbounded());

            verify(kakaoLocalClient, times(2)).lookupBestPlace(anyString(), eq("경주"));
            verify(kakaoLocalClient, never()).lookupBestPlace(eq("천마총"), anyString());
        }

        @Test
        @DisplayName("반환 목록의 크기와 순서가 유지된다 — 호출자가 인덱스로 되돌려 놓는다")
        void preservesOrderAndSize() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.NoResult());

            List<GroundedPlace> result = enricher.enrich("경주",
                List.of(place("가", null), place("나", URL), place("다", null)),
                CourseDeadline.unbounded());

            assertThat(result).extracting(GroundedPlace::name).containsExactly("가", "나", "다");
        }
    }

    @Nested
    @DisplayName("수락 조건 둘 — 이름 게이트 그리고 거리 300m")
    class Acceptance {

        @Test
        @DisplayName("이름이 맞고 좌표도 가까우면 URL 을 붙인다")
        void attachesUrlWhenBothConditionsPass() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(document(NAEMUL_LAT, NAEMUL_LON)));

            List<GroundedPlace> result = enricher.enrich("경주",
                List.of(place("천마총", null)), CourseDeadline.unbounded());

            // 288m — 임계값 안이다.
            assertThat(result.get(0).placeUrl()).isEqualTo(URL);
        }

        @Test
        @DisplayName("이름이 맞아도 좌표가 300m 를 넘으면 비운다 — 같은 상호명의 다른 지점이다")
        void rejectsWhenTooFar() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(document(CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON)));

            List<GroundedPlace> result = enricher.enrich("경주",
                List.of(place("천마총", null)), CourseDeadline.unbounded());

            // 엉뚱한 장소의 URL 은 URL 없음보다 나쁘다.
            assertThat(result.get(0).placeUrl()).isNull();
        }

        @Test
        @DisplayName("이름 게이트에서 걸리면 비운다")
        void rejectsNameMismatch() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.NameMismatch("전혀다른가게"));

            assertThat(enricher.enrich("경주", List.of(place("천마총", null)),
                CourseDeadline.unbounded()).get(0).placeUrl()).isNull();
        }

        @Test
        @DisplayName("좌표가 없는 응답은 거리 검증을 못 하므로 비운다")
        void rejectsWhenCoordinateMissing() {
            Document noCoordinate = new Document("1", "천마총", "여행", "AT4", "관광명소", "",
                "경북 경주시 황남동", "경북 경주시 계림로 9", "", null, URL, null);
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(noCoordinate));

            assertThat(enricher.enrich("경주", List.of(place("천마총", null)),
                CourseDeadline.unbounded()).get(0).placeUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("fail-open — URL 은 코스 성립 조건이 아니다")
    class FailOpen {

        @Test
        @DisplayName("카카오가 죽어도 예외 없이 URL 만 빈다")
        void kakaoOutageLeavesUrlEmpty() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Failed(ApiFailureCause.TRANSPORT_ERROR, "connect"));

            List<GroundedPlace> result = enricher.enrich("경주",
                List.of(place("천마총", null)), CourseDeadline.unbounded());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).placeUrl()).isNull();
            // 좌표는 이미 있으므로 코스는 성립한다.
            assertThat(result.get(0).latitude()).isEqualTo(CHEONMACHONG_LAT);
        }

        @Test
        @DisplayName("예산이 부족하면 통째로 건너뛴다 — 한 건도 부르지 않는다")
        void skipsEntirelyWhenBudgetExhausted() {
            List<GroundedPlace> result = enricher.enrich("경주",
                List.of(place("천마총", null)), CourseDeadline.startingNow(Duration.ZERO));

            verifyNoInteractions(kakaoLocalClient);
            assertThat(result.get(0).placeUrl()).isNull();
        }

        @Test
        @DisplayName("건너뛴 건수도 센다 — 조용히 빠지면 URL 채움률이 실제보다 좋아 보인다")
        void countsSkipped() {
            enricher.enrich("경주", List.of(place("천마총", null), place("첨성대", null)),
                CourseDeadline.startingNow(Duration.ZERO));

            assertThat(counted("skipped")).isEqualTo(2.0);
        }

        @Test
        @DisplayName("좌표가 멀어 비운 건수는 too_far 로 남는다 — 동명 업소 문제의 지표다")
        void countsTooFar() {
            when(kakaoLocalClient.lookupBestPlace(anyString(), anyString()))
                .thenReturn(new PlaceLookup.Found(document(CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON)));

            enricher.enrich("경주", List.of(place("천마총", null)), CourseDeadline.unbounded());

            assertThat(counted("too_far")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("빈 목록은 그대로 빈 목록이다")
        void emptyInputStaysEmpty() {
            assertThat(enricher.enrich("경주", List.of(), CourseDeadline.unbounded())).isEmpty();
        }
    }
}
