package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum FeedCommentErrorCode implements ErrorCode{

    COMMENT_NOT_FOUND("댓글을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    COMMENT_SENTENCE_REQUIRED("댓글 입력은 필수입니다.", HttpStatus.BAD_REQUEST),
    COMMENT_UPDATE_NOT_AUTHORIZED("댓글 수정 권한이 없습니다.", HttpStatus.FORBIDDEN),
    COMMENT_DELETE_NOT_AUTHORIZED("댓글 삭제 권한이 없습니다.", HttpStatus.FORBIDDEN),
    COMMENT_ALREADY_DELETED("이미 삭제된 댓글입니다.", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;
}
