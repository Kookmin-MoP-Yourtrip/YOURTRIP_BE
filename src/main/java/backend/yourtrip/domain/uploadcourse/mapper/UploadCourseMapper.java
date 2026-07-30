package backend.yourtrip.domain.uploadcourse.mapper;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseCreateResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListItemResponse;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.user.entity.User;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UploadCourseMapper {

    public static CourseKeywordListResponse toKeywordListResponse() {
        return CourseKeywordListResponse.builder()
            .travelMode(KeywordType.findByCategory("travelMode"))
            .companionType(KeywordType.findByCategory("companionType"))
            .mood(KeywordType.findByCategory("mood"))
            .duration(KeywordType.findByCategory("duration"))
            .budget(KeywordType.findByCategory("budget"))
            .build();
    }

    public static UploadCourse toEntity(UploadCourseCreateRequest request, MyCourse myCourse,
        User user, String thumbnailS3Key) {
        return UploadCourse.builder()
            .title(request.title())
            .introduction(request.introduction())
            .location(myCourse.getLocation())
            .myCourse(myCourse)
            .user(user)
            .thumbnailImageS3Key(thumbnailS3Key)
            .build();
    }

    public static UploadCourseDetailResponse toDetailResponse(UploadCourse uploadCourse,
        String thumbnailUrl, List<DayScheduleResponse> daySchedules) {
        return UploadCourseDetailResponse.builder()
            .uploadCourseId(uploadCourse.getId())
            .title(uploadCourse.getTitle())
            .introduction(uploadCourse.getIntroduction())
            .thumbnailImageUrl(thumbnailUrl)
            .keywords(uploadCourse.getKeywords().stream()
                .map(courseKeyword -> courseKeyword.getKeywordType().getLabel())
                .toList()
            )
            .location(uploadCourse.getLocation())
            .startDate(uploadCourse.getMyCourse().getStartDate())
            .endDate(uploadCourse.getMyCourse().getEndDate())
            .forkCount(uploadCourse.getForkCount())
            .daySchedules(daySchedules)
            .build();
    }

    public static UploadCourseListItemResponse toListItemResponse(UploadCourse uploadCourse,
        String thumbnailUrl) {
        return UploadCourseListItemResponse.builder()
            .uploadCourseId(uploadCourse.getId())
            .title(uploadCourse.getTitle())
            .location(uploadCourse.getLocation())
            .thumbnailImageUrl(thumbnailUrl)
            .forkCount(uploadCourse.getForkCount())
            .keywords(uploadCourse.getKeywords().stream()
                .map(courseKeyword -> courseKeyword.getKeywordType().getLabel())
                .toList()
            )
            .build();
    }

    public static UploadCourseCreateResponse toCreateResponse(UploadCourse uploadCourse,
        MyCourse myCourse, List<DayScheduleResponse> daySchedules) {
        return UploadCourseCreateResponse.builder()
            .uploadCourseId(uploadCourse.getId())
            .title(uploadCourse.getTitle())
            .introduction(uploadCourse.getIntroduction())
            .location(myCourse.getLocation())
            .startDate(myCourse.getStartDate())
            .endDate(myCourse.getEndDate())
            .keywords(uploadCourse.getKeywords().stream()
                .map(courseKeyword -> courseKeyword.getKeywordType().getLabel())
                .toList()
            )
            .daySchedules(daySchedules)
            .build();
    }
}
