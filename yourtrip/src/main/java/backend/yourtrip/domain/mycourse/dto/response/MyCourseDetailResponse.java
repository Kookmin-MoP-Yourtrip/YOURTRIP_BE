package backend.yourtrip.domain.mycourse.dto.response;

import backend.yourtrip.domain.mycourse.entity.myCourse.enums.CourseRole;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record MyCourseDetailResponse(
    Long courseId,
    String title,
    String location,
    int memberCount,
    LocalDate startDate,
    LocalDate endDate,
    CourseRole role,
    LocalDateTime updatedAt,
    List<DayScheduleSummary> daySchedules
) {

    public record DayScheduleSummary(
        Long dayId,
        int day
    ) {

    }

}
