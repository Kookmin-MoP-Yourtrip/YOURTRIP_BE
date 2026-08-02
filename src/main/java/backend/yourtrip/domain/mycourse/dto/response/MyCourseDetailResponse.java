package backend.yourtrip.domain.mycourse.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record MyCourseDetailResponse(
    Long courseId,
    String title,
    String location,
    LocalDate startDate,
    LocalDate endDate,
    LocalDateTime updatedAt,
    List<DayScheduleSummary> daySchedules
) {

    public record DayScheduleSummary(
        Long dayId,
        int day
    ) {

    }

}
