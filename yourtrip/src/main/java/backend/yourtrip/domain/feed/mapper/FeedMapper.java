package backend.yourtrip.domain.feed.mapper;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.user.entity.User;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FeedMapper {

    public static Feed toEntity(User user, FeedCreateRequest request) {
        return Feed.builder()
            .user(user)
            .title(request.title())
            .location(request.location())
            .contentUrl(request.contentUrl())
            //TODO: 업로드 코스 내용 필요
            .build();
    }

    public static FeedDetailResponse toDetailResponse(Feed feed) {

        List<String> hashtagNames = feed.getHashtags().stream()
            .map(hashtag -> hashtag.getTagName())
            .collect(Collectors.toList());

        return FeedDetailResponse.builder()
            .feedId(feed.getId())
            .userId(feed.getUser().getId())
            .nickname(feed.getUser().getNickname())
            .profileImageUrl(feed.getUser().getProfileImageS3Key())
            .title(feed.getTitle())
            .hashtags(hashtagNames)
            .location(feed.getLocation())
            .contentUrl(feed.getContentUrl())
            .commentCount(feed.getCommentCount())
            .heartCount(feed.getHeartCount())
            //TODO: 업로드 코스 내용 필요
            .build();
    }
}
