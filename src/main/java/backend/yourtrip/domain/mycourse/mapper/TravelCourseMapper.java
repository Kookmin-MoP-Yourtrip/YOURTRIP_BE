package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.request.AICourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListItemResponse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import backend.yourtrip.domain.user.entity.User;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TravelCourseMapper {

    //직접 생성
    public static TravelCourse toEntity(MyCourseCreateRequest request, User user) {
        return TravelCourse.builder()
            .user(user)
            .title(request.title())
            .location(request.location())
            .startDate(request.startDate())
            .endDate(request.endDate())
            .type(TravelCourseType.DIRECT)
            .build();
    }

    public static MyCourseCreateResponse toCreateResponse(TravelCourse course) {
        return MyCourseCreateResponse.builder()
            .myCourseId(course.getId())
            .title(course.getTitle())
            .location(course.getLocation())
            .startDate(course.getStartDate())
            .endDate(course.getEndDate())
            .build();
    }

    public static MyCourseDetailResponse toDetailResponse(TravelCourse course) {
        return MyCourseDetailResponse.builder()
            .courseId(course.getId())
            .title(course.getTitle())
            .location(course.getLocation())
            .startDate(course.getStartDate())
            .endDate(course.getEndDate())
            .updatedAt(course.getUpdatedAt())
            .daySchedules(DayScheduleMapper.toSummaryResponse(
                course.getDaySchedules()))
            .build();
    }

    public static MyCourseListItemResponse toListItemResponse(TravelCourse course) {
        return MyCourseListItemResponse.builder()
            .title(course.getTitle())
            .location(course.getLocation())
            .startDate(course.getStartDate())
            .endDate(course.getEndDate())
            .courseId(course.getId())
            .build();
    }

    //포크/업로드 사본 공용 복사
    public static TravelCourse toCopyEntity(TravelCourse originalTravelCourse,
        TravelCourseType type, User user) {
        return TravelCourse.builder()
            .user(user)
            .title(originalTravelCourse.getTitle())
            .location(originalTravelCourse.getLocation())
            .startDate(originalTravelCourse.getStartDate())
            .endDate(originalTravelCourse.getEndDate())
            .type(type)
            .build();
    }

    //AI 생성 — LLM 산출물에서 필요한 값은 title뿐이라 DTO가 아니라 문자열로 받는다
    public static TravelCourse toAICourseEntity(AICourseCreateRequest request,
        String title, User user) {
        return TravelCourse.builder()
            .user(user)
            .title(title)
            .location(request.location())
            .startDate(request.startDate())
            .endDate(request.endDate())
            .type(TravelCourseType.AI)
            .build();
    }
}
