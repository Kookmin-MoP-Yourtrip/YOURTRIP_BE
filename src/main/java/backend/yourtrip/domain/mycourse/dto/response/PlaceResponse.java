package backend.yourtrip.domain.mycourse.dto.response;

import java.time.LocalTime;
import java.util.List;
import lombok.Builder;

@Builder
public record PlaceResponse(
    Long placeId,
    String placeName,
    LocalTime startTime,
    String memo,
    // Place.latitude/longitude가 nullable이므로 원시 double로 받으면 언박싱 NPE가 난다.
    // 검증되지 않은 좌표는 null로 응답한다.
    Double latitude,
    Double longitude,
    String placeUrl,
    String placeLocation,
    List<PlaceImageResponse> placeImages
) {

}
