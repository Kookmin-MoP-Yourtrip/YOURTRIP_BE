package backend.yourtrip.global.ai;

import java.util.Objects;

/**
 * LLM 호출 1건의 요청 명세.
 *
 * <p>설계 근거: {@code docs/tasks/ai-course-create/TASK-AI-MULTI-AGENT.md} §6
 *
 * @param agentName          설정 조회 키이자 메트릭 태그. {@code AiLlmProperties.agents}의 키와
 *                           일치해야 하며(예: {@code planner}, {@code place-profile}), 여기서
 *                           model·temperature·max-output-tokens를 찾는다
 * @param systemInstruction  고정 규칙. 가변 데이터와 분리해두면 향후 컨텍스트 캐싱 대상이
 *                           자명해진다. null이면 system 메시지를 보내지 않는다
 * @param userPrompt         가변 데이터만
 * @param responseType       응답을 역직렬화할 타입
 * @param responseJsonSchema <b>벤더 타입이 아니라 JSON 문자열</b>. 이것이 벤더 중립의 핵심이다 —
 *                           벤더마다 이걸 받는 창구가 다르고(OpenAI는
 *                           {@code response_format: json_schema}, Gemini는
 *                           {@code GenerateContentConfig.responseJsonSchema}), 어댑터가 문자열을
 *                           자기 벤더의 타입으로 옮기면 포트는 그 차이를 모른다.
 *                           <p><b>null이면 스키마 없는 JSON 모드로 호출한다.</b> 스키마 강제를
 *                           포트의 요구사항으로 못박으면 오히려 중립성이 좁아지고(모든 벤더가
 *                           strict schema를 지원하지는 않는다), 2-6 baseline 재측정이 스키마
 *                           없는 측정점을 필요로 한다
 */
public record LlmCall<T>(
    String agentName,
    String systemInstruction,
    String userPrompt,
    Class<T> responseType,
    String responseJsonSchema
) {

    /**
     * <b>응답 스키마의 루트는 반드시 객체여야 한다.</b> 0단계 실 API 검증에서 루트가
     * {@code type: "array"}인 스키마는 400으로 거부되는 것을 확인했다
     * ({@code schema must be a JSON Schema of 'type: "object"'}). 여기서 강제하지는 않는다 —
     * 스키마 문자열을 파싱해 검사하는 비용보다, 6단계에서 스키마를 작성할 때 지키는 편이 낫다.
     */
    public LlmCall {
        Objects.requireNonNull(agentName, "agentName은 설정 조회 키라 null일 수 없다");
        Objects.requireNonNull(userPrompt, "userPrompt는 null일 수 없다");
        Objects.requireNonNull(responseType, "responseType은 null일 수 없다");
    }

    /** 스키마 없이 호출하는지 여부. 어댑터가 응답 형식을 고를 때 쓴다. */
    public boolean hasJsonSchema() {
        return responseJsonSchema != null && !responseJsonSchema.isBlank();
    }
}
