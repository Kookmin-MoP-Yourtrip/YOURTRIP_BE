package backend.yourtrip.global.ai.exception;

import lombok.Getter;

/**
 * LLM 호출 실패의 최상위 타입.
 *
 * <p><b>이 계층은 재시도 2계층 구조를 타입으로 표현한다</b>(설계 문서 §6).
 * <pre>
 * LlmException
 * ├── LlmTransportException      전송 계층 — 429/5xx/타임아웃. 지수 백오프 대상
 * └── LlmResponseException       의미 계층 — 200 OK인데 쓸 수 없는 응답. 1회 재시도 대상
 *     ├── LlmTruncatedResponseException   finish_reason != stop
 *     └── LlmParseException               스키마 위반 / 역직렬화 실패
 * </pre>
 *
 * <p>재시도는 전부 어댑터 내부에서 끝난다 — 이 예외가 호출자에게 도달했다면 재시도가 이미
 * 소진된 상태다.
 *
 * <p><b>{@code BusinessException}을 상속하지 않는다.</b> 이 저장소의 관례는 외부 호출 실패를
 * {@code BusinessException(ErrorCode)}로 변환하는 것이지만(예: {@code KakaoLocalClient}가
 * {@code KAKAO_API_FAILED}로 변환), LLM 실패는 <b>어떤 HTTP 응답이 될지가 어댑터가 아니라
 * 파이프라인의 폴백 전략에 달려 있다</b> — 에이전트 하나가 실패해도 degrade해서 코스를 만들 수
 * 있으면 200이고, 카카오까지 죽었을 때만 503이다(설계 문서 §9). 그래서 어댑터는 실패 <b>사실</b>만
 * 전달하고, {@code ErrorCode} 매핑은 7단계 {@code AiCourseErrorCode}에서 파이프라인이 결정한다.
 */
@Getter
public abstract class LlmException extends RuntimeException {

    /** 어느 에이전트의 호출이 실패했는지. 로그·메트릭 태그로 쓴다. */
    private final String agentName;

    protected LlmException(String agentName, String message) {
        super(message);
        this.agentName = agentName;
    }

    protected LlmException(String agentName, String message, Throwable cause) {
        super(message, cause);
        this.agentName = agentName;
    }
}
