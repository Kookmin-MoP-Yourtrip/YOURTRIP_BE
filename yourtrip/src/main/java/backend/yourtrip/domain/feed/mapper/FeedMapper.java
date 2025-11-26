package backend.yourtrip.domain.feed.mapper;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.Hashtag;
import backend.yourtrip.domain.feed.entity.FeedMedia;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.FeedErrorCode;
import backend.yourtrip.global.s3.service.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FeedMapper {

    private final S3Service s3Service;

    public Feed toEntity(User user, FeedCreateRequest request, UploadCourse uploadCourse) {
        return Feed.builder()
                .user(user)
                .title(request.title())
                .location(request.location())
                .content(request.content())
                .uploadCourse(uploadCourse)
                .build();
    }

    public FeedDetailResponse toDetailResponse(Feed feed) {

        if(feed == null) {
            throw new BusinessException(FeedErrorCode.FEED_NOT_FOUND);
        }
        List<String> hashtagNames = feed.getHashtags() != null
            ? feed.getHashtags().stream()
            .map(Hashtag::getTagName)
            .collect(Collectors.toList())
            :List.of();

        User user = feed.getUser();
        UploadCourse uploadCourse = feed.getUploadCourse();

        String profileImageUrl = null;
        if (user != null && user.getProfileImageS3Key() != null && !user.getProfileImageS3Key().isBlank()) {
            profileImageUrl = s3Service.getPresignedUrl(user.getProfileImageS3Key());
        }

        List<FeedDetailResponse.MediaResponse> mediaList = feed.getMediaList() != null
                ? feed.getMediaList().stream()
                .map(media -> FeedDetailResponse.MediaResponse.builder()
                        .mediaId(media.getId())
                        .mediaUrl(s3Service.getPresignedUrl(media.getMediaS3Key()))
                        .mediaType(media.getMediaType().name())
                        .displayOrder(media.getDisplayOrder())
                        .build())
                .toList()
                : List.of();

        return FeedDetailResponse.builder()
                .feedId(feed.getId())
                .userId(user != null ? user.getId() : null)
                .nickname(user != null ? user.getNickname() : null)
                .profileImageUrl(profileImageUrl)
                .title(feed.getTitle())
                .hashtags(hashtagNames)
                .location(feed.getLocation())
                .content(feed.getContent())
                .commentCount(feed.getCommentCount())
                .heartCount(feed.getHeartCount())
                .viewCount(feed.getViewCount())
                .uploadCourseId(uploadCourse != null ? uploadCourse.getId() : null)
                .mediaList(mediaList)
                .build();
    }

    public FeedDetailResponse toDetailResponse(Feed feed, boolean isLiked) {
        if(feed == null) {
            throw new BusinessException(FeedErrorCode.FEED_NOT_FOUND);
        }
        List<String> hashtagNames = feed.getHashtags() != null
                ? feed.getHashtags().stream()
                .map(Hashtag::getTagName)
                .collect(Collectors.toList())
                :List.of();

        User user = feed.getUser();
        UploadCourse uploadCourse = feed.getUploadCourse();

        String profileImageUrl = null;
        if (user != null && user.getProfileImageS3Key() != null && !user.getProfileImageS3Key().isBlank()) {
            profileImageUrl = s3Service.getPresignedUrl(user.getProfileImageS3Key());
        }

        List<FeedDetailResponse.MediaResponse> mediaList = feed.getMediaList() != null
                ? feed.getMediaList().stream()
                .map(media -> FeedDetailResponse.MediaResponse.builder()
                        .mediaId(media.getId())
                        .mediaUrl(s3Service.getPresignedUrl(media.getMediaS3Key()))
                        .mediaType(media.getMediaType().name())
                        .displayOrder(media.getDisplayOrder())
                        .build())
                .toList()
                : List.of();

        return FeedDetailResponse.builder()
                .feedId(feed.getId())
                .userId(user != null ? user.getId() : null)
                .nickname(user != null ? user.getNickname() : null)
                .profileImageUrl(profileImageUrl)
                .title(feed.getTitle())
                .hashtags(hashtagNames)
                .location(feed.getLocation())
                .content(feed.getContent())
                .commentCount(feed.getCommentCount())
                .heartCount(feed.getHeartCount())
                .viewCount(feed.getViewCount())
                .isLiked(isLiked)
                .uploadCourseId(uploadCourse != null ? uploadCourse.getId() : null)
                .mediaList(mediaList)
                .build();
    }

    public FeedListResponse toListResponse(Page<Feed> feedPage) {
        List<FeedDetailResponse> responses = feedPage.getContent().stream()
            .map(this::toDetailResponse)
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
