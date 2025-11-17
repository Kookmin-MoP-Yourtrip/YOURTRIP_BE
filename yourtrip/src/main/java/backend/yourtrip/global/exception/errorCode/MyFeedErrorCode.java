package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum MyFeedErrorCode implements ErrorCode {

    MY_FEED_NOT_FOUND("내 피드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    MY_FEED_FORBIDDEN("다른 사용자의 피드에는 접근할 수 없습니다.", HttpStatus.FORBIDDEN);

    private final String message;
    private final HttpStatus status;
}