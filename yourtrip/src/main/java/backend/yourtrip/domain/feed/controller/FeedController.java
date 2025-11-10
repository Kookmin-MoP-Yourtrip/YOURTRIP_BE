package backend.yourtrip.domain.feed.controller;


import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import backend.yourtrip.domain.feed.service.FeedService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/feeds")
public class FeedController {

    private final FeedService feedService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedCreateResponse createFeed(
            @Valid @RequestBody FeedCreateRequest request) {
        return feedService.saveFeed(request);
    }

    @GetMapping("{feedId}")
    public FeedDetailResponse getFeedDetail(
            @PathVariable Long feedId
    ) {
        return feedService.getFeedByFeedId(feedId);
    }

    @GetMapping
    public FeedListResponse getAllFeed(
            @ParameterObject
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
            ) {
        return feedService.getFeedAll(pageable);
    }

    @GetMapping("/users/{userId}")
    public FeedListResponse getUserFeed(
            @PathVariable Long userId,
            @ParameterObject
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return feedService.getFeedByUserId(userId, pageable);
    }

    @GetMapping("/search")
    public FeedListResponse getKeywordFeed(
            @RequestParam String keyword,
            @ParameterObject
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return feedService.getFeedByKeyword(keyword, pageable);
    }
}

