package backend.yourtrip.domain.feed.controller;


import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.*;
import backend.yourtrip.domain.feed.entity.enums.FeedSortType;
import backend.yourtrip.domain.feed.service.FeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/feeds")
@Tag(name = "Feed API", description = "피드 관련 API")
public class FeedController {

    private final FeedService feedService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "피드 생성")
    public FeedCreateResponse createFeed(
            @Valid @RequestBody FeedCreateRequest request) {
        return feedService.saveFeed(request);
    }

    @GetMapping("{feedId}")
    @Operation(summary = "피드 단건 조회")
    public FeedDetailResponse getFeedDetail(
            @PathVariable Long feedId
    ) {
        return feedService.getFeedByFeedId(feedId);
    }

    @GetMapping
    @Operation(summary = "피드 전체 조회")
    public FeedListResponse getAllFeed(
            @RequestParam(defaultValue = "NEW") FeedSortType sortType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
            ) {
        return feedService.getFeedAll(page, size, sortType);
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "유저 별 피드 조회")
    public FeedListResponse getUserFeed(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return feedService.getFeedByUserId(userId, page, size);
    }

    @GetMapping("/search")
    @Operation(summary = "키워드 별 피드 조회")
    public FeedListResponse getKeywordFeed(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return feedService.getFeedByKeyword(keyword, page, size);
    }

    @PatchMapping("/{feedId}")
    @Operation(summary = "피드 수정")
    public FeedUpdateResponse updateFeed(
            @PathVariable Long feedId,
            @Valid @RequestBody FeedUpdateRequest request
            ) {
        return feedService.updateFeed(feedId, request);
    }

    @DeleteMapping("/{feedId}")
    @Operation(summary = "피드 삭제")
    public FeedDeleteResponse deleteFeed(
            @PathVariable Long feedId
    ) {
        return feedService.deleteFeed(feedId);
    }
}

