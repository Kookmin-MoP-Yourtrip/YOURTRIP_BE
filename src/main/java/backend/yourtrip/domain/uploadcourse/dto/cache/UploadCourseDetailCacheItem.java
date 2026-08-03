package backend.yourtrip.domain.uploadcourse.dto.cache;

import java.time.LocalDate;
import java.util.List;

/**
 * 코스 상세 조회 응답(UploadCourseDetailResponse) 전체를 코스ID 단위로 캐싱하기 위한 DTO.
 * URL 자체를 캐싱하지 않고 S3 key만 담아, 응답 조립 시점에 CloudFront 공개 URL(서명 없음)을
 * 매번 새로 조합한다. 목록용 CourseListItemCacheItem과 필드가 일부 겹치지만, TTL/무효화 정책이
 * 서로 달라 별도 DTO로 둔다.
 */
public record UploadCourseDetailCacheItem(
    Long uploadCourseId,
    String title,
    String introduction,
    String location,
    String thumbnailImageS3Key,
    LocalDate startDate,
    LocalDate endDate,
    int forkCount,
    List<String> keywords,
    List<DayScheduleCacheItem> daySchedules
) {

}
