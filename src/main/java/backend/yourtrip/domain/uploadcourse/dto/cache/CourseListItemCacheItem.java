package backend.yourtrip.domain.uploadcourse.dto.cache;

import java.util.List;

/**
 * 코스 1개의 콘텐츠를 코스ID 단위로 캐싱하기 위한 DTO.
 * 인기 목록 전용이 아니라, 코스ID로 화면 항목을 조립하는 모든 경로가 재사용할 수 있는 범용 캐시 값이다.
 * presigned URL은 만료(15분)가 있어 캐싱하지 않고 S3 key만 담아, 응답 조립 시점에 매번 새로 발급한다.
 */
public record CourseListItemCacheItem(
    Long uploadCourseId,
    String title,
    String location,
    String thumbnailImageS3Key,
    int forkCount,
    List<String> keywords
) {

}
