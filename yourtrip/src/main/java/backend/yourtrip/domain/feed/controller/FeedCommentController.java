package backend.yourtrip.domain.feed.controller;

import backend.yourtrip.domain.feed.dto.request.FeedCommentRequest;
import backend.yourtrip.domain.feed.dto.request.FeedCommentUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentResponse;
import backend.yourtrip.domain.feed.service.FeedCommentService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/feeds/{feedId}/comments")
public class FeedCommentController {

    private final FeedCommentService feedCommentService;

    // =========================================================
    // 1. 댓글 생성
    // =========================================================
    @Operation(summary = "댓글 작성")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public FeedCommentResponse createComment(
        @PathVariable Long feedId,
        @Valid @RequestBody FeedCommentRequest request
    ) {
        return feedCommentService.createComment(feedId, request);
    }

    // =========================================================
    // 2. 댓글 리스트 조회 (페이징)
    // =========================================================
    @Operation(summary = "댓글 리스트 조회")
    @GetMapping
    public FeedCommentListResponse getComments(
        @PathVariable Long feedId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        return feedCommentService.getComments(feedId, page, size);
    }

    // =========================================================
    // 3. 댓글 단건 조회
    // =========================================================
    @Operation(summary = "댓글 단건 조회")
    @GetMapping("/{commentId}")
    public FeedCommentResponse getComment(
        @PathVariable Long feedId,
        @PathVariable Long commentId
    ) {
        return feedCommentService.getComment(feedId, commentId);
    }

    // =========================================================
    // 4. 댓글 수정
    // =========================================================
    @Operation(summary = "댓글 수정")
    @PatchMapping("/{commentId}")
    public FeedCommentResponse updateComment(
        @PathVariable Long feedId,
        @PathVariable Long commentId,
        @Valid @RequestBody FeedCommentUpdateRequest request
    ) {
        return feedCommentService.updateComment(feedId, commentId, request);
    }

    // =========================================================
    // 5. 댓글 삭제
    // =========================================================
    @Operation(summary = "댓글 삭제")
    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(
        @PathVariable Long feedId,
        @PathVariable Long commentId
    ) {
        feedCommentService.deleteComment(feedId, commentId);
    }
}