package backend.yourtrip.global.ai.prompt;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 에이전트 응답 JSON 스키마 파일의 목록 (ROADMAP 6-1).
 *
 * <p><b>{@link PromptTemplate}과 나눠 둔 이유는 렌더링 여부가 다르기 때문이다.</b> 스키마에는
 * 플레이스홀더가 없고 그대로 {@code LlmCall.responseJsonSchema}에 실린다. 한 enum에 뭉치면
 * {@code render(스키마, ...)} 같은 호출이 타입 검사를 통과하는데, 그건 아무 오류 없이 통과하고
 * 아무 일도 하지 않는 호출이라 더 나쁘다.
 *
 * <p><b>스키마를 자바가 아니라 파일로 두는 것이 가능한 이유</b>는 2단계가 포트를 그렇게 설계했기
 * 때문이다 — {@code LlmCall.responseJsonSchema}는 벤더 타입이 아니라 <b>JSON 문자열</b>을 받는다.
 * 그 결정의 1차 목적은 벤더 중립이었지만, 스키마를 리소스로 뺄 수 있다는 것이 함께 따라왔다.
 *
 * <p><b>루트는 반드시 {@code type: "object"}여야 한다.</b> 0-3b 실 API 검증에서 최상위 배열
 * 스키마는 400으로 거부됐다({@code schema must be a JSON Schema of 'type: "object"'}). Curator의
 * 슬롯 배열을 객체로 감싸는 것은 취향이 아니라 제약이다.
 */
@Getter
@RequiredArgsConstructor
public enum ResponseSchema {

    /** Planner 응답 — 제목·컨셉·day별 권역과 슬롯 구성. */
    PLANNER("prompts/planner-response.schema.json"),

    /** Curator 응답 — day 하나의 슬롯별 선택 3개. */
    CURATOR("prompts/curator-response.schema.json");

    private final String path;
}
