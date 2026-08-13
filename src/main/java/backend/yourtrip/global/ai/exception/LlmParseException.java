package backend.yourtrip.global.ai.exception;

import lombok.Getter;

/**
 * 응답은 끝까지 왔는데 역직렬화에 실패했다 — 스키마 위반 또는 JSON 문법 오류.
 *
 * <p>{@code rawText}를 들고 있는 이유는 <b>이 실패의 원인 규명이 반복적으로 막혔기 때문</b>이다.
 * Gemini baseline 측정에서 파싱 실패율 수치만 남고 원본 응답이 남지 않아, 원인이 trailing
 * comma인지 절단인지를 몇 달 뒤에야 다른 워크트리에 남아 있던 산출물로 확인할 수 있었다
 * ({@code docs/tasks/ai-course-create/BASELINE-ARTIFACT-ANALYSIS.md} 판정 3). 예외에 원문을
 * 실어두면 로그만으로 판정이 끝난다.
 */
@Getter
public class LlmParseException extends LlmResponseException {

    /** 역직렬화에 실패한 응답 원문. */
    private final String rawText;

    public LlmParseException(String agentName, String rawText, Throwable cause) {
        super(agentName, "LLM 응답을 역직렬화하지 못했다: " + cause.getMessage(), cause);
        this.rawText = rawText;
    }
}
