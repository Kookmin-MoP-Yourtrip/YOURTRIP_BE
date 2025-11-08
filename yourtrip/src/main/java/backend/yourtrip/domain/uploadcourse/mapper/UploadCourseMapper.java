package backend.yourtrip.domain.uploadcourse.mapper;

import backend.yourtrip.domain.mycourse.entity.MyCourse;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.user.entity.User;
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
        User user) {
        return UploadCourse.builder()
            .title(request.title())
            .introduction(request.introduction())
            .thumbnailImageUrl(request.thumbnailImage())
            .myCourse(myCourse)
            .user(user)
            .build();
    }

}
