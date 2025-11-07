package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.Hashtag;
import backend.yourtrip.domain.feed.mapper.FeedMapper;
import backend.yourtrip.domain.feed.repository.FeedRepository;
import backend.yourtrip.domain.feed.repository.HashtagRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService{

    private final FeedRepository feedRepository;
    private final HashtagRepository hashtagRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    @Override
    @Transactional
    public FeedCreateResponse saveFeed(FeedCreateRequest request) {
        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        Feed feed = FeedMapper.toEntity(user, request);
        Feed savedFeed = feedRepository.save(feed);

        for (String hashtag : request.hashtags()) {
            hashtagRepository.save(new Hashtag(feed, hashtag));
        }

        return new FeedCreateResponse(savedFeed.getId(), "피드 등록 완료");
    }

    @Override
    public FeedDetailResponse getFeedById(Long feedId) {
        Feed feed = feedRepository.findFeedWithHashtag(feedId);

        return FeedMapper.toDetailResponse(feed);
    }
//
//    @Override
//    public FeedListResponse getAllFeeds() {
//
//    }
}
