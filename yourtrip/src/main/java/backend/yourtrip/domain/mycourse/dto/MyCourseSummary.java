package backend.yourtrip.domain.mycourse.dto;

import java.time.LocalDate;
import lombok.Builder;

@Builder
public record MyCourseSummary(
    String title,
    String location,
    String thumbnailImage,
    int nights,
    int days,
    LocalDate startDay,
    LocalDate endDay,
    int memberCount
) {

}
