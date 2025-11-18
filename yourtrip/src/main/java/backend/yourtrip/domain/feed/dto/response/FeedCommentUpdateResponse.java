package backend.yourtrip.domain.feed.dto.response;

public record FeedCommentUpdateResponse(
        Long commentFeedId,
        String message
) {
}
