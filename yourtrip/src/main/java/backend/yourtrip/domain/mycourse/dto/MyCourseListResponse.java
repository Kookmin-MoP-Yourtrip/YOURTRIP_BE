package backend.yourtrip.domain.mycourse.dto;

import java.util.List;

public record MyCourseListResponse(
    List<MyCourseListItemResponse> myCourses
) {

}
