package backend.yourtrip.domain.mycourse.dto.response;

import java.time.LocalTime;
import lombok.Builder;

@Builder
public record PlaceCreateResponse(
    Long placeId,
    String placeName,
    Double latitude,
    Double longitude,
    String placeUrl,
    String placeLocation,
    String memo,
    LocalTime startTime
) {

}
