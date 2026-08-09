package backend.yourtrip.domain.uploadcourse.event;

/**
 * 업로드 코스 삭제 트랜잭션이 커밋된 뒤 Redis 캐시(상세/아이템/인기 랭킹)를 즉시 무효화하기
 * 위한 이벤트. 캐시 삭제 자체는 가벼운 명령이라 이 이벤트의 리스너는 동기(AFTER_COMMIT)로
 * 처리한다 — S3 정리(UploadCourseImagesCleanupEvent, 비동기)와는 관심사를 분리한다.
 */
public record UploadCourseDeletedEvent(Long uploadCourseId) {

}
