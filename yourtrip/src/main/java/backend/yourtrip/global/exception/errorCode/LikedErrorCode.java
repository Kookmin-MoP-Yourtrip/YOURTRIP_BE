package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum LikedErrorCode implements ErrorCode {

    // 좋아요 조회
    COURSE_LIKE_NOT_FOUND("좋아요한 코스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    FEED_LIKE_NOT_FOUND("좋아요한 피드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),

    // 코스 & Fork
    COURSE_NOT_FOUND("업로드된 코스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    FORK_FAILED("코스 가져오기에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String message;
    private final HttpStatus status;
}