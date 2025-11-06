package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListItemResponse;
import backend.yourtrip.domain.mycourse.entity.MyCourse;
import backend.yourtrip.domain.mycourse.entity.enums.CourseRole;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MyCourseMapper {

    public static MyCourse toEntity(MyCourseCreateRequest request) {
        return MyCourse.builder()
            .title(request.title())
            .location(request.location())
            .nights(request.nights())
            .days(request.days())
            .startDay(request.startDay())
            .endDay(request.endDay())
            .build();
    }

    public static MyCourseDetailResponse toDetailResponse(MyCourse course, CourseRole role) {
        return MyCourseDetailResponse.builder()
            .courseId(course.getId())
            .title(course.getTitle())
            .location(course.getLocation())
//            .totalBudget(course.getTotalBudget())
            .memberCount(course.getMemberCount())
            .thumbnailImageUrl(course.getThumbnailImageUrl())
            .days(course.getDays())
            .nights(course.getNights())
            .startDay(course.getStartDay())
            .endDay(course.getEndDay())
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
            .thumbnailImage(course.getThumbnailImageUrl())
            .nights(course.getNights())
            .days(course.getDays())
            .startDay(course.getStartDay())
            .endDay(course.getEndDay())
            .memberCount(course.getMemberCount())
            .build();
    }
}
