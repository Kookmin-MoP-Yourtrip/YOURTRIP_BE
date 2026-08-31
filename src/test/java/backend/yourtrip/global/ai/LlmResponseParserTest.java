package backend.yourtrip.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import backend.yourtrip.global.ai.exception.LlmParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code LlmResponseParser} 단위 테스트 (ROADMAP 2-4).
 *
 * <p><b>이 테스트가 존재할 수 있다는 것 자체가 포트 도입의 성과다.</b> 지금까지 LLM 응답 파싱은
 * {@code MyCourseServiceImpl.createAICourse} 안에 인라인으로 있었고, 그 메서드를 부르려면
 * {@code GeminiService} → {@code com.google.genai.Client}(public final, 목킹 불가)가 딸려 왔다.
 */
@DisplayName("LlmResponseParser (ROADMAP 2-4)")
class LlmResponseParserTest {

    private static final String AGENT = "planner";

    private final LlmResponseParser parser =
        new LlmResponseParser(new ObjectMapper().registerModule(new JavaTimeModule()));

    /** 파이프라인 응답과 같은 모양의 최소 타입. 루트가 객체인 것은 OpenAI strict 모드의 제약이다. */
    record DayPlan(String title, List<Slot> slots) {
        record Slot(String placeName, LocalTime startTime) {}
    }

    @Test
    @DisplayName("정상 JSON을 도메인 타입으로 역직렬화한다")
    void parsesValidJson() {
        String raw = """
            {"title":"경주, 천년의 밤을 걷다",
             "slots":[{"placeName":"동궁과 월지","startTime":"19:30"}]}
            """;

        DayPlan parsed = parser.parse(AGENT, raw, DayPlan.class);

        assertThat(parsed.title()).isEqualTo("경주, 천년의 밤을 걷다");
        assertThat(parsed.slots()).singleElement()
            .satisfies(slot -> assertThat(slot.startTime()).isEqualTo(LocalTime.of(19, 30)));
    }

    @Test
    @DisplayName("절단된 JSON은 원문을 예외에 실어 보낸다 — 원인 규명이 로그만으로 끝나게")
    void carriesRawTextOnFailure() {
        // Gemini baseline 실패 5건이 전부 이 모양이었다: 키 이름 중간에서 끊김.
        String truncated = "{\"title\":\"경주 야경\",\"slots\":[{\"placeNa";

        assertThatThrownBy(() -> parser.parse(AGENT, truncated, DayPlan.class))
            .isInstanceOf(LlmParseException.class)
            .satisfies(thrown -> {
                LlmParseException e = (LlmParseException) thrown;
                assertThat(e.getRawText()).isEqualTo(truncated);
                assertThat(e.getAgentName()).isEqualTo(AGENT);
            });
    }

    @Test
    @DisplayName("빈 응답도 파싱 실패로 다룬다 — null 을 위로 흘려보내지 않는다")
    void rejectsBlankResponse() {
        assertThatThrownBy(() -> parser.parse(AGENT, "   ", DayPlan.class))
            .isInstanceOf(LlmParseException.class);
        assertThatThrownBy(() -> parser.parse(AGENT, null, DayPlan.class))
            .isInstanceOf(LlmParseException.class);
    }
}
