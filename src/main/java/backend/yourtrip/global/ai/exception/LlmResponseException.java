package backend.yourtrip.global.ai.exception;

/**
 * 의미 계층 실패 — HTTP는 200인데 응답을 쓸 수 없는 경우.
 *
 * <p>어댑터가 <b>1회만</b> 재시도한다(temperature를 낮추고 보정 지시를 덧붙여서). 2회 이상은
 * 지연 예산만 태운다는 것이 설계 문서 §6의 판단이다.
 *
 * <p>이 타입이 {@link LlmTransportException}과 갈라져 있어야 하는 이유는 <b>대응이 정반대</b>이기
 * 때문이다 — 전송 실패는 "그대로 다시 보내면 될 수도 있다"이고, 의미 실패는 "그대로 다시 보내면
 * 같은 결과가 나온다"이다.
 */
public abstract class LlmResponseException extends LlmException {

    protected LlmResponseException(String agentName, String message) {
        super(agentName, message);
    }

    protected LlmResponseException(String agentName, String message, Throwable cause) {
        super(agentName, message, cause);
    }
}
