package backend.yourtrip.domain.mycourse.dto.response;

import lombok.Builder;

@Builder
public record PlaceUpdateResponse(
    Long placeId,
    String placeName,
    Double latitude,
    Double longitude,
    String placeUrl,
    String placeLocation
) {

}
