package backend.yourtrip.domain.feed.controller;


import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.*;
import backend.yourtrip.domain.feed.entity.enums.FeedSortType;
import backend.yourtrip.domain.feed.service.FeedService;
import backend.yourtrip.domain.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/feeds")
public class FeedController implements FeedControllerSpec{

    private final FeedService feedService;
    private final UserService userService;

    // ==========================
    //  피드 생성
    // ==========================
    @Override
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FeedCreateResponse createFeed(
            @RequestPart(value = "mediaFiles", required = false) List<MultipartFile> mediaFiles,
            @Valid @RequestPart(value = "request") FeedCreateRequest request) {
        return feedService.saveFeed(request, mediaFiles);
    }

    // ==========================
    //  피드 단건 조회
    // ==========================
    @Override
    @GetMapping("/{feedId}")
    public FeedDetailResponse getFeedDetail(@PathVariable Long feedId) {
        return feedService.getFeedByFeedId(feedId);
    }

    // ==========================
    //  피드 전체 조회
    // ==========================
    @Override
    @GetMapping
    public FeedListResponse getAllFeed(
            @RequestParam(defaultValue = "NEW") FeedSortType sortType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
            ) {
        return feedService.getFeedAll(page, size, sortType);
    }

    // ==========================
    //  유저 별 피드 조회
    // ==========================
    @Override
    @GetMapping("/users/{userId}")
    public FeedListResponse getUserFeed(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return feedService.getFeedByUserId(userId, page, size);
    }

    // ==========================
    //  키워드 별 피드 조회
    // ==========================
    @Override
    @GetMapping("/search")
    public FeedListResponse getKeywordFeed(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return feedService.getFeedByKeyword(keyword, page, size);
    }

    // ==========================
    //  피드 수정
    // ==========================
    @Override
    @PutMapping(value = "/{feedId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FeedUpdateResponse updateFeed(
            @PathVariable Long feedId,
            @RequestPart(value = "mediaFiles", required = false) List<MultipartFile> mediaFiles,
            @Valid @RequestPart(value = "request") FeedUpdateRequest request) {
        return feedService.updateFeed(feedId, request, mediaFiles);
    }

    // ==========================
    //  피드 삭제
    // ==========================
    @Override
    @DeleteMapping("/{feedId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFeed(@PathVariable Long feedId) {
        feedService.deleteFeed(feedId);
    }

    // ==========================
    //  피드 좋아요 토글
    // ==========================
    @PostMapping("/{feedId}/like")
    public FeedLikeResponse toggleFeedLike(@PathVariable Long feedId) {
        Long userId = userService.getCurrentUserId();
        return feedService.toggleLike(feedId, userId);
    }
}

