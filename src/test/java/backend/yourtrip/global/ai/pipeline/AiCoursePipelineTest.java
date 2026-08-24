package backend.yourtrip.global.ai.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.agent.CuratorAgent;
import backend.yourtrip.global.ai.agent.DefaultPlannerPlans;
import backend.yourtrip.global.ai.agent.PlannerAgent;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateRetrievalStage;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.candidate.StyleTag;
import backend.yourtrip.global.ai.config.AiCourseProperties;
import backend.yourtrip.global.ai.exception.LlmTransportException;
import backend.yourtrip.global.ai.grounding.GroundedDay;
import backend.yourtrip.global.ai.grounding.GroundedPlace;
import backend.yourtrip.global.ai.grounding.GroundedSlot;
import backend.yourtrip.global.ai.grounding.GroundingStage;
import backend.yourtrip.global.ai.grounding.PlaceUrlEnricher;
import backend.yourtrip.global.ai.route.RouteOptimizer;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.AiCourseErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 묻는 것은 <b>"조각이 다 있을 때가 아니라 하나가 빠졌을 때 무엇이 남는가"</b>다 (ROADMAP 7-1·7-3·7-4).
 *
 * <p>스테이지는 목이지만 <b>{@link RouteOptimizer}와 {@link AiCourseMetrics}는 실물을 쓴다</b> —
 * 전자는 외부 의존이 없는 순수 계산이라 목으로 바꾸면 "순서와 시각이 실제로 실리는가"를 물을 수
 * 없게 되고, 후자는 시계열 값 자체가 단언 대상이기 때문이다(5·6단계가 세운 관례).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiCoursePipeline — 스테이지 조립과 degrade (ROADMAP 7-1·7-3·7-4)")
class AiCoursePipelineTest {

    private static final String LOCATION = "경주";
    private static final List<KeywordType> KEYWORDS = List.of(KeywordType.SOLO);

    // 경주 실좌표 — 지어낸 좌표로 동선을 시험하면 거리 계산이 테스트 안에서만 성립한다.
    private static final double CHEONMACHONG_LAT = 35.8386877792;
    private static final double CHEONMACHONG_LON = 129.2104983997;
    private static final double CHEOMSEONGDAE_LAT = 35.8347222;
    private static final double CHEOMSEONGDAE_LON = 129.2192222;

    @Mock
    private PlannerAgent plannerAgent;
    @Mock
    private CandidateRetrievalStage candidateRetrievalStage;
    @Mock
    private CuratorAgent curatorAgent;
    @Mock
    private GroundingStage groundingStage;
    @Mock
    private PlaceUrlEnricher placeUrlEnricher;

