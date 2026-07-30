package backend.yourtrip.domain.feed.dto.response;

import lombok.Builder;

@Builder
public record FeedLikeResponse(
        Long feedId,
        boolean isLiked,
        int heartCount
) {
}
