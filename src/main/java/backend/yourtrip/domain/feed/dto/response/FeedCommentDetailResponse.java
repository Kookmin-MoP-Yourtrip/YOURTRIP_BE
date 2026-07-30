package backend.yourtrip.domain.feed.dto.response;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record FeedCommentDetailResponse(
        Long feedCommentId,
        Long feedId,
        Long userId,
        String nickname,
        String profileImageUrl,
        String sentence,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}