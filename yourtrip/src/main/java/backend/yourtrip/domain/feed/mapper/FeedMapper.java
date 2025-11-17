package backend.yourtrip.domain.feed.mapper;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.Hashtag;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.FeedErrorCode;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FeedMapper {

    /**
     * 피드 생성용 엔티티 매핑
     */
    public static Feed toEntity(User user, FeedCreateRequest request) {
        return Feed.builder()
            .user(user)
            .title(request.title())
            .location(request.location())
            .contentUrl(request.contentUrl())
            // uploadCourse, isPublic 등은 서비스 레벨에서 따로 세팅하고 있으면 그대로 두면 됨
            .build();
    }

    /**
     * 피드 상세보기 응답 매핑
     */
    public static FeedDetailResponse toDetailResponse(Feed feed) {

        if (feed == null) {
            throw new BusinessException(FeedErrorCode.FEED_NOT_FOUND);
        }

        User user = feed.getUser();
        UploadCourse uploadCourse = feed.getUploadCourse();

        // 해시태그 이름 리스트
        List<String> hashtagNames = feed.getHashtags() != null
            ? feed.getHashtags().stream()
            .map(Hashtag::getTagName)
            .collect(Collectors.toList())
            : List.of();

        // --- 업로드 코스 정보 매핑 ---
        Long courseId = null;
        String courseTitle = null;
        String courseThumbnail = null;
        String courseLocation = null;

        if (uploadCourse != null) {
            courseId = uploadCourse.getId();
            courseTitle = uploadCourse.getTitle();
            courseThumbnail = uploadCourse.getThumbnailImageS3Key();
            courseLocation = uploadCourse.getLocation();
        }

        return FeedDetailResponse.builder()
            .feedId(feed.getId())
            .userId(user != null ? user.getId() : null)
            .nickname(user != null ? user.getNickname() : null)
            .profileImageUrl(user != null ? user.getProfileImageS3Key() : null)
            .title(feed.getTitle())
            .hashtags(hashtagNames)
            .location(feed.getLocation())
            .contentUrl(feed.getContentUrl())
            .commentCount(feed.getCommentCount())
            .heartCount(feed.getHeartCount())
            .viewCount(feed.getViewCount())

            // --- 업로드 코스 정보 ---
            .courseId(courseId)
            .courseTitle(courseTitle)
            .courseThumbnail(courseThumbnail)
            .courseLocation(courseLocation)

            // --- 공통 메타 정보 ---
            .createdAt(feed.getCreatedAt())
            .isPublic(feed.isPublic())
            .build();
    }

    /**
     * 피드 리스트 응답 매핑
     */
    public static FeedListResponse toListResponse(Page<Feed> feedPage) {
        List<FeedDetailResponse> responses = feedPage.getContent().stream()
            .map(FeedMapper::toDetailResponse)
            .toList();

        return new FeedListResponse(
            responses,
            feedPage.getNumber(),
            feedPage.getTotalPages(),
            feedPage.getTotalElements(),
            feedPage.hasNext(),
            feedPage.hasPrevious()
        );
    }
}