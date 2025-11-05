package backend.yourtrip.domain.mycourse.dto.response;

import java.util.List;

public record MyCourseListResponse(
    List<MyCourseListItemResponse> myCourses
) {

}
