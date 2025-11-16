package backend.yourtrip.domain.mycourse.dto.response;

import java.time.LocalTime;
import java.util.List;
import lombok.Builder;

@Builder
public record PlaceListResponse(
    Long placeId,
    String placeName,
    LocalTime startTime,
    String memo,
    double latitude,
    double longitude,
    String placeUrl,
    String placeLocation,
    List<String> placeImagesUrls
) {

}
