package backend.yourtrip.domain.feed.controller;

import backend.yourtrip.domain.feed.dto.request.FeedCommentCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedCommentUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentUpdateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Feed Comment API", description = "피드 댓글 관련 API")
public interface FeedCommentControllerSpec {

    // ==========================
    //  댓글 생성
    // ==========================
    @Operation(
            summary = "피드 댓글 생성",
            description = """
              ### 설명
              - 특정 피드에 새로운 댓글을 작성합니다.
              - 댓글 내용(sentence)을 입력받아 댓글을 등록합니다.

              ### 제약조건
              - 경로 변수
                  - 피드 ID(feedId): 존재하는 피드여야 함
              - 요청 값
                  - 댓글 내용(sentence): 필수 입력, 공백만 입력 불가

              ### ⚠ 예외상황
              - `COMMENT_SENTENCE_REQUIRED(400)`: 댓글 내용이 비어있거나 공백만 있는 경우
              - `FEED_NOT_FOUND(404)`: 피드가 존재하지 않는 경우
              """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "댓글 생성 성공",
                    content = @Content(
                            schema = @Schema(implementation = FeedCommentCreateResponse.class),
                            examples = @ExampleObject(
                                    value = """
                          {
                            "feedCommentId": 1,
                            "message": "댓글이 성공적으로 생성되었습니다."
                          }
                          """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "댓글 내용 미입력",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "COMMENT_SENTENCE_REQUIRED",
                            "message": "댓글 입력은 필수입니다."
                          }
                          """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "피드를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "FEED_NOT_FOUND",
                            "message": "피드를 찾을 수 없습니다."
                          }
                          """
                            )
                    )
            )
    })
    FeedCommentCreateResponse createComment(
            @Schema(example = "1") Long feedId,
            FeedCommentCreateRequest request
    );

    // ==========================
    //  피드별 댓글 조회
    // ==========================
    @Operation(
            summary = "피드별 댓글 목록 조회",
            description = """
              ### 설명
              - 특정 피드의 모든 댓글을 페이징하여 조회합니다.
              - 댓글은 최신순(생성일시 기준 내림차순)으로 정렬됩니다.
              - 각 댓글에는 작성자 정보(userId, nickname, profileImageUrl)가 포함됩니다.

              ### 제약조건
              - 경로 변수
                  - 피드 ID(feedId): 존재하는 피드여야 함
              - 쿼리 파라미터
                  - page: 페이지 번호 (0부터 시작), 기본값 0
                  - size: 페이지 크기, 기본값 10

              ### ⚠ 예외상황
              - `FEED_NOT_FOUND(404)`: 피드가 존재하지 않는 경우
              """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "댓글 목록 조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = FeedCommentListResponse.class),
                            examples = @ExampleObject(
                                    value = """
                          {
                            "comments": [
                              {
                                "feedCommentId": 1,
                                "feedId": 1,
                                "userId": 1,
                                "nickname": "홍길동",
                                "profileImageUrl": "https://example.com/profile.jpg",
                                "sentence": "정말 멋진 여행이네요!",
                                "createdAt": "2025-11-21T10:00:00",
                                "updatedAt": "2025-11-21T10:00:00"
                              },
                              {
                                "feedCommentId": 2,
                                "feedId": 1,
                                "userId": 2,
                                "nickname": "김철수",
                                "profileImageUrl": "https://example.com/profile2.jpg",
                                "sentence": "저도 가보고 싶어요!",
                                "createdAt": "2025-11-21T09:30:00",
                                "updatedAt": "2025-11-21T09:30:00"
                              }
                            ],
                            "totalPages": 3,
                            "totalElements": 25,
                            "currentPage": 0,
                            "pageSize": 10
                          }
                          """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "피드를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "FEED_NOT_FOUND",
                            "message": "피드를 찾을 수 없습니다."
                          }
                          """
                            )
                    )
            )
    })
    FeedCommentListResponse getCommentsByFeed(
            @Schema(example = "1") Long feedId,
            @Schema(description = "페이지 번호 (0부터 시작)", example = "0") int page,
            @Schema(description = "페이지 크기", example = "10") int size
    );

    // ==========================
    //  댓글 수정
    // ==========================
    @Operation(
            summary = "댓글 수정",
            description = """
              ### 설명
              - 기존 댓글의 내용을 수정합니다.
              - 본인이 작성한 댓글만 수정할 수 있습니다.

              ### 제약조건
              - 경로 변수
                  - 댓글 ID(commentId): 존재하는 댓글이어야 함
              - 요청 값
                  - 댓글 내용(sentence): 필수 입력, 공백만 입력 불가

              ### ⚠ 예외상황
              - `COMMENT_SENTENCE_REQUIRED(400)`: 댓글 내용이 비어있거나 공백만 있는 경우
              - `COMMENT_UPDATE_NOT_AUTHORIZED(403)`: 본인의 댓글이 아닌 경우 수정 불가
              - `COMMENT_NOT_FOUND(404)`: 댓글이 존재하지 않는 경우
              """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "댓글 수정 성공",
                    content = @Content(
                            schema = @Schema(implementation = FeedCommentUpdateResponse.class),
                            examples = @ExampleObject(
                                    value = """
                          {
                            "commentFeedId": 1,
                            "message": "댓글이 성공적으로 수정되었습니다."
                          }
                          """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "댓글 내용 미입력",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "COMMENT_SENTENCE_REQUIRED",
                            "message": "댓글 입력은 필수입니다."
                          }
                          """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "댓글 수정 권한 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "COMMENT_UPDATE_NOT_AUTHORIZED",
                            "message": "댓글 수정 권한이 없습니다."
                          }
                          """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "댓글을 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "COMMENT_NOT_FOUND",
                            "message": "댓글을 찾을 수 없습니다."
                          }
                          """
                            )
                    )
            )
    })
    FeedCommentUpdateResponse updateComment(
            @Schema(example = "1") Long commentId,
            FeedCommentUpdateRequest request
    );

    // ==========================
    //  댓글 삭제
    // ==========================
    @Operation(
            summary = "댓글 삭제",
            description = """
              ### 설명
              - 특정 댓글을 삭제합니다.
              - 본인이 작성한 댓글만 삭제할 수 있습니다.
              - 삭제된 댓글은 소프트 삭제(deleted 플래그)되며, 해당 피드의 댓글 카운트가 1 감소합니다.

              ### 제약조건
              - 경로 변수
                  - 댓글 ID(commentId): 존재하는 댓글이어야 함

              ### ⚠ 예외상황
              - `COMMENT_DELETE_NOT_AUTHORIZED(403)`: 본인의 댓글이 아닌 경우 삭제 불가
              - `COMMENT_NOT_FOUND(404)`: 댓글이 존재하지 않는 경우
              """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "댓글 삭제 성공"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "댓글 삭제 권한 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "COMMENT_DELETE_NOT_AUTHORIZED",
                            "message": "댓글 삭제 권한이 없습니다."
                          }
                          """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "댓글을 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "COMMENT_NOT_FOUND",
                            "message": "댓글을 찾을 수 없습니다."
                          }
                          """
                            )
                    )
            )
    })
    void deleteComment(@Schema(example = "1") Long commentId);
}
