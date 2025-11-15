package backend.yourtrip.domain.mycourse.dto.response;

import java.time.LocalTime;
import lombok.Builder;

@Builder
public record PlaceListResponse(
    Long placeId,
    String placeName,
    LocalTime startTime,
    String memo,
//    int budget,
    double latitude,
    double longitude,
    String placeUrl,
    String placeLocation
) {

}
