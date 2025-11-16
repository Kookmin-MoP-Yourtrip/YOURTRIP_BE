package backend.yourtrip.domain.mycourse.dto.response;

import java.time.LocalDate;
import lombok.Builder;

@Builder
public record MyCourseCreateResponse(
    Long myCourseId,
    String title,
    String location,
    LocalDate startDate,
    LocalDate endDate,
    int memberCount
) {

}
