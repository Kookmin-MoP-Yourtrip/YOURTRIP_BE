package backend.yourtrip.domain.mycourse.dto.request;

import java.time.LocalDate;

public record MyCourseCreateRequest(
    String title,
    String location,
    int nights,
    int days,
    LocalDate startDay,
    LocalDate endDay
) {

}
