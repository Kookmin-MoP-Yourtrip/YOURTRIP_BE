package backend.yourtrip.global.ai.exception;

import lombok.Getter;

/**
 * 전송 계층 실패 — 429(rate limit), 5xx, 타임아웃, 연결 실패.
 *
 * <p>어댑터가 {@code llm.retry} 설정대로 지수 백오프 + 지터로 재시도한 <b>뒤에도</b> 실패했을 때
 * 던진다. 즉 이 예외를 받은 호출자가 곧바로 다시 부르는 것은 의미가 없다.
 *
 * <p>{@code attempts}를 들고 있는 이유는 <b>{@code llm.max-concurrent-calls} 초기값의 근거를
 * 실측으로 확보하기 위해서</b>다. 이 저장소는 OpenAI의 RPM/TPM 티어를 아직 모르는 상태로
 * 세마포어를 2로 두고 시작하는데, 429 재시도가 몇 번 만에 성공했는지가 그 값이 적절한지를
 * 판단할 유일한 신호다.
 */
@Getter
public class LlmTransportException extends LlmException {

    /** 실제로 시도한 횟수(초회 포함). */
    private final int attempts;

    public LlmTransportException(String agentName, int attempts, String message, Throwable cause) {
        super(agentName, message, cause);
        this.attempts = attempts;
    }
}
