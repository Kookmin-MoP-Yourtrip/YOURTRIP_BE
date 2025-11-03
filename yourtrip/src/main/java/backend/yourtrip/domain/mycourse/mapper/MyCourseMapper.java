package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.entity.MyCourse;
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

    public static MyCourseDetailResponse toDetailResponse(MyCourse course) {
        return MyCourseDetailResponse.builder()
            .courseId(course.getId())
            .title(course.getTitle())
            .location(course.getLocation())
            .totalBudget(course.getTotalBudget())
            .memberCount(course.getMemberCount())
            .thumbnailImageUrl(course.getThumbnailImageUrl())
            .days(course.getDays())
            .nights(course.getNights())
            .startDay(course.getStartDay())
            .endDay(course.getEndDay())
            .updatedAt(course.getUpdatedAt())
            .daySchedules(DayScheduleMapper.toListResponse(
                course.getDaySchedules()))
            .build();


    }
}
