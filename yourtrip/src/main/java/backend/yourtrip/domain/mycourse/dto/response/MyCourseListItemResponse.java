package backend.yourtrip.domain.mycourse.dto.response;

import java.time.LocalDate;
import lombok.Builder;

@Builder
public record MyCourseListItemResponse(
    String title,
    String location,
    int nights,
    int days,
    LocalDate startDay,
    LocalDate endDay,
    int memberCount
) {

}
