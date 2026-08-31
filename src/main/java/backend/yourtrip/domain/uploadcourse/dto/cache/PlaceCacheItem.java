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
    // UploadCourseMapper.toPlaceCacheItem이 Place의 nullable 좌표를 그대로 읽으므로 Double이어야 한다.
    Double latitude,
    Double longitude,
    String placeUrl,
    String placeLocation,
    List<PlaceImageCacheItem> placeImages
) {

}
