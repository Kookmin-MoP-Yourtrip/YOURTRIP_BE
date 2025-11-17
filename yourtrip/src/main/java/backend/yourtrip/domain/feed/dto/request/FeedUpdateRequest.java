package backend.yourtrip.domain.feed.dto.request;

import java.util.List;

public record FeedUpdateRequest(
        String title,
        String location,
        String content,
        List<String> hashtags,
        Long uploadCourseId
) {
}
