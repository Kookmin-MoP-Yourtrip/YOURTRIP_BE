package backend.yourtrip.domain.feed.dto.response;

public record FeedCommentCreateResponse(
        Long feedCommentId,
        String message
) {
}
