package backend.yourtrip.domain.feed.dto.response;

import java.util.List;

/**
 * 피드 댓글 목록 응답 DTO
 */
public record FeedCommentListResponse(
    List<FeedCommentResponse> comments,
    int currentPage,
    int totalPages,
    long totalElements,
    boolean hasNext,
    boolean hasPrevious
) {
}