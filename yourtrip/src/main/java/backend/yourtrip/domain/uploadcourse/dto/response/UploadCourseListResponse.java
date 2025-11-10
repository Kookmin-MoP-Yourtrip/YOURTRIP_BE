package backend.yourtrip.domain.uploadcourse.dto.response;

import java.util.List;

public record UploadCourseListResponse(
    List<UploadCourseListItemResponse> uploadCourses
) {

}
