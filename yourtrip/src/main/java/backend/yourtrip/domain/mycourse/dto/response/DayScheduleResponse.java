package backend.yourtrip.domain.mycourse.dto.response;

import java.util.List;

public record DayScheduleResponse(
    Long dayScheduleId,
    int day,
    List<PlaceListResponse> places
) {

}
