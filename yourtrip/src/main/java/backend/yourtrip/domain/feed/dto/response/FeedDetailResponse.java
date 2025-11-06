package backend.yourtrip.domain.feed.dto.response;

import java.util.List;

public record FeedDetailResponse(
        Long feedId,
        Long userId,
        String nickname,
        String profileImageUrl,
        String title,
        String location,
        String contentUrl,
        List<FeedHashtagListResponse> hashtags,
        int commentCount,
        int heartCount,
        Long uploadCourseId
) {
}
