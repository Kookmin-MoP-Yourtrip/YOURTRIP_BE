package backend.yourtrip.domain.feed.dto.response;

import java.util.List;

public record FeedListResponse(
        List<FeedDetailResponse> feeds,
        int currentPage,
        int totalPages,
        long totalElements,
        boolean hasNext,
        boolean hasPrevious
) {
}
