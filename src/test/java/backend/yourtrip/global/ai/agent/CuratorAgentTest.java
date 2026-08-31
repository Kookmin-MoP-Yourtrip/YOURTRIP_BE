package backend.yourtrip.global.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.LlmCall;
import backend.yourtrip.global.ai.LlmClient;
import backend.yourtrip.global.ai.agent.dto.CuratorResponse;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.exception.LlmTransportException;
import backend.yourtrip.global.ai.pipeline.CuratedDay;
import backend.yourtrip.global.ai.pipeline.CuratedPlace;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.route.SlotType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code CuratorAgent} 단위 테스트 (ROADMAP 6-4).
 *
 * <p>executor 를 {@code Runnable::run} 으로 바꿔 결정론을 얻는다 — 5단계 스테이지 테스트가 세운
 * 방식과 같고, 그래서 "병렬로 도는가" 가 아니라 "day 별로 몇 번 부르고 실패를 어떻게 흡수하는가" 를
 * 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CuratorAgent (ROADMAP 6-4)")
class CuratorAgentTest {

    private static final Executor DIRECT = Runnable::run;
    private static final double LAT = 35.8386877792;
    private static final double LON = 129.2104983997;

    @Mock
    private LlmClient llmClient;

    private SimpleMeterRegistry registry;
    private CuratorAgent agent;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        agent = new CuratorAgent(llmClient, new PromptLoader(), new AiCourseMetrics(registry),
            DIRECT);
    }

    @Nested
    @DisplayName("day별 병렬")
    class PerDay {

        @Test
        @DisplayName("day 수만큼 호출하고 순서를 그대로 유지한다")
        void callsOncePerDay() {
            given(llmClient.generateAsync(any(), any()))
                .willReturn(completed(response(1)), completed(response(2)));

            List<CuratedDay> days = agent.curate(plan(2), pool(), List.of(),
                CourseDeadline.unbounded());

            then(llmClient).should(org.mockito.Mockito.times(2)).generateAsync(any(), any());
            assertThat(days).extracting(CuratedDay::day).containsExactly(1, 2);
        }

        @Test
        @DisplayName("day 하나가 실패해도 그 day 만 비고 나머지는 산다")
        void oneFailingDayDoesNotKillTheRest() {
            given(llmClient.generateAsync(any(), any())).willReturn(
                CompletableFuture.failedFuture(
                    new LlmTransportException(CuratorAgent.AGENT_NAME, 3, "429", null)),
                completed(response(2)));

            List<CuratedDay> days = agent.curate(plan(2), pool(), List.of(),
                CourseDeadline.unbounded());

            assertThat(days).hasSize(2);
            assertThat(days.getFirst().slots()).allSatisfy(
                slot -> assertThat(slot.choices()).isEmpty());
            assertThat(choiceNamesOf(days.get(1))).containsExactly("대릉원");
        }
    }

    @Nested
    @DisplayName("프롬프트 입력")
    class Prompt {

        @Test
        @DisplayName("권역·테마·자리 목록·후보 목록이 user 프롬프트에 들어간다")
        void carriesDayContext() {
            given(llmClient.generateAsync(any(), any())).willReturn(completed(response(1)));

            agent.curate(plan(1), pool(), List.of(KeywordType.HEALING),
                CourseDeadline.unbounded());

            LlmCall<?> call = captureCall();
            assertThat(call.agentName()).isEqualTo(CuratorAgent.AGENT_NAME);
            assertThat(call.userPrompt())
                .contains("황리단길 일대")
                .contains("한옥 골목")
                .contains("0. ATTRACTION")
                .contains("0. [seed 1위] 대릉원")
                .contains("힐링");
            assertThat(call.responseJsonSchema()).contains("listIndex");
        }
    }

    @Nested
    @DisplayName("강등과 메트릭")
    class Demotion {

        @Test
        @DisplayName("위조된 목록 참조를 강등하고 사유별로 센다")
        void recordsDemotions() {
            given(llmClient.generateAsync(any(), any())).willReturn(completed(
                new CuratorResponse(1, List.of(new CuratorResponse.Slot(0, "ATTRACTION",
                    List.of(new CuratorResponse.Choice("SEEDED", 9, "천마총")))))));

            List<CuratedDay> days = agent.curate(plan(1), pool(), List.of(),
                CourseDeadline.unbounded());

            assertThat(days.getFirst().slots().getFirst().choices())
                .singleElement()
                .extracting(CuratedPlace::source)
                .isEqualTo(CandidateSourceType.SUGGESTED);
            assertThat(registry.get(AiCourseMetrics.CANDIDATE_DEMOTED)
                .tag("reason", "index_out_of_range").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("강등이 없으면 시계열은 0으로 남는다 — 없는 것과 0은 다르다")
        void keepsZeroSeries() {
            given(llmClient.generateAsync(any(), any())).willReturn(completed(response(1)));

            agent.curate(plan(1), pool(), List.of(), CourseDeadline.unbounded());

            assertThat(registry.get(AiCourseMetrics.CANDIDATE_DEMOTED)
                .tag("reason", "name_mismatch").counter().count()).isZero();
        }
    }

    @Nested
    @DisplayName("예산과 빈 입력")
    class Budget {

        @Test
        @DisplayName("예산이 소진됐으면 호출하지 않고 빈 자리로 넘긴다 — 예외를 올리지 않는다")
        void degradesWhenBudgetSpent() {
            List<CuratedDay> days = agent.curate(plan(2), pool(), List.of(),
                CourseDeadline.startingNow(Duration.ZERO));

            then(llmClient).should(never()).generateAsync(any(), any());
            assertThat(days).hasSize(2);
            assertThat(days).allSatisfy(day -> assertThat(day.slots())
                .allSatisfy(slot -> assertThat(slot.choices()).isEmpty()));
        }

        @Test
        @DisplayName("day 가 없으면 호출하지 않는다")
        void skipsEmptyPlan() {
            List<CuratedDay> days = agent.curate(plan(0), pool(), List.of(),
                CourseDeadline.unbounded());

            then(llmClient).should(never()).generateAsync(any(), any());
            assertThat(days).isEmpty();
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CompletableFuture<Object> completed(CuratorResponse response) {
        return (CompletableFuture) CompletableFuture.completedFuture(response);
    }

    private LlmCall<?> captureCall() {
        ArgumentCaptor<LlmCall<?>> captor = ArgumentCaptor.captor();
        then(llmClient).should().generateAsync(captor.capture(), any());
        return captor.getValue();
    }

    private static List<String> choiceNamesOf(CuratedDay day) {
        List<String> names = new ArrayList<>();
        day.slots().forEach(slot -> slot.choices().forEach(
            choice -> names.add(choice.placeName())));
        return names;
    }

    private static PlannerPlan plan(int days) {
        List<PlannerDayPlan> dayPlans = new ArrayList<>(days);
        for (int day = 1; day <= days; day++) {
            dayPlans.add(new PlannerDayPlan(day, "황리단길 일대", "대릉원", "한옥 골목", null,
                List.of(SlotType.ATTRACTION)));
        }
        return new PlannerPlan("천년의 밤", "느긋한 경주", dayPlans);
    }

    /** day 1·2 모두 같은 후보 목록을 갖는다 — 어느 day 가 살아남았는지 이름으로 구분한다. */
    private static CandidatePool pool() {
        List<CandidateSlot> slots = new ArrayList<>();
        for (int day = 1; day <= 2; day++) {
            slots.add(new CandidateSlot(day, SlotType.ATTRACTION, List.of(
                new PlaceCandidate(CandidateSourceType.SEEDED, "대릉원", "경주시 황남동", LAT, LON,
                    SlotType.ATTRACTION, Set.of(), 1, null, 0.4, "A02"))));
        }
        return new CandidatePool(slots);
    }

    private static CuratorResponse response(int day) {
        return new CuratorResponse(day, List.of(new CuratorResponse.Slot(0, "ATTRACTION",
            List.of(new CuratorResponse.Choice("SEEDED", 0, "대릉원")))));
    }
}
