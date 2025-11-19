package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseCreateResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseSummaryResponse;
import backend.yourtrip.domain.uploadcourse.entity.enums.UploadCourseSortType;
import org.springframework.web.multipart.MultipartFile;

public interface UploadCourseService {

    CourseKeywordListResponse getCourseKeywordList();

    UploadCourseCreateResponse createUploadCourse(UploadCourseCreateRequest request,
        MultipartFile thumbnailImage);

    UploadCourseSummaryResponse getDetail(Long uploadCourseId);

    UploadCourseListResponse getAllList(UploadCourseSortType sortType);
}
