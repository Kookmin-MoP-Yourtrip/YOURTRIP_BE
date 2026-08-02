package backend.yourtrip.domain.uploadcourse.dto.cache;

import java.util.List;

/**
 * 일차 1건(장소 목록 포함)을 캐싱하기 위한 DTO.
 */
public record DayScheduleCacheItem(
    Long dayScheduleId,
    int day,
    List<PlaceCacheItem> places
) {

}
