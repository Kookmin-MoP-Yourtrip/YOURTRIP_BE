package backend.yourtrip.domain.mycourse.dto;

import java.util.List;

public record DayScheduleListResponse(
    Long dayScheduleId,
    int day,
    List<PlaceListResponse> places
) {

}
