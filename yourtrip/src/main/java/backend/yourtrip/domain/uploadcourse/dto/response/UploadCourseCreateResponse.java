package backend.yourtrip.domain.uploadcourse.dto.response;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import java.time.LocalDate;
import java.util.List;

public record UploadCourseCreateResponse(
    Long uploadCourseId,
    String title,
    String location,
    String introduction,
    String thumbnailImageUrl,
    LocalDate startDate,
    LocalDate endDate,
    List<String> keywords,
    List<DayScheduleResponse> daySchedules
) {

}
