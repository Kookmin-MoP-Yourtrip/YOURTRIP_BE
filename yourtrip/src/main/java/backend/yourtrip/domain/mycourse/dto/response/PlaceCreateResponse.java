package backend.yourtrip.domain.mycourse.dto.response;

import java.time.LocalTime;
import lombok.Builder;

@Builder
public record PlaceCreateResponse(
    Long placeId,
    String placeName,
    double latitude,
    double longitude,
    String placeUrl,
    String placeLocation,
    String memo,
    LocalTime startTime
) {

}
