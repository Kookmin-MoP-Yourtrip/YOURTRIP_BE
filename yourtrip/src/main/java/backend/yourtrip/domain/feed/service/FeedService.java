package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.*;
import backend.yourtrip.domain.feed.entity.enums.FeedSortType;


public interface FeedService {
    FeedCreateResponse saveFeed(FeedCreateRequest request);
    FeedDetailResponse getFeedByFeedId(Long id);

    FeedListResponse getFeedAll(int page, int size, FeedSortType sortType);
    FeedListResponse getFeedByUserId(Long id, int page, int size);
    FeedListResponse getFeedByKeyword(String keyword, int page, int size);

    FeedUpdateResponse updateFeed(Long feedId, FeedUpdateRequest request);

    void deleteFeed(Long feedId);
}
