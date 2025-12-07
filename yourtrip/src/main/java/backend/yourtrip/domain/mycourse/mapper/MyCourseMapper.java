package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.request.AICourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListItemResponse;
import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import backend.yourtrip.domain.mycourse.entity.myCourse.enums.CourseRole;
import backend.yourtrip.domain.mycourse.entity.myCourse.enums.MyCourseType;
import backend.yourtrip.global.gemini.dto.GeminiCourseDto;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MyCourseMapper {

    //직접 생성
    public static MyCourse toEntity(MyCourseCreateRequest request) {
        return MyCourse.builder()
            .title(request.title())
            .location(request.location())
            .startDate(request.startDate())
            .endDate(request.endDate())
            .type(MyCourseType.DIRECT)
            .build();
    }

    public static MyCourseCreateResponse toCreateResponse(MyCourse course) {
        return MyCourseCreateResponse.builder()
            .myCourseId(course.getId())
            .title(course.getTitle())
            .location(course.getLocation())
            .memberCount(course.getMemberCount())
            .startDate(course.getStartDate())
            .endDate(course.getEndDate())
            .build();
    }

    public static MyCourseDetailResponse toDetailResponse(MyCourse course, CourseRole role) {
        return MyCourseDetailResponse.builder()
            .courseId(course.getId())
            .title(course.getTitle())
            .location(course.getLocation())
            .memberCount(course.getMemberCount())
            .startDate(course.getStartDate())
            .endDate(course.getEndDate())
            .role(role)
            .updatedAt(course.getUpdatedAt())
            .daySchedules(DayScheduleMapper.toSummaryResponse(
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
            .courseId(course.getId())
            .build();
    }

    //포크 생성
    public static MyCourse toCopyEntity(MyCourse originalMyCourse) {
        return MyCourse.builder()
            .title(originalMyCourse.getTitle())
            .location(originalMyCourse.getLocation())
            .startDate(originalMyCourse.getStartDate())
            .endDate(originalMyCourse.getEndDate())
            .type(MyCourseType.FORK)
            .build();
    }

    //AI 생성
    public static MyCourse toAICourseEntity(AICourseCreateRequest request,
        GeminiCourseDto courseDto) {
        return MyCourse.builder()
            .title(courseDto.title())
            .location(request.location())
            .startDate(request.startDate())
            .endDate(request.endDate())
            .type(MyCourseType.AI)
            .build();
    }
}
