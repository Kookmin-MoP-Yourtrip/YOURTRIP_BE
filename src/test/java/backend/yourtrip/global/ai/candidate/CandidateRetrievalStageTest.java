package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.tour.TourApiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CandidateRetrievalStage} 단위 테스트 (ROADMAP 5-8).
 *
 * <p><b>executor 를 {@code Runnable::run} 으로 바꿔 실행을 결정론으로 만든다.</b> 이 테스트가
 * 확인해야 하는 것은 "병렬이 빠른가"가 아니라 <b>무엇을 몇 번 부르고, 실패했을 때 무엇이 남는가</b>다.
 * 진짜 스레드풀을 쓰면 그 질문이 타이밍에 흔들린다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CandidateRetrievalStage — 후보 풀 조립 (ROADMAP 5-8)")
class CandidateRetrievalStageTest {

    private static final double ANCHOR_LAT = 35.8386877792;
    private static final double ANCHOR_LON = 129.2104983997;
    private static final List<KeywordType> COUPLE = List.of(KeywordType.COUPLE);

    @Mock
    private AreaGeocoder areaGeocoder;
    @Mock
    private NaverLocalSeedSource naverLocalSeedSource;
    @Mock
    private TourApiSource tourApiSource;

    private SimpleMeterRegistry meterRegistry;
    private CandidateRetrievalStage stage;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        stage = new CandidateRetrievalStage(areaGeocoder, naverLocalSeedSource, tourApiSource,
            new AiCourseMetrics(meterRegistry), Runnable::run);
    }

    private double counted(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter() == null
            ? 0.0
            : meterRegistry.find(name).tags(tags).counter().count();
    }

    private static PlannerPlan plan(PlannerDayPlan... days) {
        return new PlannerPlan("경주 3일", "고도의 밤", List.of(days));
    }

    private static PlannerDayPlan day(int number, SlotType... slots) {
        return new PlannerDayPlan(number, "황리단길 일대", "대릉원", List.of(slots));
    }

    private void geocodeSucceeds() {
        when(areaGeocoder.geocode(anyString(), anyString(), anyString()))
            .thenReturn(GeocodeResult.resolved(ANCHOR_LAT, ANCHOR_LON, GeocodeOutcome.HIT));
    }

    private void naverReturns(PlaceCandidate... candidates) {
        when(naverLocalSeedSource.fetch(anyString(), any(), any(), any(), any()))
            .thenReturn(CandidateBatch.of(List.of(candidates)));
    }

    private void tourReturns(PlaceCandidate... candidates) {
        when(tourApiSource.fetch(anyDouble(), anyDouble(), anyInt()))
            .thenReturn(CandidateBatch.of(List.of(candidates)));
    }

    @Nested
    @DisplayName("호출 횟수 — 쿼터가 지연보다 희소한 자원이다")
    class CallCounts {

        @Test
        @DisplayName("같은 슬롯 타입이 두 자리여도 쿼리는 한 번이다")
        void distinctSlotTypesOnly() {
            geocodeSucceeds();
            naverReturns();
            when(tourApiSource.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(CandidateBatch.empty());

            // [ATTRACTION, MEAL, ATTRACTION] — ATTRACTION 이 두 자리다.
            stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION, SlotType.MEAL,
                SlotType.ATTRACTION)), List.of(), CourseDeadline.unbounded());

            // 슬롯 타입 2종 × (기본 1 + modifier 0) = 2회.
            verify(naverLocalSeedSource, times(2)).fetch(anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("슬롯마다 기본 쿼리 1회 + modifier 쿼리만큼 더 부른다")
        void basicPlusModifierQueries() {
            geocodeSucceeds();
            naverReturns();

            stage.retrieve("경주", plan(day(1, SlotType.CAFE)), COUPLE,
                CourseDeadline.unbounded());

            // 연인 키워드의 검색 가능한 상위 2개(야경·루프탑) → 기본 1 + 2 = 3회.
            verify(naverLocalSeedSource, times(3)).fetch(anyString(), eq(SlotType.CAFE), any(),
                any(), any());
            verify(naverLocalSeedSource).fetch(anyString(), eq(SlotType.CAFE), isNull(), any(),
                any());
        }

        @Test
        @DisplayName("TourAPI 는 관광 슬롯이 요구하는 contentTypeId 합집합만큼만 부른다")
        void tourApiCalledOncePerContentType() {
            geocodeSucceeds();
            naverReturns();
            when(tourApiSource.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(CandidateBatch.empty());

            // ATTRACTION 과 VIEWPOINT 는 같은 12·14 를 요구한다 — 네 번이 아니라 두 번이어야 한다.
            stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION, SlotType.VIEWPOINT,
                SlotType.MEAL)), List.of(), CourseDeadline.unbounded());

            verify(tourApiSource, times(2)).fetch(anyDouble(), anyDouble(), anyInt());
        }

        @Test
        @DisplayName("관광 슬롯이 없는 day 는 TourAPI 를 아예 부르지 않는다")
        void noTourApiForCommercialOnlyDay() {
            geocodeSucceeds();
            naverReturns();

            stage.retrieve("경주", plan(day(1, SlotType.MEAL, SlotType.CAFE)), List.of(),
                CourseDeadline.unbounded());

            verify(tourApiSource, never()).fetch(anyDouble(), anyDouble(), anyInt());
        }

        @Test
        @DisplayName("지오코딩은 day 당 한 번이다")
        void geocodesOncePerDay() {
            geocodeSucceeds();
            naverReturns();

            stage.retrieve("경주", plan(day(1, SlotType.MEAL), day(2, SlotType.CAFE)), List.of(),
                CourseDeadline.unbounded());

            verify(areaGeocoder, times(2)).geocode(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("조립 결과")
    class Assembly {

        @Test
        @DisplayName("day × 슬롯타입마다 슬롯이 하나씩 생긴다")
        void oneSlotPerDayAndType() {
            geocodeSucceeds();
            naverReturns(CandidateFixtures.cafe("커피플레이스", "경북 경주시 포석로 1080", 1, null));

            CandidatePool pool = stage.retrieve("경주",
                plan(day(1, SlotType.MEAL, SlotType.CAFE), day(2, SlotType.CAFE)),
                List.of(), CourseDeadline.unbounded());

            assertThat(pool.slots()).hasSize(3);
            assertThat(pool.find(2, SlotType.CAFE)).isPresent();
            assertThat(pool.find(2, SlotType.MEAL)).isEmpty();
        }

        @Test
        @DisplayName("TourAPI 후보는 요청한 슬롯 타입으로 다시 붙어 목록에 들어간다")
        void tourCandidatesAreReattachedToSlot() {
            geocodeSucceeds();
            when(naverLocalSeedSource.fetch(anyString(), any(), any(), any(), any()))
                .thenReturn(CandidateBatch.empty());
            // contentTypeId=12 응답의 기본 슬롯은 ATTRACTION 이지만, 요청한 자리는 VIEWPOINT 다.
            tourReturns(CandidateFixtures.listed("첨성대", CandidateFixtures.CHEOMSEONGDAE_LAT,
                CandidateFixtures.CHEOMSEONGDAE_LON, 0.5, Set.of()));

            CandidatePool pool = stage.retrieve("경주", plan(day(1, SlotType.VIEWPOINT)),
                List.of(), CourseDeadline.unbounded());

            assertThat(pool.findOrEmpty(1, SlotType.VIEWPOINT).candidates())
                .extracting(PlaceCandidate::slotType).containsOnly(SlotType.VIEWPOINT);
        }

        @Test
        @DisplayName("시드 후보가 목록 앞에 온다 — 순서 자체가 Curator 에게 주는 신호다")
        void seededComesFirst() {
            geocodeSucceeds();
            naverReturns(CandidateFixtures.seeded("대릉원", 1, ANCHOR_LAT, ANCHOR_LON));
            tourReturns(CandidateFixtures.listed("골굴사", CandidateFixtures.NAEMUL_LAT,
                CandidateFixtures.NAEMUL_LON, 0.1, Set.of()));

            CandidatePool pool = stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION)),
                List.of(), CourseDeadline.unbounded());

            assertThat(pool.findOrEmpty(1, SlotType.ATTRACTION).candidates())
                .extracting(PlaceCandidate::name).containsExactly("대릉원", "골굴사");
        }

        @Test
        @DisplayName("두 소스가 같은 장소를 가리키면 하나로 합쳐진다")
        void mergesSamePlaceAcrossSources() {
            geocodeSucceeds();
            naverReturns(CandidateFixtures.seeded("천마총", 2,
                CandidateFixtures.CHEONMACHONG_LAT, CandidateFixtures.CHEONMACHONG_LON));
            tourReturns(CandidateFixtures.listed("천마총", CandidateFixtures.CHEONMACHONG_LAT,
                CandidateFixtures.CHEONMACHONG_LON, 0.4, Set.of(StyleTag.HISTORY)));

            List<PlaceCandidate> candidates =
                stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION)), List.of(),
                    CourseDeadline.unbounded()).findOrEmpty(1, SlotType.ATTRACTION).candidates();

            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).seeded()).isTrue();
            assertThat(candidates.get(0).official()).isTrue();
        }
    }

    @Nested
    @DisplayName("fail-open — 어느 조각이 죽어도 나머지로 성립한다")
    class FailOpen {

        @Test
        @DisplayName("지오코딩이 실패하면 그 day 의 TourAPI 만 건너뛴다 — 시더는 그대로 돈다")
        void geocodeFailureSkipsOnlyTourApi() {
            when(areaGeocoder.geocode(anyString(), anyString(), anyString()))
                .thenReturn(GeocodeResult.failed());
            naverReturns(CandidateFixtures.seeded("대릉원", 1, ANCHOR_LAT, ANCHOR_LON));

            CandidatePool pool = stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION)),
                List.of(), CourseDeadline.unbounded());

            verifyNoInteractions(tourApiSource);
            assertThat(pool.findOrEmpty(1, SlotType.ATTRACTION).candidates()).hasSize(1);
        }

        @Test
        @DisplayName("네이버가 죽으면 관광 슬롯은 TourAPI 만으로 채워진다")
        void naverFailureLeavesTourApi() {
            geocodeSucceeds();
            when(naverLocalSeedSource.fetch(anyString(), any(), any(), any(), any()))
                .thenReturn(CandidateBatch.failed(ApiFailureCause.QUOTA_EXCEEDED));
            tourReturns(CandidateFixtures.listed("골굴사", CandidateFixtures.NAEMUL_LAT,
                CandidateFixtures.NAEMUL_LON, 1.2, Set.of()));

            CandidatePool pool = stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION)),
                List.of(), CourseDeadline.unbounded());

            assertThat(pool.findOrEmpty(1, SlotType.ATTRACTION).candidates())
                .extracting(PlaceCandidate::name).containsExactly("골굴사");
        }

        @Test
        @DisplayName("둘 다 죽으면 빈 풀이다 — 예외가 아니라 초안 구조로 degrade")
        void bothSourcesDownYieldsEmptyPool() {
            geocodeSucceeds();
            when(naverLocalSeedSource.fetch(anyString(), any(), any(), any(), any()))
                .thenReturn(CandidateBatch.failed(ApiFailureCause.TRANSPORT_ERROR));
            when(tourApiSource.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(CandidateBatch.failed(ApiFailureCause.TRANSPORT_ERROR));

            CandidatePool pool = stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION)),
                List.of(), CourseDeadline.unbounded());

            assertThat(pool.isEmpty()).isTrue();
            // 슬롯 자체는 남는다 — Curator 입력의 구조가 무너지지는 않는다.
            assertThat(pool.slots()).hasSize(1);
        }

        @Test
        @DisplayName("예산이 이미 소진됐으면 아무것도 부르지 않는다")
        void expiredDeadlineSkipsEverything() {
            lenient().when(areaGeocoder.geocode(anyString(), anyString(), anyString()))
                .thenReturn(GeocodeResult.failed());

            CandidatePool pool = stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION)),
                List.of(), CourseDeadline.startingNow(Duration.ZERO));

            assertThat(pool.isEmpty()).isTrue();
            verifyNoInteractions(areaGeocoder, naverLocalSeedSource, tourApiSource);
        }

        @Test
        @DisplayName("Planner 플랜이 비면 빈 풀이다 — 외부 호출도 없다")
        void emptyPlanYieldsEmptyPool() {
            CandidatePool pool = stage.retrieve("경주", plan(), List.of(),
                CourseDeadline.unbounded());

            assertThat(pool.isEmpty()).isTrue();
            verifyNoInteractions(areaGeocoder, naverLocalSeedSource, tourApiSource);
        }
    }

    @Nested
    @DisplayName("day 간 중복 호출 제거 — 쿼터가 가장 빠듯한 소스다")
    class TourCallDeduplication {

        @Test
        @DisplayName("좌표가 같은 day 들은 TourAPI 를 한 번만 부르고 결과를 나눠 쓴다")
        void sameCoordinateDaysShareOneCall() {
            // 3일 내내 같은 권역이면 Planner 가 같은 anchor 를 주고 지오코딩도 같은 좌표를
            // 돌려준다. 그대로 두면 개발계정 일 1,000건을 필요량의 세 배로 쓴다.
            geocodeSucceeds();
            naverReturns();
            tourReturns(CandidateFixtures.listed("골굴사", CandidateFixtures.NAEMUL_LAT,
                CandidateFixtures.NAEMUL_LON, 1.2, Set.of()));

            CandidatePool pool = stage.retrieve("경주", plan(
                day(1, SlotType.ATTRACTION), day(2, SlotType.ATTRACTION),
                day(3, SlotType.ATTRACTION)), List.of(), CourseDeadline.unbounded());

            // 12·14 두 번뿐 — day 수만큼 곱해지지 않는다.
            verify(tourApiSource, times(2)).fetch(anyDouble(), anyDouble(), anyInt());
            // 그래도 세 day 모두 후보를 받는다.
            for (int day = 1; day <= 3; day++) {
                assertThat(pool.findOrEmpty(day, SlotType.ATTRACTION).candidates())
                    .extracting(PlaceCandidate::name).containsExactly("골굴사");
            }
        }

        @Test
        @DisplayName("좌표가 다르면 각각 부른다 — 권역이 갈린 여행은 합치지 않는다")
        void differentCoordinatesCallSeparately() {
            when(areaGeocoder.geocode(anyString(), anyString(), anyString()))
                .thenReturn(GeocodeResult.resolved(ANCHOR_LAT, ANCHOR_LON, GeocodeOutcome.HIT))
                .thenReturn(GeocodeResult.resolved(CandidateFixtures.CHEOMSEONGDAE_LAT,
                    CandidateFixtures.CHEOMSEONGDAE_LON, GeocodeOutcome.HIT));
            naverReturns();
            when(tourApiSource.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(CandidateBatch.empty());

            stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION), day(2, SlotType.ATTRACTION)),
                List.of(), CourseDeadline.unbounded());

            // 좌표 2종 × 분류 2종 = 4회.
            verify(tourApiSource, times(4)).fetch(anyDouble(), anyDouble(), anyInt());
        }

        @Test
        @DisplayName("메트릭은 여전히 day 수만큼 센다 — 중복 제거가 지표의 단위를 바꾸지 않는다")
        void metricStaysPerDay() {
            geocodeSucceeds();
            naverReturns();
            when(tourApiSource.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(CandidateBatch.empty());

            stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION), day(2, SlotType.ATTRACTION)),
                List.of(), CourseDeadline.unbounded());

            // 호출은 2회지만 "(day, 분류)에 후보가 모였는가"는 2일 × 2분류 = 4건이다.
            // 호출을 세는 쪽으로 바꾸면 5-9 집계와 단위가 어긋나 전후 비교가 깨진다.
            assertThat(counted(AiCourseMetrics.CANDIDATE_RETRIEVAL,
                "source", AiCourseMetrics.SOURCE_TOUR_API, "result", "empty")).isEqualTo(4.0);
        }
    }

    @Nested
    @DisplayName("병렬 구조 — 시더와 TourAPI 는 한 라운드다")
    class OneRound {

        /**
         * <b>진짜 스레드풀을 쓰는 유일한 테스트다.</b> 다른 테스트는 {@code Runnable::run} 으로
         * 결정론을 얻지만, "두 소스가 동시에 떠 있는가"는 직렬 실행에서는 물을 수조차 없는 질문이다.
         *
         * <p>시더가 TourAPI 호출을 기다리게 걸어 둔다 — 라운드가 갈려 있으면 그 래치는 시더가
         * 전부 끝난 뒤에야 풀리므로 대기가 타임아웃으로 끝난다.
         */
        @Test
        @DisplayName("시더가 TourAPI 를 기다려도 교착되지 않는다 — 둘이 함께 떠 있다")
        void bothSourcesAreInFlightTogether() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch tourCalled = new CountDownLatch(1);
            AtomicBoolean seedSawTour = new AtomicBoolean(false);

            when(areaGeocoder.geocode(anyString(), anyString(), anyString()))
                .thenReturn(GeocodeResult.resolved(ANCHOR_LAT, ANCHOR_LON, GeocodeOutcome.HIT));
            when(naverLocalSeedSource.fetch(anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    seedSawTour.set(tourCalled.await(2, TimeUnit.SECONDS));
                    return CandidateBatch.of(List.of(
                        CandidateFixtures.seeded("대릉원", 1, ANCHOR_LAT, ANCHOR_LON)));
                });
            when(tourApiSource.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    tourCalled.countDown();
                    return CandidateBatch.empty();
                });

            CandidateRetrievalStage parallelStage = new CandidateRetrievalStage(areaGeocoder,
                naverLocalSeedSource, tourApiSource, new AiCourseMetrics(meterRegistry), pool);
            try {
                CandidatePool result = parallelStage.retrieve("경주",
                    plan(day(1, SlotType.ATTRACTION)), List.of(), CourseDeadline.unbounded());

                assertThat(seedSawTour)
                    .as("시더가 도는 동안 TourAPI 호출이 시작되지 않았다면 라운드가 갈린 것이다")
                    .isTrue();
                assertThat(result.findOrEmpty(1, SlotType.ATTRACTION).candidates()).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("메트릭 (ROADMAP 5-6)")
    class Metrics {

        @Test
        @DisplayName("지오코딩 결과를 단계별로 센다 — fallback 이 잦으면 Planner anchor 문제다")
        void countsGeocodeOutcome() {
            when(areaGeocoder.geocode(anyString(), anyString(), anyString()))
                .thenReturn(GeocodeResult.resolved(ANCHOR_LAT, ANCHOR_LON,
                    GeocodeOutcome.FALLBACK_AREA));
            naverReturns();

            stage.retrieve("경주", plan(day(1, SlotType.MEAL)), List.of(),
                CourseDeadline.unbounded());

            assertThat(counted(AiCourseMetrics.GEOCODE, "result", "fallback_area")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("소스별 결말을 나눠 센다 — empty 와 failed 를 뭉치면 지표가 오염된다")
        void countsRetrievalBySourceAndOutcome() {
            geocodeSucceeds();
            when(naverLocalSeedSource.fetch(anyString(), any(), any(), any(), any()))
                .thenReturn(CandidateBatch.failed(ApiFailureCause.QUOTA_EXCEEDED));
            when(tourApiSource.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(CandidateBatch.empty());

            stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION)), List.of(),
                CourseDeadline.unbounded());

            assertThat(counted(AiCourseMetrics.CANDIDATE_RETRIEVAL,
                "source", AiCourseMetrics.SOURCE_NAVER_LOCAL, "result", "failed")).isEqualTo(1.0);
            assertThat(counted(AiCourseMetrics.CANDIDATE_RETRIEVAL,
                "source", AiCourseMetrics.SOURCE_TOUR_API, "result", "empty")).isEqualTo(2.0);
        }

        @Test
        @DisplayName("좌표를 못 얻어 부르지 못한 TourAPI 는 skipped 다 — empty 가 아니다")
        void countsSkippedWhenGeocodeFails() {
            when(areaGeocoder.geocode(anyString(), anyString(), anyString()))
                .thenReturn(GeocodeResult.failed());
            naverReturns();

            stage.retrieve("경주", plan(day(1, SlotType.ATTRACTION)), List.of(),
                CourseDeadline.unbounded());

            // "물어봤는데 없더라"와 "물어보지 못했다"는 다른 사건이다.
            assertThat(counted(AiCourseMetrics.CANDIDATE_RETRIEVAL,
                "source", AiCourseMetrics.SOURCE_TOUR_API, "result", "skipped")).isEqualTo(2.0);
            assertThat(counted(AiCourseMetrics.CANDIDATE_RETRIEVAL,
                "source", AiCourseMetrics.SOURCE_TOUR_API, "result", "empty")).isZero();
        }

        @Test
        @DisplayName("발생하지 않은 조합도 0 으로 등록돼 있다 — 시계열 부재를 0 으로 오독하지 않게")
        void registersZeroSeriesUpFront() {
            assertThat(meterRegistry.find(AiCourseMetrics.GROUNDING_MATCH)
                .tags("result", "no_result", "source", "suggested").counter()).isNotNull();
        }
    }

    @Nested
    @DisplayName("TourAPI 분류와 슬롯의 대응")
    class ContentTypeRouting {

        @Test
        @DisplayName("체험 슬롯은 레포츠를, 볼거리 슬롯은 관광지·문화시설을 부른다")
        void experienceAndSightUseDifferentContentTypes() {
            geocodeSucceeds();
            naverReturns();
            when(tourApiSource.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(CandidateBatch.empty());

            stage.retrieve("경주", plan(day(1, SlotType.EXPERIENCE, SlotType.ATTRACTION)),
                List.of(), CourseDeadline.unbounded());

            verify(tourApiSource).fetch(anyDouble(), anyDouble(),
                eq(TourApiClient.CONTENT_TYPE_LEISURE));
            verify(tourApiSource).fetch(anyDouble(), anyDouble(),
                eq(TourApiClient.CONTENT_TYPE_ATTRACTION));
            verify(tourApiSource).fetch(anyDouble(), anyDouble(),
                eq(TourApiClient.CONTENT_TYPE_CULTURE));
        }
    }
}
