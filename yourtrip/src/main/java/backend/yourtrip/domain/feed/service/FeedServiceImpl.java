package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCreateResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.Hashtag;
import backend.yourtrip.domain.feed.mapper.FeedMapper;
import backend.yourtrip.domain.feed.repository.FeedRepository;
import backend.yourtrip.domain.feed.repository.HashtagRepository;
import backend.yourtrip.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService{

    private final FeedRepository feedRepository;
    private final HashtagRepository hashtagRepository;
    private final UserService userService;

    @Override
    @Transactional
    public FeedCreateResponse saveFeed(FeedCreateRequest request) {
        Long userId = userService.getCurrentUserId();

        Feed feed = FeedMapper.toEntity(request);
        Feed savedFeed = feedRepository.save(feed);

        return new FeedCreateResponse(savedFeed.getId(), "피드 등록 완료");
    }

//    @Override
//    public FeedDetailResponse getFeedById(Long id) {
//
//    }
//
//    @Override
//    public FeedListResponse getAllFeeds() {
//
//    }
}
