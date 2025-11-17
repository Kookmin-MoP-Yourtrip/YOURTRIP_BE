package backend.yourtrip.domain.feed.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record FeedDetailResponse(
    Long feedId,
    Long userId,
    String nickname,
    String profileImageUrl,
    String title,
    List<String> hashtags,
    String location,
    String contentUrl,
    int commentCount,
    int heartCount,
    long viewCount,

    Long courseId,           // 업로드 코스 ID (없으면 null)
    String courseTitle,      // 업로드 코스 제목
    String courseThumbnail,  // 업로드 코스 썸네일 S3 Key or URL
    String courseLocation,   // 업로드 코스 위치(여행지)
    LocalDateTime createdAt,
    boolean isPublic
) {

}