    private SimpleMeterRegistry registry;
    private AiCourseMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AiCourseMetrics(registry);
    }

    private AiCoursePipeline pipelineWithBudget(int budgetMs) {
        return new AiCoursePipeline(plannerAgent, candidateRetrievalStage, curatorAgent,
            groundingStage, new RouteOptimizer(), placeUrlEnricher, metrics,
            new AiCourseProperties(budgetMs));
    }

    private AiCoursePipeline pipeline() {
        return pipelineWithBudget(30_000);
    }

    // 픽스처

    private static PlannerPlan plan(int days, SlotType... slots) {
        List<PlannerDayPlan> dayPlans = new ArrayList<>(days);
        for (int day = 1; day <= days; day++) {
            dayPlans.add(new PlannerDayPlan(day, "황리단길", "대릉원", "고분 산책",
                LocalTime.of(10, 0), List.of(slots)));
        }
        return new PlannerPlan("경주 감성 여행", "천년 고도를 걷는 코스", dayPlans);
    }

    private static GroundedPlace place(String name, SlotType slotType, double lat, double lon) {
        return new GroundedPlace(name, slotType, lat, lon, "경북 경주시 " + name + "로 1", null,
            CandidateSourceType.SEEDED, null);
    }

    private static GroundedDay groundedDay(int day, GroundedPlace... places) {
        List<GroundedSlot> slots = Arrays.stream(places)
            .map(place -> new GroundedSlot(place.slotType(), List.of(place)))
            .toList();
        return new GroundedDay(day, slots);
    }

    private static GroundedDay emptyGroundedDay(int day, SlotType... slotTypes) {
        return new GroundedDay(day, Arrays.stream(slotTypes)
            .map(slotType -> new GroundedSlot(slotType, List.<GroundedPlace>of()))
            .toList());
    }

    /** URL 보강은 기본적으로 항등이다 — 그 자체를 묻는 테스트에서만 따로 스텁한다. */
    private void givenEnricherIsIdentity() {
        given(placeUrlEnricher.enrich(anyString(), anyList(), any()))
            .willAnswer(invocation -> invocation.getArgument(1));
    }

    private void givenGrounded(List<GroundedDay> days) {
        given(groundingStage.ground(anyString(), anyList(), any(), any())).willReturn(days);
    }

    private void givenPlannerAndCurator(PlannerPlan plan) {
        given(plannerAgent.plan(anyString(), anyInt(), anyList(), any())).willReturn(plan);
        given(candidateRetrievalStage.retrieve(anyString(), any(), anyList(), any()))
            .willReturn(CandidatePool.empty());
        given(curatorAgent.curate(any(), any(), anyList(), any())).willReturn(List.of());
    }

    @Nested
    @DisplayName("정상 조립")
    class HappyPath {

        @Test
        @DisplayName("스테이지를 설계 순서대로 한 번씩 부른다")
        void callsStagesInOrder() {
            PlannerPlan plan = plan(1, SlotType.ATTRACTION);
            givenPlannerAndCurator(plan);
            givenGrounded(List.of(groundedDay(1,
                place("천마총", SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON))));
            givenEnricherIsIdentity();

            pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS));

            InOrder order = inOrder(plannerAgent, candidateRetrievalStage, curatorAgent,
                groundingStage, placeUrlEnricher);
            order.verify(plannerAgent).plan(anyString(), anyInt(), anyList(), any());
            order.verify(candidateRetrievalStage).retrieve(anyString(), any(), anyList(), any());
            order.verify(curatorAgent).curate(any(), any(), anyList(), any());
            order.verify(groundingStage).ground(anyString(), anyList(), any(), any());
            order.verify(placeUrlEnricher).enrich(anyString(), anyList(), any());
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("배치된 순서와 시각이 초안에 그대로 실린다 — 이 순서가 곧 저장 순서다")
        void carriesOrderAndTimes() {
            PlannerPlan plan = plan(1, SlotType.ATTRACTION, SlotType.MEAL);
            givenPlannerAndCurator(plan);
            givenGrounded(List.of(groundedDay(1,
                place("천마총", SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON),
                place("황남밀면", SlotType.MEAL, CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON))));
            givenEnricherIsIdentity();

            AiCourseDraft draft = pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS));

            assertThat(draft.title()).isEqualTo("경주 감성 여행");
            assertThat(draft.concept()).isEqualTo("천년 고도를 걷는 코스");
            assertThat(draft.days()).hasSize(1);

            AiCourseDay day = draft.days().get(0);
            assertThat(day.places()).extracting(courseP -> courseP.place().name())
                .containsExactlyInAnyOrder("천마총", "황남밀면");
            assertThat(day.places()).allSatisfy(courseP -> {
                assertThat(courseP.startTime()).isNotNull();
                assertThat(courseP.stayMinutes()).isPositive();
            });
        }

        @Test
        @DisplayName("Planner 의 dayStartTime 이 최적화 입력으로 넘어간다 — 표시용이 아니다")
        void usesPlannerDayStartTime() {
            PlannerPlan plan = plan(1, SlotType.ATTRACTION);
            givenPlannerAndCurator(plan);
            givenGrounded(List.of(groundedDay(1,
                place("천마총", SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON))));
            givenEnricherIsIdentity();

            AiCourseDraft draft = pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS));

            assertThat(draft.days().get(0).startTime()).isEqualTo(LocalTime.of(10, 0));
        }
    }

    @Nested
    @DisplayName("Planner 실패 — 기본 플랜으로 degrade (7-3)")
    class PlannerDegrade {

        @Test
        @DisplayName("예외가 새지 않고 area = location 인 결정론적 플랜으로 진행한다")
        void fallsBackToDefaultPlan() {
            given(plannerAgent.plan(anyString(), anyInt(), anyList(), any()))
                .willThrow(new LlmTransportException("planner", 3, "429 가 계속됐다", null));
            given(candidateRetrievalStage.retrieve(anyString(), any(), anyList(), any()))
                .willReturn(CandidatePool.empty());
            given(curatorAgent.curate(any(), any(), anyList(), any())).willReturn(List.of());
            givenGrounded(List.of(groundedDay(1,
                place("천마총", SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON))));
            givenEnricherIsIdentity();

            AiCourseDraft draft = pipeline().generate(CourseBrief.of(LOCATION, 2, KEYWORDS));

            ArgumentCaptor<PlannerPlan> captor = ArgumentCaptor.forClass(PlannerPlan.class);
            verify(candidateRetrievalStage)
                .retrieve(anyString(), captor.capture(), anyList(), any());

            PlannerPlan used = captor.getValue();
            assertThat(used.days()).hasSize(2);
            assertThat(used.days()).allSatisfy(day -> {
                assertThat(day.area()).isEqualTo(LOCATION);
                assertThat(day.slots()).isEqualTo(DefaultPlannerPlans.DEFAULT_SLOTS);
            });
            assertThat(draft.title()).isEqualTo(DefaultPlannerPlans.defaultTitle(LOCATION, 2));
        }
    }

    @Nested
    @DisplayName("Curator 가 비운 자리 — 후보 목록에서 채운다 (7-3)")
    class CuratorDegrade {

        @Test
        @DisplayName("빈 슬롯이 목록 상위 3개로 채워져 그라운딩에 넘어간다")
        void fillsEmptySlotFromPool() {
            PlannerPlan plan = plan(1, SlotType.ATTRACTION);
            CandidatePool pool = new CandidatePool(List.of(new CandidateSlot(1,
                SlotType.ATTRACTION, List.of(
                candidate("천마총"), candidate("첨성대"), candidate("동궁과 월지")))));

            given(plannerAgent.plan(anyString(), anyInt(), anyList(), any())).willReturn(plan);
            given(candidateRetrievalStage.retrieve(anyString(), any(), anyList(), any()))
                .willReturn(pool);
            given(curatorAgent.curate(any(), any(), anyList(), any()))
                .willReturn(List.of(new CuratedDay(1,
                    List.of(new CuratedSlot(SlotType.ATTRACTION, List.of())))));
            givenGrounded(List.of(groundedDay(1,
                place("천마총", SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON))));
            givenEnricherIsIdentity();

            pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<CuratedDay>> captor = ArgumentCaptor.forClass(List.class);
            verify(groundingStage).ground(anyString(), captor.capture(), any(), any());

            List<CuratedPlace> choices = captor.getValue().get(0).slots().get(0).choices();
            assertThat(choices).extracting(CuratedPlace::placeName)
                .containsExactly("천마총", "첨성대", "동궁과 월지");
            // source 가 SEEDED 라야 GroundingStage 가 좌표를 승계해 카카오를 부르지 않는다.
            assertThat(choices).extracting(CuratedPlace::source)
                .containsOnly(CandidateSourceType.SEEDED);
            assertThat(choices).extracting(CuratedPlace::listIndex).containsExactly(0, 1, 2);
        }

        private static PlaceCandidate candidate(String name) {
            return new PlaceCandidate(CandidateSourceType.SEEDED, name, "경북 경주시 " + name + "로 1",
                CHEONMACHONG_LAT, CHEONMACHONG_LON, SlotType.ATTRACTION, Set.<StyleTag>of(), 1,
                null, null, "관광,명소>유적지");
        }
    }

    @Nested
    @DisplayName("hard fail 은 하나뿐이다 (7-4)")
    class HardFail {

        @Test
        @DisplayName("전 day 장소 0개인데 예산이 남았으면 AI_GROUNDING_FAILED(503)")
        void groundingFailedWhenNoPlaceSurvives() {
            givenPlannerAndCurator(plan(2, SlotType.ATTRACTION));
            givenGrounded(List.of(
                emptyGroundedDay(1, SlotType.ATTRACTION),
                emptyGroundedDay(2, SlotType.ATTRACTION)));

            assertThatThrownBy(() -> pipeline().generate(CourseBrief.of(LOCATION, 2, KEYWORDS)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AiCourseErrorCode.AI_GROUNDING_FAILED);

            // 실패해도 URL 보강에는 가지 않는다 — 채울 장소가 없다.
            verify(placeUrlEnricher, never()).enrich(anyString(), anyList(), any());
        }

        @Test
        @DisplayName("같은 상황에서 예산이 소진돼 있었으면 AI_COURSE_TIMEOUT(504)")
        void timeoutWhenBudgetIsGone() {
            givenPlannerAndCurator(plan(1, SlotType.ATTRACTION));
            given(groundingStage.ground(anyString(), anyList(), any(), any()))
                .willAnswer(invocation -> {
                    // 예산 1ms 를 확실히 넘긴다 — "만료됐다"를 시계에 맡기지 않는다.
                    Thread.sleep(20);
                    return List.of(emptyGroundedDay(1, SlotType.ATTRACTION));
                });

            assertThatThrownBy(
                () -> pipelineWithBudget(1).generate(CourseBrief.of(LOCATION, 1, KEYWORDS)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AiCourseErrorCode.AI_COURSE_TIMEOUT);
        }

        @Test
        @DisplayName("day 하나만 비는 것은 실패가 아니다 — 부분 실패는 통과시킨다")
        void partialEmptyDayIsNotFailure() {
            givenPlannerAndCurator(plan(2, SlotType.ATTRACTION));
            givenGrounded(List.of(
                groundedDay(1, place("천마총", SlotType.ATTRACTION,
                    CHEONMACHONG_LAT, CHEONMACHONG_LON)),
                emptyGroundedDay(2, SlotType.ATTRACTION)));
            givenEnricherIsIdentity();

            AiCourseDraft draft = pipeline().generate(CourseBrief.of(LOCATION, 2, KEYWORDS));

            assertThat(draft.days()).hasSize(2);
            assertThat(draft.days().get(0).places()).hasSize(1);
            assertThat(draft.days().get(1).places()).isEmpty();
        }
    }

    @Nested
    @DisplayName("URL 보강 — 인덱스로 원자리에 되돌린다")
    class UrlEnrichment {

        @Test
        @DisplayName("day 를 넘어가도 URL 이 제자리에 붙는다")
        void reassemblesAcrossDays() {
            givenPlannerAndCurator(plan(2, SlotType.ATTRACTION));
            givenGrounded(List.of(
                groundedDay(1, place("천마총", SlotType.ATTRACTION,
                    CHEONMACHONG_LAT, CHEONMACHONG_LON)),
                groundedDay(2, place("첨성대", SlotType.ATTRACTION,
                    CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON))));
            given(placeUrlEnricher.enrich(anyString(), anyList(), any()))
                .willAnswer(invocation -> {
                    List<GroundedPlace> input = invocation.getArgument(1);
                    return input.stream()
                        .map(place -> place.withPlaceUrl("https://place.map.kakao.com/"
                            + place.name()))
                        .toList();
                });

            AiCourseDraft draft = pipeline().generate(CourseBrief.of(LOCATION, 2, KEYWORDS));

            assertThat(draft.days().get(0).places().get(0).place().placeUrl())
                .isEqualTo("https://place.map.kakao.com/천마총");
            assertThat(draft.days().get(1).places().get(0).place().placeUrl())
                .isEqualTo("https://place.map.kakao.com/첨성대");
        }

        @Test
        @DisplayName("보강 결과의 크기가 어긋나면 보강 전 목록을 쓴다 — URL 때문에 코스가 죽지 않는다")
        void fallsBackWhenSizeMismatches() {
            givenPlannerAndCurator(plan(1, SlotType.ATTRACTION, SlotType.MEAL));
            givenGrounded(List.of(groundedDay(1,
                place("천마총", SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON),
                place("황남밀면", SlotType.MEAL, CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON))));
            given(placeUrlEnricher.enrich(anyString(), anyList(), any())).willReturn(List.of());

            AiCourseDraft draft = pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS));

            assertThat(draft.days().get(0).places()).hasSize(2);
            assertThat(draft.days().get(0).places())
                .allSatisfy(place -> assertThat(place.place().placeUrl()).isNull());
        }
    }

    @Nested
    @DisplayName("메트릭 (7-5)")
    class Metrics {

        @Test
        @DisplayName("실행 전에도 시계열이 0으로 등록돼 있다 — 없는 것과 0은 다르다")
        void zeroSeriesRegisteredUpfront() {
            for (PipelineStage stage : PipelineStage.values()) {
                assertThat(registry.get(AiCourseMetrics.PIPELINE_DURATION)
                    .tag("stage", stage.name().toLowerCase())
                    .timer().count()).isZero();
            }
            assertThat(registry.get(AiCourseMetrics.CANDIDATE_ADOPTED)
                .tag("source", "suggested").tag("modifier", "true")
                .counter().count()).isZero();
            for (SlotFillOutcome outcome : SlotFillOutcome.values()) {
                assertThat(registry.get(AiCourseMetrics.CURATION_SLOT)
                    .tag("result", outcome.name().toLowerCase())
                    .counter().count()).isZero();
            }
        }

        @Test
        @DisplayName("Curator 가 고른 자리와 폴백이 채운 자리를 갈라 센다 — LLM 큐레이션이 꺼진 상태의 유일한 신호다")
        void recordsCurationSlotSplit() {
            PlannerPlan plan = plan(1, SlotType.ATTRACTION, SlotType.MEAL);
            CandidatePool pool = new CandidatePool(List.of(new CandidateSlot(1, SlotType.MEAL,
                List.of(new PlaceCandidate(CandidateSourceType.SEEDED, "황남밀면", "경북 경주시",
                    CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, SlotType.MEAL, Set.<StyleTag>of(), 1,
                    null, null, "음식점>한식")))));
            given(plannerAgent.plan(anyString(), anyInt(), anyList(), any())).willReturn(plan);
            given(candidateRetrievalStage.retrieve(anyString(), any(), anyList(), any()))
                .willReturn(pool);
            given(curatorAgent.curate(any(), any(), anyList(), any()))
                .willReturn(List.of(new CuratedDay(1, List.of(
                    new CuratedSlot(SlotType.ATTRACTION,
                        List.of(new CuratedPlace(CandidateSourceType.SUGGESTED, null, "천마총"))),
                    new CuratedSlot(SlotType.MEAL, List.of())))));
            givenGrounded(List.of(groundedDay(1,
                place("천마총", SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON))));
            givenEnricherIsIdentity();

            pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS));

            assertThat(curationSlot(SlotFillOutcome.CURATOR)).isEqualTo(1.0);
            assertThat(curationSlot(SlotFillOutcome.FALLBACK)).isEqualTo(1.0);
            assertThat(curationSlot(SlotFillOutcome.UNFILLED)).isZero();
        }

        @Test
        @DisplayName("후보도 없어 못 채운 자리는 unfilled 로 남는다 — hard fail 의 선행 지표다")
        void recordsUnfilledSlots() {
            givenPlannerAndCurator(plan(1, SlotType.ATTRACTION, SlotType.MEAL));
            givenGrounded(List.of(groundedDay(1,
                place("천마총", SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON))));
            givenEnricherIsIdentity();

            pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS));

            // givenPlannerAndCurator 는 Curator 가 빈 목록을 주고 풀도 비어 있는 조합이다.
            assertThat(curationSlot(SlotFillOutcome.CURATOR)).isZero();
            assertThat(curationSlot(SlotFillOutcome.FALLBACK)).isZero();
        }

        private double curationSlot(SlotFillOutcome outcome) {
            return registry.get(AiCourseMetrics.CURATION_SLOT)
                .tag("result", outcome.name().toLowerCase())
                .counter().count();
        }

        @Test
        @DisplayName("스테이지 6종의 지연이 모두 기록된다")
        void recordsEveryStage() {
            givenPlannerAndCurator(plan(1, SlotType.ATTRACTION));
            givenGrounded(List.of(groundedDay(1,
                place("천마총", SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON))));
            givenEnricherIsIdentity();

            pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS));

            for (PipelineStage stage : PipelineStage.values()) {
                assertThat(registry.get(AiCourseMetrics.PIPELINE_DURATION)
                    .tag("stage", stage.name().toLowerCase())
                    .timer().count())
                    .as("%s 단계가 기록되지 않았다", stage)
                    .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("실패한 스테이지의 지연도 기록된다 — 느려서 죽은 구간이 사라지면 안 된다")
        void recordsFailedStagesToo() {
            givenPlannerAndCurator(plan(1, SlotType.ATTRACTION));
            givenGrounded(List.of(emptyGroundedDay(1, SlotType.ATTRACTION)));

            assertThatThrownBy(() -> pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS)))
                .isInstanceOf(BusinessException.class);

            assertThat(registry.get(AiCourseMetrics.PIPELINE_DURATION)
                .tag("stage", "grounding").timer().count()).isEqualTo(1);
            // 도달하지 못한 단계는 0으로 남는다.
            assertThat(registry.get(AiCourseMetrics.PIPELINE_DURATION)
                .tag("stage", "url_enrich").timer().count()).isZero();
        }

        @Test
        @DisplayName("채택 집계는 출처와 modifier 축으로 갈린다 — 8-7 삭제 로그의 분모다")
        void recordsAdoptedBySource() {
            givenPlannerAndCurator(plan(1, SlotType.ATTRACTION, SlotType.MEAL));
            GroundedPlace fromModifier = new GroundedPlace("숨은 카페", SlotType.MEAL,
                CHEOMSEONGDAE_LAT, CHEOMSEONGDAE_LON, "경북 경주시", null,
                CandidateSourceType.LISTED, StyleTag.QUIET);
            givenGrounded(List.of(new GroundedDay(1, List.of(
                new GroundedSlot(SlotType.ATTRACTION, List.of(place("천마총", SlotType.ATTRACTION,
                    CHEONMACHONG_LAT, CHEONMACHONG_LON))),
                new GroundedSlot(SlotType.MEAL, List.of(fromModifier))))));
            givenEnricherIsIdentity();

            pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS));

            assertThat(registry.get(AiCourseMetrics.CANDIDATE_ADOPTED)
                .tag("source", "seeded").tag("modifier", "false").counter().count())
                .isEqualTo(1.0);
            assertThat(registry.get(AiCourseMetrics.CANDIDATE_ADOPTED)
                .tag("source", "listed").tag("modifier", "true").counter().count())
                .isEqualTo(1.0);
            assertThat(registry.get(AiCourseMetrics.CANDIDATE_ADOPTED)
                .tag("source", "suggested").tag("modifier", "false").counter().count())
                .isZero();
        }
    }

    @Nested
    @DisplayName("예산은 요청당 하나다")
    class Budget {

        @Test
        @DisplayName("모든 스테이지가 같은 CourseDeadline 인스턴스를 받는다")
        void sharesOneDeadline() {
            givenPlannerAndCurator(plan(1, SlotType.ATTRACTION));
            givenGrounded(List.of(groundedDay(1,
                place("천마총", SlotType.ATTRACTION, CHEONMACHONG_LAT, CHEONMACHONG_LON))));
            givenEnricherIsIdentity();

            pipeline().generate(CourseBrief.of(LOCATION, 1, KEYWORDS));

            ArgumentCaptor<CourseDeadline> planner =
                ArgumentCaptor.forClass(CourseDeadline.class);
            ArgumentCaptor<CourseDeadline> enricher =
                ArgumentCaptor.forClass(CourseDeadline.class);
            verify(plannerAgent).plan(anyString(), anyInt(), anyList(), planner.capture());
            verify(placeUrlEnricher).enrich(anyString(), anyList(), enricher.capture());

            assertThat(enricher.getValue()).isSameAs(planner.getValue());
        }
    }
}
