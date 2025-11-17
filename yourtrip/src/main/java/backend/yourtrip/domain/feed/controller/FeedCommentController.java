package backend.yourtrip.domain.feed.controller;

import backend.yourtrip.domain.feed.dto.request.FeedCommentRequest;
import backend.yourtrip.domain.feed.dto.request.FeedCommentUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentResponse;
import backend.yourtrip.domain.feed.service.FeedCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * FeedCommentController
 *
 * 피드 댓글 CRUD API
 * - 댓글 작성
 * - 댓글 조회(리스트/단건)
 * - 댓글 수정
 * - 댓글 삭제
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/feeds/{feedId}/comments")
public class FeedCommentController {

    private final FeedCommentService feedCommentService;

    // =========================================================
    // 1. 댓글 생성
    // =========================================================
    @Operation(
        summary = "댓글 작성",
        description = """
        ### 기능 설명
        - 특정 피드에 댓글을 작성합니다.
        - 작성자는 **현재 로그인된 사용자**로 자동 설정됩니다.

        ### 제약조건
        - 로그인 필수
        - `feedId`는 PathVariable로 전달
        - 본문은 `FeedCommentRequest` 형식 (content 필수)

        ### 요청 예시
        ```json
        {
          "content": "사진 너무 예뻐요!"
        }
        ```

        ### 정상 응답 예시
        ```json
        {
          "commentId": 15,
          "userId": 3,
          "nickname": "여행러버",
          "content": "사진 너무 예뻐요!",
          "createdAt": "2025-11-22 14:10"
        }
        ```

        ### 예외 상황
        - `FEED_NOT_FOUND` : 해당 피드가 존재하지 않을 때
        - `USER_NOT_FOUND` : 유저 정보 없음
        - `INVALID_COMMENT_CONTENT` : 댓글 내용 공백
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "댓글 작성 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = FeedCommentResponse.class)
            )
        )
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedCommentResponse createComment(
        @PathVariable Long feedId,
        @Valid @RequestBody FeedCommentRequest request
    ) {
        return feedCommentService.createComment(feedId, request);
    }

    // =========================================================
    // 2. 댓글 리스트 조회
    // =========================================================
    @Operation(
        summary = "댓글 리스트 조회",
        description = """
        ### 기능 설명
        - 특정 피드의 댓글 목록을 페이징 형태로 조회합니다.
        - 기본 정렬은 **작성일 오름차순(ASC)** 입니다.

        ### 제약조건
        - 로그인 불필요 (공개 피드는 누구나 조회 가능)
        - `page` 기본값: 0
        - `size` 기본값: 10

        ### 정상 응답 예시
        ```json
        {
          "comments": [
            {
              "commentId": 10,
              "userId": 3,
              "nickname": "여행덕후",
              "content": "사진 예쁘네요!",
              "createdAt": "2025-11-18 18:10"
            },
            {
              "commentId": 11,
              "userId": 4,
              "nickname": "유럽여행러",
              "content": "저도 가보고 싶어요!",
              "createdAt": "2025-11-18 18:12"
            }
          ],
          "currentPage": 0,
          "totalPages": 2,
          "totalElements": 15,
          "hasNext": true,
          "hasPrevious": false
        }
        ```

        ### 예외 상황
        - `FEED_NOT_FOUND` : 피드가 삭제되었거나 존재하지 않음
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "댓글 리스트 조회 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = FeedCommentListResponse.class)
            )
        )
    })
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
    @Operation(
        summary = "댓글 단건 조회",
        description = """
        ### 기능 설명
        - 특정 피드의 특정 댓글을 단건 조회합니다.

        ### 제약조건
        - 누구나 조회 가능

        ### 정상 응답 예시
        ```json
        {
          "commentId": 10,
          "userId": 3,
          "nickname": "여행자",
          "content": "정말 아름답네요!",
          "createdAt": "2025-11-22 18:10"
        }
        ```

        ### 예외 상황
        - `FEED_NOT_FOUND` : 피드 또는 댓글이 존재하지 않을 때
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "댓글 단건 조회 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = FeedCommentResponse.class)
            )
        )
    })
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
    @Operation(
        summary = "댓글 수정",
        description = """
        ### 기능 설명
        - 내가 작성한 댓글만 수정할 수 있습니다.
        - 본문은 `FeedCommentUpdateRequest` 형식

        ### 제약조건
        - 로그인 필수
        - 댓글 작성자 본인만 수정 가능

        ### 요청 예시
        ```json
        {
          "content": "내용 조금 수정했어요!"
        }
        ```

        ### 정상 응답 예시
        ```json
        {
          "commentId": 15,
          "userId": 3,
          "nickname": "여행러버",
          "content": "내용 조금 수정했어요!",
          "createdAt": "2025-11-22 14:10"
        }
        ```

        ### 예외 상황
        - `FEED_NOT_FOUND` : 피드 없음 / 댓글 없음
        - `FORBIDDEN` : 본인 댓글이 아닌 경우
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "댓글 수정 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = FeedCommentResponse.class)
            )
        )
    })
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
    @Operation(
        summary = "댓글 삭제",
        description = """
        ### 기능 설명
        - 내가 작성한 댓글을 삭제합니다. (Soft Delete 방식)

        ### 제약조건
        - 로그인 필수
        - 댓글 작성자 본인만 삭제 가능

        ### 정상 응답
        - 상태 코드 **204 No Content**

        ### 예외 상황
        - `FEED_NOT_FOUND` : 댓글 또는 피드 없음
        - `FORBIDDEN` : 본인 댓글이 아닐 때
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "204",
            description = "댓글 삭제 성공"
        )
    })
    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(
        @PathVariable Long feedId,
        @PathVariable Long commentId
    ) {
        feedCommentService.deleteComment(feedId, commentId);
    }
}