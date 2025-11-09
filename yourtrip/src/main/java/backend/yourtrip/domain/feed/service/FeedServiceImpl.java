package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.Hashtag;
import backend.yourtrip.domain.feed.mapper.FeedMapper;
import backend.yourtrip.domain.feed.repository.FeedRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.FeedErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService{

    private final FeedRepository feedRepository;
    private final UserService userService;

    @Override
    @Transactional
    public FeedCreateResponse saveFeed(FeedCreateRequest request) {
        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        Feed feed = FeedMapper.toEntity(user, request);
        Feed savedFeed = feedRepository.save(feed);

        for (String hashtag : request.hashtags()) {
            Hashtag tagName = Hashtag.builder()
                    .feed(feed)
                    .tagName(hashtag)
                    .build();
            feed.getHashtags().add(tagName);
        }

        return new FeedCreateResponse(savedFeed.getId(), "피드 등록 완료");
    }

    @Override
    @Transactional(readOnly = true)
    public FeedDetailResponse getFeedByFeedId(Long feedId) {
        Feed feed = feedRepository.findFeedWithHashtag(feedId)
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));
        return FeedMapper.toDetailResponse(feed);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedListResponse getFeedAll() {
        List<Feed> feeds = feedRepository.findAllFeedWithHashtag();

        return FeedMapper.toListResponse(feeds);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedListResponse getFeedByUserId(Long userId) {
        userService.getUser(userId);
        List<Feed> feeds = feedRepository.findFeedByUserIdWithHashtag(userId);

        return FeedMapper.toListResponse(feeds);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedListResponse getFeedByKeyword(String keyword) {
        List<Feed> feeds = feedRepository.findFeedByKeywordWithHashtag(keyword);

        return FeedMapper.toListResponse(feeds);
    }
}
