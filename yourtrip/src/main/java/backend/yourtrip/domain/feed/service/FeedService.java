package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.*;
import backend.yourtrip.domain.feed.entity.enums.FeedSortType;
import org.springframework.data.domain.Pageable;


public interface FeedService {
    FeedCreateResponse saveFeed(FeedCreateRequest request);
    FeedDetailResponse getFeedByFeedId(Long id);

    FeedListResponse getFeedAll(int page, int size, FeedSortType sortType);
    FeedListResponse getFeedByUserId(Long id, int page, int size);
    FeedListResponse getFeedByKeyword(String keyword, int page, int size);

    FeedUpdateResponse updateFeed(Long feedId, FeedUpdateRequest request);

    FeedDeleteResponse deleteFeed(Long feedId);
}
