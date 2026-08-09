package backend.yourtrip.domain.mycourse.event;

import java.util.List;

/**
 * 코스 삭제 트랜잭션이 커밋된 뒤 S3에서 정리해야 할 장소 이미지 key 목록을 담아 발행하는 이벤트.
 * 영속성 컨텍스트가 살아있는 트랜잭션 내부에서 미리 수집한 key만 담아, 커밋 이후 리스너가
 * 엔티티에 다시 접근하지 않고 S3 삭제만 수행하도록 한다.
 */
public record MyCourseImagesCleanupEvent(List<String> imageS3Keys) {

}
