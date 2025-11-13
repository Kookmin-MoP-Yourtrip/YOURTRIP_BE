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
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.stream.Collectors;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FeedMapper {

    public static Feed toEntity(User user, FeedCreateRequest request, UploadCourse uploadCourse) {
        return Feed.builder()
                .user(user)
                .title(request.title())
                .location(request.location())
                .content(request.content())
                .tagCourse(uploadCourse)
                .build();
    }

    public static FeedDetailResponse toDetailResponse(Feed feed) {

        if(feed == null) {
            throw new BusinessException(FeedErrorCode.FEED_NOT_FOUND);
        }
        List<String> hashtagNames = feed.getHashtags() != null
                ? feed.getHashtags().stream()
                    .map(Hashtag::getTagName)
                    .collect(Collectors.toList())
                :List.of();

        User user = feed.getUser();
        UploadCourse uploadCourse = feed.getTagCourse();

        return FeedDetailResponse.builder()
                .feedId(feed.getId())
                .userId(user != null ? user.getId() : null)
                .nickname(user != null ? user.getNickname() : null)
                .profileImageUrl(user != null ? user.getProfileImageUrl() : null)
                .title(feed.getTitle())
                .hashtags(hashtagNames)
                .location(feed.getLocation())
                .contentUrl(feed.getContent())
                .commentCount(feed.getCommentCount())
                .heartCount(feed.getHeartCount())
                .viewCount(feed.getViewCount())
                .uploadCourseId(uploadCourse != null ? uploadCourse.getId() : null)
                .build();
    }

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
