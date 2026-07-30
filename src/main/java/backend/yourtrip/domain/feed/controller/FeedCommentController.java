package backend.yourtrip.domain.feed.controller;


import backend.yourtrip.domain.feed.dto.request.FeedCommentCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedCommentUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentUpdateResponse;
import backend.yourtrip.domain.feed.service.FeedCommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/feeds")
public class FeedCommentController implements FeedCommentControllerSpec {

    private final FeedCommentService feedCommentService;

    // ==========================
    //  댓글 생성
    // ==========================
    @Override
    @PostMapping("/{feedId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public FeedCommentCreateResponse createComment(
            @PathVariable Long feedId,
            @Valid @RequestBody FeedCommentCreateRequest request
    ) {
        return feedCommentService.saveComment(feedId, request);
    }

    // ==========================
    //  피드별 댓글 조회
    // ==========================
    @Override
    @GetMapping("/{feedId}/comments")
    public FeedCommentListResponse getCommentsByFeed(
            @PathVariable Long feedId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return feedCommentService.getCommentsByFeedId(feedId, page, size);
    }

    // ==========================
    //  댓글 수정
    // ==========================
    @Override
    @PutMapping("/comments/{commentId}")
    public FeedCommentUpdateResponse updateComment(
            @PathVariable Long commentId,
            @Valid @RequestBody FeedCommentUpdateRequest request
    ) {
        return feedCommentService.updateComment(commentId, request);
    }

    // ==========================
    //  댓글 삭제
    // ==========================
    @Override
    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@PathVariable Long commentId) {
        feedCommentService.deleteComment(commentId);
    }
}