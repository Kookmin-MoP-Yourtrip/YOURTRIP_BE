package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum FeedCommentResponseCode {
    FEED_COMMENT_CREATED("댓글 등록 완료"),
    FEED_COMMENT_UPDATED("댓글 수정 완료"),
    FEED_COMMENT_DELETED("댓글 삭제 완료");

    private final String message;
}
