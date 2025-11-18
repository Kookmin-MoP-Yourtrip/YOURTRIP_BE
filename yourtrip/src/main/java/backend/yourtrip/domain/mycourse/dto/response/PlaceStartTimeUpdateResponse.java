package backend.yourtrip.domain.mycourse.dto.response;

import java.time.LocalTime;

public record PlaceStartTimeUpdateResponse(
    Long placeId,
    LocalTime startTime
) {

}
