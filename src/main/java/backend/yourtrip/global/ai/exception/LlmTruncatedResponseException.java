package backend.yourtrip.global.ai.exception;

import lombok.Getter;

/**
 * 응답이 끝까지 오지 않았다 — {@code finish_reason}이 {@code stop}이 아닌 경우.
 *
 * <p><b>이 예외 타입이 존재하는 이유는 실측 때문이다.</b> Gemini 단일 호출 baseline에서 JSON 파싱
 * 실패 5건이 나왔는데, 원인을 캐보니 <b>전부 {@code Unexpected end-of-input}</b>(응답 절단)이었다.
 * trailing comma 같은 문법 오류는 하나도 없었고, 원본이 남은 1건은 386바이트에서 키 이름 중간에
 * 잘려 있었다(정상 응답은 1,400~1,660바이트). 근거는
 * {@code docs/tasks/ai-course-create/BASELINE-ARTIFACT-ANALYSIS.md} 판정 3.
 *
 * <p>여기서 나오는 결론이 설계에 직접 영향을 준다 — <b>{@code response_format: json_schema}는
 * 생성되는 토큰의 문법을 강제할 뿐, 응답이 중간에 끊기는 것을 막지 못한다.</b> 그래서 "구조화
 * 출력을 켜면 파싱 실패가 near-zero가 된다"는 LLM 포트 설계의 전제는 절단에 대해서는 성립하지
 * 않고, 절단을 <b>파싱 실패와 구분해 별도로 관측</b>해야 원인을 계속 추적할 수 있다.
 *
 * <p>파싱을 시도하기 <b>전에</b> 던진다. 잘린 JSON을 파싱하면 {@link LlmParseException}이 나와
 * 원인이 "스키마 위반"으로 오분류되기 때문이다.
 */
@Getter
public class LlmTruncatedResponseException extends LlmResponseException {

    /** 벤더가 준 종료 사유 원문. OpenAI 기준 {@code length} / {@code content_filter} 등. */
    private final String finishReason;

    /**
     * 끊기기 전까지 받은 본문.
     *
     * <p>버리지 않고 들고 있는 이유는 <b>절단의 원인이 두 가지로 갈리기 때문</b>이다 — 출력 상한에
     * 닿아서 잘린 것과 스트림이 중간에 끊긴 것은 대응이 다르다(전자는 {@code max-output-tokens}를
     * 올리면 되고, 후자는 그렇지 않다). 실제로 Gemini baseline의 절단 1건은 정상 응답이
     * 1,400~1,660바이트인데 <b>386바이트</b>에서 끊겨 있어 상한이 원인이 아님을 알 수 있었다
     * ({@code BASELINE-ARTIFACT-ANALYSIS.md} 판정 3). 길이를 재려면 원문이 있어야 한다.
     */
    private final String partialText;

    public LlmTruncatedResponseException(String agentName, String finishReason, String partialText) {
        super(agentName, "LLM 응답이 정상 종료되지 않았다 (finishReason=%s, %d바이트 수신)"
            .formatted(finishReason, partialText == null ? 0 : partialText.length()));
        this.finishReason = finishReason;
        this.partialText = partialText;
    }
}
