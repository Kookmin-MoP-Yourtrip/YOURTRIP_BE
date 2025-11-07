package backend.yourtrip.domain.feed.mapper;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.Hashtag;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.user.entity.User;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FeedMapper {

    public static Feed toEntity(User user, FeedCreateRequest request) {
        return Feed.builder()
                .user(user)
                .title(request.title())
                .location(request.location())
                .contentUrl(request.contendUrl())
                //업로드 코스 내용 필요
                .build();
    }

    public static FeedDetailResponse toDetailResponse(Feed feed) {
        return FeedDetailResponse.builder()
                .feedId(feed.getId())
                .userId(feed.getUser().getId())
                .nickname(feed.getUser().getNickname())
                .profileImageUrl(feed.getUser().getProfileImageUrl())
                .title(feed.getTitle())
                .location(feed.getLocation())
                .contentUrl(feed.getContentUrl())
                .commentCount(feed.getCommentCount())
                .heartCount(feed.getHeartCount())
                //.uploadCourseId()
                .build();
    }
}
