package backend.yourtrip.domain.uploadcourse.event;

import backend.yourtrip.domain.uploadcourse.dto.cache.CourseListItemCacheItem;
import backend.yourtrip.domain.uploadcourse.dto.cache.UploadCourseDetailCacheItem;

/**
 * 업로드 코스 수정 트랜잭션이 커밋된 뒤 courseDetail/courseListItem 캐시를 write-through로
 * 갱신하기 위한 이벤트. 커밋 전(영속성 컨텍스트가 살아있는) 시점에 이미 조립해둔 캐시 DTO를
 * 그대로 담아, 커밋 이후 실행되는 리스너가 엔티티에 다시 접근하지 않고 캐시 쓰기만 수행하도록 한다.
 */
public record UploadCourseCacheRefreshEvent(
    Long uploadCourseId,
    UploadCourseDetailCacheItem detailCacheItem,
    CourseListItemCacheItem listItemCacheItem
) {

}
