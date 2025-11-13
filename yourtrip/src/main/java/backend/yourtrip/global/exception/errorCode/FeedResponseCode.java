package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum FeedResponseCode {
    FEED_CREATED("피드 등록 완료"),
    FEED_UPDATED("피드 수정 완료"),
    FEED_DELETED("피드 삭제 완료");

    private final String message;
}
