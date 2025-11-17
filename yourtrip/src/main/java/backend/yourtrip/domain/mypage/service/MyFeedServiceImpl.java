package backend.yourtrip.domain.mypage.service;

import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.mapper.FeedMapper;
import backend.yourtrip.domain.feed.repository.FeedRepository;
import backend.yourtrip.domain.mypage.dto.request.MyFeedUpdateRequest;
import backend.yourtrip.domain.mypage.dto.response.MyFeedVisibilityResponse;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MyFeedErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyFeedServiceImpl implements MyFeedService {

    private final FeedRepository feedRepository;
    private final JwtTokenProvider jwtTokenProvider;

    private Long getCurrentUserId() {
        return jwtTokenProvider.getCurrentUserId();
    }

    private Feed getMyFeed(Long feedId) {
        Long userId = getCurrentUserId();

        Feed feed = feedRepository.findById(feedId)
            .orElseThrow(() -> new BusinessException(MyFeedErrorCode.MY_FEED_NOT_FOUND));

        if (!feed.getUser().getId().equals(userId)) {
            throw new BusinessException(MyFeedErrorCode.MY_FEED_FORBIDDEN);
        }

        return feed;
    }

    @Override
    @Transactional(readOnly = true)
    public FeedListResponse getMyFeeds(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Feed> feedPage = feedRepository.findByUser_Id(getCurrentUserId(), pageable);
        return FeedMapper.toListResponse(feedPage);
    }

    @Override
    @Transactional
    public FeedDetailResponse getMyFeedDetail(Long feedId) {
        Feed feed = getMyFeed(feedId);
        feed.increaseViewCount();
        return FeedMapper.toDetailResponse(feed);
    }

    @Override
    @Transactional
    public MyFeedVisibilityResponse toggleVisibility(Long feedId) {
        Feed feed = getMyFeed(feedId);
        feed.updateVisibility();
        return new MyFeedVisibilityResponse(
            feedId,
            feed.isPublic(),
            feed.isPublic() ? "피드가 공개되었습니다." : "피드가 비공개로 변경되었습니다."
        );
    }

    @Override
    @Transactional
    public FeedDetailResponse updateFeed(Long feedId, MyFeedUpdateRequest request) {
        Feed feed = getMyFeed(feedId);
        feed.updateFeed(request.title(), request.location(), request.contentUrl());
        return FeedMapper.toDetailResponse(feed);
    }

    @Override
    @Transactional
    public void deleteFeed(Long feedId) {
        Feed feed = getMyFeed(feedId);
        // soft delete
        feed.softDelete();
    }
}