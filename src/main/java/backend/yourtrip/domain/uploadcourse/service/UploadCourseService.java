package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseCreateResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListResponse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.entity.enums.UploadCourseSortType;
import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

public interface UploadCourseService {

    CourseKeywordListResponse getCourseKeywordList();

    UploadCourseCreateResponse createUploadCourse(UploadCourseCreateRequest request,
        MultipartFile thumbnailImage);

    UploadCourseDetailResponse getDetail(Long uploadCourseId, String viewerKey);

    /**
     * 컨트롤러에서 추출한 클라이언트 IP/User-Agent로, 조회수 중복 방지에 쓸 viewerKey를 만든다.
     * 로그인 여부 판별까지 이 메서드 내부에서 처리해, 컨트롤러가 UserService에 직접 의존하지 않게 한다.
     */
    String resolveViewerKey(String clientIp, String userAgent);

    UploadCourseListResponse getAllForSearch(String keyword, List<KeywordType> tags,
        UploadCourseSortType sortType);

    UploadCourseListResponse getMyUploadCourses();

    UploadCourseListResponse getPopularCourses(KeywordType theme);

    UploadCourseDetailResponse updateUploadCourse(Long uploadCourseId,
        UploadCourseUpdateRequest request, MultipartFile thumbnailImage,
        List<MultipartFile> placeImages);

    void refreshAllPopularCoursesCache();

    /**
     * 조회수 동기화 스케줄러가 청크 단위로 호출한다. 이 메서드가 반환되는 시점에 해당 청크의
     * DB 트랜잭션이 독립적으로 커밋되어야, 호출부가 커밋 확인 후에만 Redis 카운터를 차감할 수 있다.
     */
    void applyViewCountIncrements(Map<Long, Long> increments);
}