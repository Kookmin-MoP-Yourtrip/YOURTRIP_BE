package backend.yourtrip.domain.uploadcourse.dto.response;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleListResponse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record UploadCourseDetailResponse(
    Long uploadCourseId,
    String title,
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

    List<DayScheduleListResponse> daySchedules
) {

}
