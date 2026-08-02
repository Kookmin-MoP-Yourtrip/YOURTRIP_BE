package backend.yourtrip.domain.uploadcourse.mapper;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.place.PlaceImage;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.uploadcourse.dto.cache.CourseListItemCacheItem;
import backend.yourtrip.domain.uploadcourse.dto.cache.DayScheduleCacheItem;
import backend.yourtrip.domain.uploadcourse.dto.cache.PlaceCacheItem;
import backend.yourtrip.domain.uploadcourse.dto.cache.PlaceImageCacheItem;
import backend.yourtrip.domain.uploadcourse.dto.cache.UploadCourseDetailCacheItem;
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

    public static UploadCourse toEntity(UploadCourseCreateRequest request,
        TravelCourse travelCourse, User user, String thumbnailS3Key) {
        return UploadCourse.builder()
            .title(request.title())
            .introduction(request.introduction())
            .location(travelCourse.getLocation())
            .travelCourse(travelCourse)
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
            .startDate(uploadCourse.getTravelCourse().getStartDate())
            .endDate(uploadCourse.getTravelCourse().getEndDate())
            .forkCount(uploadCourse.getForkCount())
            .daySchedules(daySchedules)
            .build();
    }

    public static UploadCourseDetailResponse toDetailResponse(UploadCourseDetailCacheItem item,
        String thumbnailUrl, List<DayScheduleResponse> daySchedules) {
        return UploadCourseDetailResponse.builder()
            .uploadCourseId(item.uploadCourseId())
            .title(item.title())
            .introduction(item.introduction())
            .thumbnailImageUrl(thumbnailUrl)
            .keywords(item.keywords())
            .location(item.location())
            .startDate(item.startDate())
            .endDate(item.endDate())
            .forkCount(item.forkCount())
            .daySchedules(daySchedules)
            .build();
    }

    public static UploadCourseDetailCacheItem toDetailCacheItem(UploadCourse uploadCourse,
        List<DaySchedule> daySchedules) {
        return new UploadCourseDetailCacheItem(
            uploadCourse.getId(),
            uploadCourse.getTitle(),
            uploadCourse.getIntroduction(),
            uploadCourse.getLocation(),
            uploadCourse.getThumbnailImageS3Key(),
            uploadCourse.getTravelCourse().getStartDate(),
            uploadCourse.getTravelCourse().getEndDate(),
            uploadCourse.getForkCount(),
            uploadCourse.getKeywords().stream()
                .map(courseKeyword -> courseKeyword.getKeywordType().getLabel())
                .toList(),
            daySchedules.stream().map(UploadCourseMapper::toDayScheduleCacheItem).toList()
        );
    }

    private static DayScheduleCacheItem toDayScheduleCacheItem(DaySchedule daySchedule) {
        return new DayScheduleCacheItem(
            daySchedule.getId(),
            daySchedule.getDay(),
            daySchedule.getPlaces().stream().map(UploadCourseMapper::toPlaceCacheItem).toList()
        );
    }

    private static PlaceCacheItem toPlaceCacheItem(Place place) {
        return new PlaceCacheItem(
            place.getId(),
            place.getPlaceName(),
            place.getStartTime(),
            place.getMemo(),
            place.getLatitude(),
            place.getLongitude(),
            place.getPlaceUrl(),
            place.getPlaceLocation(),
            place.getPlaceImages().stream()
                .map(placeImage -> toPlaceImageCacheItem(place, placeImage))
                .toList()
        );
    }

    private static PlaceImageCacheItem toPlaceImageCacheItem(Place place, PlaceImage placeImage) {
        return new PlaceImageCacheItem(
            place.getId(),
            placeImage.getId(),
            placeImage.getPlaceImageS3Key()
        );
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

    public static CourseListItemCacheItem toCourseListItemCacheItem(UploadCourse uploadCourse) {
        return new CourseListItemCacheItem(
            uploadCourse.getId(),
            uploadCourse.getTitle(),
            uploadCourse.getLocation(),
            uploadCourse.getThumbnailImageS3Key(),
            uploadCourse.getForkCount(),
            uploadCourse.getKeywords().stream()
                .map(courseKeyword -> courseKeyword.getKeywordType().getLabel())
                .toList()
        );
    }

    public static UploadCourseListItemResponse toListItemResponse(CourseListItemCacheItem item,
        String thumbnailUrl) {
        return UploadCourseListItemResponse.builder()
            .uploadCourseId(item.uploadCourseId())
            .title(item.title())
            .location(item.location())
            .thumbnailImageUrl(thumbnailUrl)
            .forkCount(item.forkCount())
            .keywords(item.keywords())
            .build();
    }

    public static UploadCourseCreateResponse toCreateResponse(UploadCourse uploadCourse,
        TravelCourse travelCourse, List<DayScheduleResponse> daySchedules) {
        return UploadCourseCreateResponse.builder()
            .uploadCourseId(uploadCourse.getId())
            .title(uploadCourse.getTitle())
            .introduction(uploadCourse.getIntroduction())
            .location(travelCourse.getLocation())
            .startDate(travelCourse.getStartDate())
            .endDate(travelCourse.getEndDate())
            .keywords(uploadCourse.getKeywords().stream()
                .map(courseKeyword -> courseKeyword.getKeywordType().getLabel())
                .toList()
            )
            .daySchedules(daySchedules)
            .build();
    }
}
