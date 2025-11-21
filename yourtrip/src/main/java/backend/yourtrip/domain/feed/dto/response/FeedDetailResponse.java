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
        String content,
        List<String> hashtags,
        int commentCount,
        int heartCount,
        int viewCount,
        Long uploadCourseId,
        List<MediaResponse> mediaList
) {
    @Builder
    public record MediaResponse(
            Long mediaId,
            String mediaUrl,
            String mediaType,
            int displayOrder
    ) {}
}
