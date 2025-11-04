package backend.yourtrip.domain.mycourse.dto;

import java.time.LocalTime;
import lombok.Builder;

@Builder
public record PlaceListResponse(
    Long placeId,
    String placeName,
    LocalTime starTime,
    String memo,
//    int budget,
    double latitude,
    double longitude,
    String placeUrl
) {

}
