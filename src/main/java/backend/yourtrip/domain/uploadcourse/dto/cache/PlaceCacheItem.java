package backend.yourtrip.domain.uploadcourse.dto.cache;

import java.time.LocalTime;
import java.util.List;

/**
 * 장소 1건을 캐싱하기 위한 DTO.
 */
public record PlaceCacheItem(
    Long placeId,
    String placeName,
    LocalTime startTime,
    String memo,
    double latitude,
    double longitude,
    String placeUrl,
    String placeLocation,
    List<PlaceImageCacheItem> placeImages
) {

}
