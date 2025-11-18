package backend.yourtrip.domain.uploadcourse.mapper;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListItemResponse;
import backend.yourtrip.domain.uploadcourse.entity.CourseKeyword;
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
        List<DaySchedule> daySchedules, String presignedUrl, String profileUrl) {
        return UploadCourseDetailResponse.builder()
            .uploadCourseId(uploadCourse.getId())
            .title(uploadCourse.getTitle())
            .introduction(uploadCourse.getIntroduction())
            .thumbnailImageUrl(presignedUrl)
            .keywords(uploadCourse.getKeywords().stream()
                .map(CourseKeyword::getKeywordType)
                .toList()
            )
            .location(uploadCourse.getLocation())
            .heartCount(uploadCourse.getHeartCount())
            .commentCount(uploadCourse.getCommentCount())
            .viewCount(uploadCourse.getViewCount())
            .createdAt(uploadCourse.getCreatedAt())
            .writerId(uploadCourse.getUser().getId())
            .writerNickname(uploadCourse.getUser().getNickname())
            .writerProfileUrl(profileUrl)
//            .daySchedules(DayScheduleMapper.toListResponse(daySchedules))
            .build();
    }

    public static UploadCourseListItemResponse toListItemResponse(UploadCourse uploadCourse,
        String thumbnailUrl, String profileUrl) {
        return UploadCourseListItemResponse.builder()
            .uploadCourseId(uploadCourse.getId())
            .title(uploadCourse.getTitle())
            .location(uploadCourse.getLocation())
            .thumbnailImageUrl(thumbnailUrl)
            .heartCount(uploadCourse.getHeartCount())
            .commentCount(uploadCourse.getCommentCount())
            .viewCount(uploadCourse.getViewCount())
            .writerId(uploadCourse.getUser().getId())
            .writerNickname(uploadCourse.getUser().getNickname())
            .writerProfileUrl(profileUrl)
            .createdAt(uploadCourse.getCreatedAt())
            .build();
    }
}
