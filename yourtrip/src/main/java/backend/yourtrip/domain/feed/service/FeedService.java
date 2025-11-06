package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;


public interface FeedService {
    FeedCreateResponse saveFeed(FeedCreateRequest request);
//    FeedDetailResponse getFeedById(Long id);
//    FeedListResponse getAllFeeds();
}
