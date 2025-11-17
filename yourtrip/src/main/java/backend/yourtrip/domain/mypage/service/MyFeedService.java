package backend.yourtrip.domain.mypage.service;

import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import backend.yourtrip.domain.mypage.dto.request.MyFeedUpdateRequest;
import backend.yourtrip.domain.mypage.dto.response.MyFeedVisibilityResponse;

public interface MyFeedService {

    FeedListResponse getMyFeeds(int page, int size);
    FeedDetailResponse getMyFeedDetail(Long feedId);
    MyFeedVisibilityResponse toggleVisibility(Long feedId);
    FeedDetailResponse updateFeed(Long feedId, MyFeedUpdateRequest request);
    void deleteFeed(Long feedId);
}