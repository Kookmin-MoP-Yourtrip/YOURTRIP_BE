package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum FeedErrorCode implements ErrorCode {

    FEED_NOT_FOUND("피드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    COMMENT_NOT_FOUND("댓글을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    INVALID_COMMENT_ACCESS("해당 피드의 댓글이 아닙니다.", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;
}