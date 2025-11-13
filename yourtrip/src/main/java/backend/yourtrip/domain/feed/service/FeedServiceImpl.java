package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.Hashtag;
import backend.yourtrip.domain.feed.entity.enums.FeedSortType;
import backend.yourtrip.domain.feed.mapper.FeedMapper;
import backend.yourtrip.domain.feed.repository.FeedRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.FeedErrorCode;
import backend.yourtrip.global.exception.errorCode.FeedResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

        return new FeedCreateResponse(savedFeed.getId(), FeedResponseCode.FEED_CREATED.getMessage());
    }

    @Override
    @Transactional
    public FeedDetailResponse getFeedByFeedId(Long feedId) {
        Feed feed = feedRepository.findFeedWithHashtag(feedId)
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        feed.increaseViewCount();

        return FeedMapper.toDetailResponse(feed);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedListResponse getFeedAll(int page, int size, FeedSortType sortType) {
        Sort sort = createSort(sortType);
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Feed> feedPage = feedRepository.findAll(pageable);
        return FeedMapper.toListResponse(feedPage);
    }

    private Sort createSort(FeedSortType sortType) {
        return switch (sortType) {
            case NEW -> Sort.by(Sort.Direction.DESC, "createdAt");
            case POPULAR -> Sort.by(Sort.Direction.DESC, "viewCount");
        };
    }

    @Override
    @Transactional(readOnly = true)
    public FeedListResponse getFeedByUserId(Long userId, int page, int size) {
        userService.getUser(userId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Feed> feedPage = feedRepository.findByUser_Id(userId, pageable);

        return FeedMapper.toListResponse(feedPage);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedListResponse getFeedByKeyword(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Feed> feedPage = feedRepository.findByKeyword(keyword, pageable);

        return FeedMapper.toListResponse(feedPage);
    }
}
