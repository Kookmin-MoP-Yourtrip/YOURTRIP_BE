package backend.yourtrip.domain.uploadcourse.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record UploadCourseListItemResponse(
    Long uploadCourseId,
    String title,
    @Schema(description = "여행지")
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
