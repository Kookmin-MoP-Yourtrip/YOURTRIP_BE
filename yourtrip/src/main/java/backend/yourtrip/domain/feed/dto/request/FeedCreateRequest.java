package backend.yourtrip.domain.feed.dto.request;

import java.util.List;

public record FeedCreateRequest(
        String title,
        String location,
        String contendUrl,
        List<String> hashtags,
        Long uploadCourseId
) {
}
