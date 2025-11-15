package backend.yourtrip.domain.uploadcourse.dto.response;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record UploadCourseDetailResponse(
    Long uploadCourseId,
    String title,
    @Schema(description = "여행지")
    String location,
    String introduction,
    String thumbnailImageUrl,
    List<KeywordType> keywords,
    int heartCount,
    int commentCount,
    int viewCount,
    LocalDateTime createdAt,

    Long writerId,
    String writerNickname,
    String writerProfileUrl,

    List<DayScheduleResponse> daySchedules
) {

}
