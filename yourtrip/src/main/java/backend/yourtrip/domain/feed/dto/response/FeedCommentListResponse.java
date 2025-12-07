package backend.yourtrip.domain.feed.dto.response;


import lombok.Builder;

import java.util.List;

@Builder
public record FeedCommentListResponse(
        List<FeedCommentDetailResponse> comments,
        int totalPages,
        long totalElements,
        int currentPage,
        int pageSize
) {
}
