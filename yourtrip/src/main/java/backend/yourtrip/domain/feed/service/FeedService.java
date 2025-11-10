package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import org.springframework.data.domain.Pageable;


public interface FeedService {
    FeedCreateResponse saveFeed(FeedCreateRequest request);
    FeedDetailResponse getFeedByFeedId(Long id);

    FeedListResponse getFeedAll(Pageable pageable);
    FeedListResponse getFeedByUserId(Long id, Pageable pageable);
    FeedListResponse getFeedByKeyword(String keyword, Pageable pageable);
}
