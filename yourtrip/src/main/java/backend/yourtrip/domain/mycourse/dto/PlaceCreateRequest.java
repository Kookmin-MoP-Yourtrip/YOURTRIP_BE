package backend.yourtrip.domain.mycourse.dto;

import java.time.LocalTime;

public record PlaceCreateRequest(
    String placeName,
    LocalTime startTime,
    String memo,
//    int budget,
    double latitude,
    double longitude,
    String placeUrl
) {

}
