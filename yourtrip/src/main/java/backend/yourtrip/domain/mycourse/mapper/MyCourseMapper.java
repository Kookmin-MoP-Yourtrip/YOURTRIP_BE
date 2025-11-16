package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListItemResponse;
import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import backend.yourtrip.domain.mycourse.entity.myCourse.enums.CourseRole;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MyCourseMapper {

    public static MyCourse toEntity(MyCourseCreateRequest request) {
        return MyCourse.builder()
            .title(request.title())
            .location(request.location())
            .startDate(request.startDate())
            .endDate(request.endDate())
            .build();
    }

    public static MyCourseDetailResponse toDetailResponse(MyCourse course, CourseRole role) {
        return MyCourseDetailResponse.builder()
            .courseId(course.getId())
            .title(course.getTitle())
            .location(course.getLocation())
//            .totalBudget(course.getTotalBudget())
            .memberCount(course.getMemberCount())
            .startDate(course.getStartDate())
            .endDate(course.getEndDate())
            .role(role)
            .updatedAt(course.getUpdatedAt())
            .daySchedules(DayScheduleMapper.toListResponse(
                course.getDaySchedules()))
            .build();
    }

    public static MyCourseListItemResponse toListItemResponse(MyCourse course) {
        return MyCourseListItemResponse.builder()
            .title(course.getTitle())
            .location(course.getLocation())
            .startDate(course.getStartDate())
            .endDate(course.getEndDate())
            .memberCount(course.getMemberCount())
            .build();
    }
}
