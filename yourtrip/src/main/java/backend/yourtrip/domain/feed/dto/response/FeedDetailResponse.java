package backend.yourtrip.domain.feed.dto.response;

import lombok.Builder;

import java.util.List;

@Builder
public record FeedDetailResponse(
        Long feedId,
        Long userId,
        String nickname,
        String profileImageUrl,
        String title,
        String location,
        String contentUrl,
        List<String> hashtags,
        int commentCount,
        int heartCount,
        Long uploadCourseId
) {
}
