package backend.yourtrip.global.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.LlmCall;
import backend.yourtrip.global.ai.LlmClient;
import backend.yourtrip.global.ai.agent.dto.PlannerResponse;
import backend.yourtrip.global.ai.exception.LlmTransportException;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.route.SlotType;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
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
 * {@code PlannerAgent} 단위 테스트 (ROADMAP 6-2).
 *
 * <p><b>API 키도 네트워크도 없이 돈다</b> — 2단계가 {@code LlmClient} 포트를 둔 유일한 남은 근거가
 * 정확히 이것이다. executor 는 {@code Runnable::run}으로 바꿔 결정론을 얻는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlannerAgent (ROADMAP 6-2)")
class PlannerAgentTest {

    private static final Executor DIRECT = Runnable::run;

    @Mock
    private LlmClient llmClient;

    private PlannerAgent agent;

    @BeforeEach
    void setUp() {
        agent = new PlannerAgent(llmClient, new PromptLoader(), DIRECT);
    }

    @Nested
    @DisplayName("호출 명세")
    class Call {

        @Test
        @DisplayName("설정 키와 스키마를 실어 보낸다 — 스키마는 프롬프트가 아니라 API 필드로 나간다")
        void carriesAgentNameAndSchema() {
            givenResponse(validResponse());

            agent.plan("경주", 1, List.of(KeywordType.HEALING), CourseDeadline.unbounded());

            LlmCall<?> call = captureCall();
            assertThat(call.agentName()).isEqualTo(PlannerAgent.AGENT_NAME);
            assertThat(call.responseType()).isEqualTo(PlannerResponse.class);
            assertThat(call.hasJsonSchema()).isTrue();
            assertThat(call.responseJsonSchema()).contains("anchor").contains("dayStartTime");
        }

        @Test
        @DisplayName("가변 데이터만 user 프롬프트에 들어간다")
        void putsVariablesInUserPrompt() {
            givenResponse(validResponse());

            agent.plan("경주", 3, List.of(KeywordType.WALK, KeywordType.COUPLE),
                CourseDeadline.unbounded());

            LlmCall<?> call = captureCall();
            assertThat(call.userPrompt())
                .contains("경주")
                .contains("3")
                .contains("뚜벅이")
                .contains("연인");
            assertThat(call.systemInstruction()).contains("anchor").doesNotContain("경주 3일");
        }

        @Test
        @DisplayName("duration 키워드는 프롬프트에 실리지 않는다")
        void omitsDurationKeyword() {
            givenResponse(validResponse());

            agent.plan("경주", 3, List.of(KeywordType.TWO_DAYS, KeywordType.HEALING),
                CourseDeadline.unbounded());

            assertThat(captureCall().userPrompt()).doesNotContain("1박 2일").contains("힐링");
        }
    }

    @Nested
    @DisplayName("응답 처리")
    class Response {

        @Test
        @DisplayName("보정을 거친 PlannerPlan 을 돌려준다")
        void normalizesResponse() {
            givenResponse(new PlannerResponse("천년의 밤", "느긋한 경주", List.of(
                new PlannerResponse.Day(9, "황리단길", "대릉원", "한옥 골목", "10:00",
                    List.of("ATTRACTION", "MEAL", "CAFE", "VIEWPOINT", "MEAL")))));

            PlannerPlan plan = agent.plan("경주", 1, List.of(), CourseDeadline.unbounded());

            assertThat(plan.title()).isEqualTo("천년의 밤");
            assertThat(plan.days()).hasSize(1);
            assertThat(plan.days().getFirst().day()).isEqualTo(1);
            assertThat(plan.days().getFirst().dayStartTime()).isEqualTo(LocalTime.of(10, 0));
            assertThat(plan.days().getFirst().slots())
                .containsExactly(SlotType.ATTRACTION, SlotType.MEAL, SlotType.CAFE,
                    SlotType.VIEWPOINT, SlotType.MEAL);
        }

        @Test
        @DisplayName("day 가 모자라면 기본 플랜으로 채워 요청한 일수를 맞춘다")
        void fillsMissingDays() {
            givenResponse(validResponse());

            PlannerPlan plan = agent.plan("경주", 3, List.of(), CourseDeadline.unbounded());

            assertThat(plan.days()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("실패와 예산")
    class Failures {

        @Test
        @DisplayName("예산이 이미 소진됐으면 호출하지 않는다")
        void skipsCallWhenBudgetSpent() {
            CourseDeadline expired = CourseDeadline.startingNow(Duration.ZERO);

            assertThatThrownBy(() -> agent.plan("경주", 1, List.of(), expired))
                .isInstanceOf(LlmTransportException.class);
            then(llmClient).should(never()).generateAsync(any(), any());
        }

        @Test
        @DisplayName("LLM 실패를 삼키지 않는다 — 기본 플랜으로 degrade 할지는 파이프라인이 정한다")
        void propagatesLlmFailure() {
            given(llmClient.generateAsync(any(), any())).willReturn(
                CompletableFuture.failedFuture(new LlmTransportException(
                    PlannerAgent.AGENT_NAME, 3, "429", null)));

            assertThatThrownBy(
                () -> agent.plan("경주", 1, List.of(), CourseDeadline.unbounded()))
                .isInstanceOf(LlmTransportException.class)
                .hasMessage("429");
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void givenResponse(PlannerResponse response) {
        given(llmClient.generateAsync(any(), any()))
            .willReturn((CompletableFuture) CompletableFuture.completedFuture(response));
    }

    private LlmCall<?> captureCall() {
        ArgumentCaptor<LlmCall<?>> captor = ArgumentCaptor.captor();
        then(llmClient).should().generateAsync(captor.capture(), any());
        return captor.getValue();
    }

    private static PlannerResponse validResponse() {
        return new PlannerResponse("제목", "컨셉", List.of(
            new PlannerResponse.Day(1, "황리단길", "대릉원", "산책", "10:00",
                List.of("ATTRACTION", "MEAL", "CAFE"))));
    }
}
