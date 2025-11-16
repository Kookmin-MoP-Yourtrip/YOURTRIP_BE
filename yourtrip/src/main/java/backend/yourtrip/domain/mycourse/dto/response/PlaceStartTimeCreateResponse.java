package backend.yourtrip.domain.mycourse.dto.response;

import java.time.LocalTime;

public record PlaceStartTimeCreateResponse(
    LocalTime startTime,
    String message
) {

}
