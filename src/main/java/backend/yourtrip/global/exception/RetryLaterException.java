package backend.yourtrip.global.exception;

import backend.yourtrip.global.exception.errorCode.ErrorCode;
import lombok.Getter;

/**
 * 재시도 간격을 함께 알려야 하는 실패 — 응답에 {@code Retry-After} 헤더가 붙는다 (#192).
 *
 * <p><b>{@link BusinessException}에 헤더 필드를 더하지 않고 하위 타입으로 둔 이유</b>는 헤더가 필요한
 * 실패가 이것 하나뿐이기 때문이다. 다른 도메인 예외는 지금처럼 상태·본문만 만들고, 이 타입만
 * {@link GlobalExceptionHandler}가 헤더를 얹는다. 본문 형식은 {@code BusinessException}과 같다.
 */
@Getter
public class RetryLaterException extends BusinessException {

    private final int retryAfterSeconds;

    public RetryLaterException(ErrorCode errorCode, int retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
