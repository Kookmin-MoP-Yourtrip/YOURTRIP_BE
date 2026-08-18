package backend.yourtrip.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import backend.yourtrip.global.ai.exception.LlmTransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 포트가 실제로 목킹 가능한지 확인한다 (ROADMAP 2-5).
 *
 * <p><b>이 테스트가 초록불이라는 사실 자체가 2단계의 성과다.</b> LLM 포트 설계는 포트를 두는
 * 근거를 "테스트 가능성" 하나로 압축했다 — {@code com.google.genai.Client}는
 * {@code public final class}에 {@code models}도 {@code public final} 필드라 Mockito로 묶을 수
 * 없고, 그래서 지금까지 LLM 호출부의 단위 테스트가 원천적으로 불가능했다. 환각률 측정 하네스가
 * Spring 컨텍스트 없이 {@code new GeminiService(...)}로 조립해 <b>실제 API를 때리는</b> 방식을
 * 택한 것도 같은 제약 때문이다.
 *
 * <p>아래 {@code FakeAgent}는 6단계에서 만들 {@code PlannerAgent}·{@code CuratorAgent}의 대역이다.
 * 이런 소비자가 <b>API 키도 네트워크도 없이</b> 테스트되는 것이 포트가 사주는 대가다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("포트 목킹 가능성 (ROADMAP 2-5)")
class LlmClientMockingDemoTest {

    record Plan(String title) {}

    /**
     * 6단계 에이전트의 대역. <b>벤더 SDK 타입을 하나도 import하지 않는다</b>는 것이 요점이며,
     * 실제 에이전트도 정확히 이 모양이 된다.
     */
    static class FakeAgent {

        private static final String AGENT_NAME = "planner";
        private static final String SCHEMA = "{\"type\":\"object\"}";

        private final LlmClient llmClient;

        FakeAgent(LlmClient llmClient) {
            this.llmClient = llmClient;
        }

        Plan plan(String location, int days) {
            return llmClient.generate(new LlmCall<>(
                AGENT_NAME,
                "너는 한국 여행 코스 플래너다",
                "%s %d일 코스의 컨셉과 제목을 정해줘".formatted(location, days),
                Plan.class,
                SCHEMA));
        }
    }

    @Mock
    private LlmClient llmClient;

    @InjectMocks
    private FakeAgent agent;

    @Test
    @DisplayName("LlmClient 를 목킹해 소비자를 네트워크 없이 검증한다")
    void consumerIsTestableWithoutNetwork() {
        // given
        given(llmClient.generate(this.<Plan>anyCall())).willReturn(new Plan("경주, 천년의 밤을 걷다"));

        // when
        Plan plan = agent.plan("경주", 3);

        // then
        assertThat(plan.title()).isEqualTo("경주, 천년의 밤을 걷다");
    }

    @Test
    @DisplayName("소비자가 조립한 LlmCall 의 내용을 그대로 들여다볼 수 있다")
    void callContentIsInspectable() {
        given(llmClient.generate(this.<Plan>anyCall())).willReturn(new Plan("t"));

        agent.plan("순천", 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LlmCall<Plan>> captor = ArgumentCaptor.forClass(LlmCall.class);
        then(llmClient).should().generate(captor.capture());

        LlmCall<Plan> sent = captor.getValue();
        assertThat(sent.agentName()).isEqualTo("planner");
        assertThat(sent.userPrompt()).contains("순천", "2일");
        assertThat(sent.hasJsonSchema()).isTrue();
    }

    @Test
    @DisplayName("LLM 장애도 목으로 재현된다 — 폴백 전략을 실제 장애 없이 테스트할 수 있다")
    void failureIsReproducible() {
        // 7단계의 "degrade, don't fail" 폴백은 이런 방식으로만 검증할 수 있다.
        willThrow(new LlmTransportException("planner", 3, "429 소진", null))
            .given(llmClient).generate(this.<Plan>anyCall());

        assertThatThrownBy(() -> agent.plan("부산", 3))
            .isInstanceOf(LlmTransportException.class);
    }

    /** {@code LlmCall<T>}가 제네릭이라 매처에 타입을 붙여야 컴파일된다. */
    private <T> LlmCall<T> anyCall() {
        return any();
    }
}
