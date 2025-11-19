package backend.yourtrip.domain.uploadcourse.dto.response;

import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

@Builder
public record UploadCourseSummaryResponse(
    Long uploadCourseId,
    String title,
    String location,
    String introduction,
    String thumbnailImageUrl,
    LocalDate startDate,
    LocalDate endDate,
    int forkCount,
    List<String> keywords
) {

}
