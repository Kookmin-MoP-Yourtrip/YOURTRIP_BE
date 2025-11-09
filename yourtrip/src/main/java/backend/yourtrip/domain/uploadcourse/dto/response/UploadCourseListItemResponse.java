package backend.yourtrip.domain.uploadcourse.dto.response;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record UploadCourseListItemResponse(
    Long uploadCourseId,
    String title,
    String location,
    String thumbnailImageUrl,
    int heartCount,
    int commentCount,
    int viewCount,
    Long writerId,
    String writerNickname,
    String writerProfileUrl,
    LocalDateTime createdAt
) {

}
