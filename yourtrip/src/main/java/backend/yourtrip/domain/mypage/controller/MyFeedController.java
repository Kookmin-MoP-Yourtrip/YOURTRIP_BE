package backend.yourtrip.domain.mypage.controller;

import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import backend.yourtrip.domain.mypage.dto.request.MyFeedUpdateRequest;
import backend.yourtrip.domain.mypage.dto.response.MyFeedVisibilityResponse;
import backend.yourtrip.domain.mypage.service.MyFeedService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mypage/myfeed")
@RequiredArgsConstructor
public class MyFeedController {

    private final MyFeedService myFeedService;

    // 1. 내가 올린 피드 리스트 조회
    @GetMapping
    @Operation(summary = "내가 올린 피드 리스트 조회")
    public FeedListResponse getMyFeeds(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        return myFeedService.getMyFeeds(page, size);
    }

    // 2. 내가 올린 피드 상세 조회
    @GetMapping("/{feedId}")
    @Operation(summary = "내가 올린 피드 상세 보기")
    public FeedDetailResponse getMyFeedDetail(@PathVariable Long feedId) {
        return myFeedService.getMyFeedDetail(feedId);
    }

    // 3. 피드 공개/비공개 설정
    @PatchMapping("/{feedId}/visibility")
    @Operation(summary = "피드 공개/비공개 설정")
    public MyFeedVisibilityResponse updateVisibility(@PathVariable Long feedId) {
        return myFeedService.toggleVisibility(feedId);
    }

    // 4. 피드 수정
    @PatchMapping("/{feedId}")
    @Operation(summary = "내 피드 수정")
    public FeedDetailResponse updateMyFeed(
        @PathVariable Long feedId,
        @RequestBody @Valid MyFeedUpdateRequest request
    ) {
        return myFeedService.updateFeed(feedId, request);
    }

    // 5. 피드 삭제
    @DeleteMapping("/{feedId}")
    @Operation(summary = "내 피드 삭제")
    public void deleteMyFeed(@PathVariable Long feedId) {
        myFeedService.deleteFeed(feedId);
    }
}