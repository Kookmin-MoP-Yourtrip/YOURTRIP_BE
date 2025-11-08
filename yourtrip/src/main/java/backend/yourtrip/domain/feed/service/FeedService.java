package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;


public interface FeedService {
    FeedCreateResponse saveFeed(FeedCreateRequest request);
    FeedDetailResponse getFeedByFeedId(Long id);
    //TODO: 피드 전체 조회 기능
    //TODO: 피드 유저별 조회 기능
}
