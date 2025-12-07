package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.*;
import backend.yourtrip.domain.feed.entity.enums.FeedSortType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


public interface FeedService {
    FeedCreateResponse saveFeed(FeedCreateRequest request, List<MultipartFile> mediaFiles);
    FeedDetailResponse getFeedByFeedId(Long id);

    FeedListResponse getFeedAll(int page, int size, FeedSortType sortType);
    FeedListResponse getFeedByUserId(Long id, int page, int size);
    FeedListResponse getFeedByKeyword(String keyword, int page, int size);

    FeedUpdateResponse updateFeed(Long feedId, FeedUpdateRequest request, List<MultipartFile> mediaFiles);

    void deleteFeed(Long feedId);

    FeedLikeResponse toggleLike(Long feedId);
}
