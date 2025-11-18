package backend.yourtrip.domain.feed.controller;


import backend.yourtrip.domain.feed.dto.request.FeedCommentCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedCommentUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentUpdateResponse;
import backend.yourtrip.domain.feed.service.FeedCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/feeds")
@Tag(name = "Feed Comment API", description = "피드 댓글 관련 API")
public class FeedCommentController {

    private final FeedCommentService feedCommentService;

    @PostMapping("/{feedId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "댓글 생성")
    public FeedCommentCreateResponse createComment(
            @PathVariable Long feedId,
            @Valid @RequestBody FeedCommentCreateRequest request
    ) {
        return feedCommentService.saveComment(feedId, request);
    }

    @GetMapping("/{feedId}/comments")
    @Operation(summary = "피드별 댓글 조회")
    public FeedCommentListResponse getCommentsByFeed(
            @PathVariable Long feedId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return feedCommentService.getCommentsByFeedId(feedId, page, size);
    }

    @PutMapping("/comments/{commentId}")
    @Operation(summary = "댓글 수정")
    public FeedCommentUpdateResponse updateComment(
            @PathVariable Long commentId,
            @Valid @RequestBody FeedCommentUpdateRequest request
    ) {
        return feedCommentService.updateComment(commentId, request);
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "댓글 삭제")
    public void deleteComment(@PathVariable Long commentId) {
        feedCommentService.deleteComment(commentId);
    }
}