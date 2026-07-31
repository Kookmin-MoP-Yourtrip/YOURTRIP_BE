package backend.yourtrip.domain.mycourse.dto.response;

import lombok.Builder;

@Builder
public record PlaceUpdateResponse(
    Long placeId,
    String placeName,
    double latitude,
    double longitude,
    String placeUrl,
    String placeLocation
) {

}
