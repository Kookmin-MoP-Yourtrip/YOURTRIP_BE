package backend.yourtrip.domain.mypage.controller;

import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import backend.yourtrip.domain.mypage.dto.request.MyFeedUpdateRequest;
import backend.yourtrip.domain.mypage.dto.response.MyFeedVisibilityResponse;
import backend.yourtrip.domain.mypage.service.MyFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * MyFeedController
 *
 * 마이페이지 - 내가 올린 피드 관리용 API
 * - 내 피드 목록 조회
 * - 내 피드 상세 조회
 * - 공개/비공개 토글
 * - 내용 수정
 * - 삭제
 */
@RestController
@RequestMapping("/api/mypage/myfeed")
@RequiredArgsConstructor
public class MyFeedController {

    private final MyFeedService myFeedService;

    // =========================================================
    // 1. 내가 올린 피드 리스트 조회
    // =========================================================
    @Operation(
        summary = "내가 올린 피드 리스트 조회",
        description = """
        ### 기능 설명
        - **현재 로그인한 사용자가 작성한 피드들만** 페이징 형태로 조회합니다.
        - 최신 순(생성일 내림차순)으로 정렬된 목록을 반환합니다.

        ### 제약조건
        - **로그인 필수** (Authorization: `Bearer {accessToken}`)
        - 쿼리 파라미터
          - `page` : 0 이상 정수 (기본값 0)
          - `size` : 1~50 사이 권장 (기본값 10)
        - 삭제된 피드나, 소프트 삭제된 사용자의 피드는 제외됩니다.

        ### 예외상황 / 에러코드
        - `USER_NOT_FOUND (404)`
          - 인증 정보가 없거나, 유저 엔티티를 찾을 수 없는 경우
        - `INVALID_PAGINATION (400)` (예: MyFeedErrorCode)
          - page < 0 또는 size <= 0 등 잘못된 페이징 파라미터

        ### 정상 응답 예시
        ```json
        {
          "page": 0,
          "size": 10,
          "totalElements": 23,
          "totalPages": 3,
          "feeds": [
            {
              "feedId": 12,
              "title": "제주도 한라산 등산 코스 후기",
              "thumbnailUrl": "https://yourtrip.s3.ap-northeast-2.amazonaws.com/feed/2025-11-20/abc123.png",
              "isPublic": true,
              "createdAt": "2025-11-20T10:00:00"
            },
            {
              "feedId": 11,
              "title": "부산 해운대 1박 2일 코스",
              "thumbnailUrl": null,
              "isPublic": false,
              "createdAt": "2025-11-19T15:30:00"
            }
          ]
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "내 피드 리스트 조회 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = FeedListResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "잘못된 페이징 파라미터",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "timestamp": "2025-11-20T10:05:00",
                      "code": "INVALID_PAGINATION",
                      "message": "page 또는 size 값이 올바르지 않습니다."
                    }
                    """
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자 정보 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "timestamp": "2025-11-20T10:06:00",
                      "code": "USER_NOT_FOUND",
                      "message": "사용자를 찾을 수 없습니다."
                    }
                    """
                )
            )
        )
    })
    @GetMapping
    public FeedListResponse getMyFeeds(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        return myFeedService.getMyFeeds(page, size);
    }

    // =========================================================
    // 2. 내가 올린 피드 상세 조회
    // =========================================================
    @Operation(
        summary = "내가 올린 피드 상세 보기",
        description = """
        ### 기능 설명
        - **현재 로그인한 사용자가 작성한 특정 피드**를 상세 조회합니다.
        - 제목, 본문, 첨부 이미지, 코스/장소 정보, 해시태그, 공개 여부 등을 모두 반환합니다.

        ### 제약조건
        - 로그인 필수 (Bearer Token)
        - `feedId`는 PathVariable 로 전달
        - 해당 피드의 작성자와 현재 로그인 사용자가 달라면 조회할 수 없습니다.

        ### 예외상황 / 에러코드
        - `FEED_NOT_FOUND (404)` (예: FeedErrorCode)
          - 해당 ID의 피드가 존재하지 않는 경우
        - `NOT_FEED_OWNER (403)` (예: MyFeedErrorCode)
          - 요청한 피드의 작성자가 현재 로그인한 유저가 아닌 경우
        - `USER_NOT_FOUND (404)`
          - 인증 정보 또는 유저 엔티티 없음

        ### 정상 응답 예시
        ```json
        {
          "feedId": 12,
          "title": "제주도 한라산 등산 코스 후기",
          "content": "왕복 6시간 코스로 다녀왔어요...",
          "thumbnailUrl": "https://yourtrip.s3.ap-northeast-2.amazonaws.com/feed/2025-11-20/abc123.png",
          "imageUrls": [
            "https://yourtrip.s3.ap-northeast-2.amazonaws.com/feed/2025-11-20/img1.png",
            "https://yourtrip.s3.ap-northeast-2.amazonaws.com/feed/2025-11-20/img2.png"
          ],
          "isPublic": true,
          "likeCount": 32,
          "commentCount": 5,
          "createdAt": "2025-11-20T10:00:00",
          "updatedAt": "2025-11-20T11:30:00"
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "내 피드 상세 조회 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = FeedDetailResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "해당 피드의 작성자가 아님",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "timestamp": "2025-11-20T10:10:00",
                      "code": "NOT_FEED_OWNER",
                      "message": "해당 피드에 접근할 권한이 없습니다."
                    }
                    """
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "피드 또는 사용자 정보 없음",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "피드 없음",
                        value = """
                        {
                          "timestamp": "2025-11-20T10:09:00",
                          "code": "FEED_NOT_FOUND",
                          "message": "해당 피드를 찾을 수 없습니다."
                        }
                        """
                    ),
                    @ExampleObject(
                        name = "사용자 없음",
                        value = """
                        {
                          "timestamp": "2025-11-20T10:09:30",
                          "code": "USER_NOT_FOUND",
                          "message": "사용자를 찾을 수 없습니다."
                        }
                        """
                    )
                }
            )
        )
    })
    @GetMapping("/{feedId}")
    public FeedDetailResponse getMyFeedDetail(@PathVariable Long feedId) {
        return myFeedService.getMyFeedDetail(feedId);
    }

    // =========================================================
    // 3. 피드 공개/비공개 설정 (토글)
    // =========================================================
    @Operation(
        summary = "피드 공개/비공개 설정",
        description = """
        ### 기능 설명
        - 내 피드의 **공개/비공개 상태를 토글**합니다.
        - 현재 상태가 공개(PUBLIC)라면 비공개(PRIVATE)로,  
          비공개라면 공개로 변경하고 결과를 반환합니다.

        ### 제약조건
        - 로그인 필수 (Bearer Token)
        - `feedId`는 PathVariable
        - 본인 피드만 수정 가능

        ### 예외상황 / 에러코드
        - `FEED_NOT_FOUND (404)`
          - 존재하지 않는 피드
        - `NOT_FEED_OWNER (403)`
          - 다른 사용자의 피드를 변경하려는 경우
        - `USER_NOT_FOUND (404)`
          - 인증 정보 또는 유저 없음

        ### 정상 응답 예시
        ```json
        {
          "feedId": 12,
          "isPublic": false
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "피드 공개/비공개 상태 변경 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = MyFeedVisibilityResponse.class),
                examples = @ExampleObject(
                    value = """
                    {
                      "feedId": 12,
                      "isPublic": true
                    }
                    """
                )
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "피드 소유자가 아님",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "timestamp": "2025-11-20T10:20:00",
                      "code": "NOT_FEED_OWNER",
                      "message": "해당 피드에 대한 수정 권한이 없습니다."
                    }
                    """
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "피드 또는 사용자 없음",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "피드 없음",
                        value = """
                        {
                          "timestamp": "2025-11-20T10:21:00",
                          "code": "FEED_NOT_FOUND",
                          "message": "해당 피드를 찾을 수 없습니다."
                        }
                        """
                    ),
                    @ExampleObject(
                        name = "사용자 없음",
                        value = """
                        {
                          "timestamp": "2025-11-20T10:21:30",
                          "code": "USER_NOT_FOUND",
                          "message": "사용자를 찾을 수 없습니다."
                        }
                        """
                    )
                }
            )
        )
    })
    @PatchMapping("/{feedId}/visibility")
    public MyFeedVisibilityResponse updateVisibility(@PathVariable Long feedId) {
        return myFeedService.toggleVisibility(feedId);
    }

    // =========================================================
    // 4. 피드 수정
    // =========================================================
    @Operation(
        summary = "내 피드 수정",
        description = """
        ### 기능 설명
        - 내가 작성한 피드의 **제목, 내용, 해시태그, 공개 여부 등**을 수정합니다.
        - 요청 본문은 `MyFeedUpdateRequest` DTO 형식을 따릅니다.

        ### 제약조건
        - 로그인 필수 (Bearer Token)
        - `feedId`는 PathVariable
        - 본인 피드만 수정 가능
        - 요청 필드 Validation:
          - 제목/내용 길이 제한, 빈 값 여부 등은 DTO의 Bean Validation(@NotBlank, @Size 등) 기준

        ### 예외상황 / 에러코드
        - `FEED_NOT_FOUND (404)`
          - 존재하지 않는 피드
        - `NOT_FEED_OWNER (403)`
          - 다른 사용자의 피드를 수정하려는 경우
        - `INVALID_REQUEST_FIELD (400)`
          - DTO Validation 실패 (길이 초과, 필수값 누락 등)
        - `USER_NOT_FOUND (404)`
          - 인증 정보 없음

        ### 요청 예시
        ```json
        {
          "title": "제주도 한라산 등산 후기 (수정본)",
          "content": "코스를 조금 더 자세히 정리해봤어요...",
          "isPublic": true,
          "hashtags": ["제주도", "등산", "한라산"]
        }
        ```

        ### 정상 응답 예시
        ```json
        {
          "feedId": 12,
          "title": "제주도 한라산 등산 후기 (수정본)",
          "content": "코스를 조금 더 자세히 정리해봤어요...",
          "thumbnailUrl": "https://yourtrip.s3.../feed/abc123.png",
          "imageUrls": [],
          "isPublic": true,
          "likeCount": 32,
          "commentCount": 5,
          "createdAt": "2025-11-20T10:00:00",
          "updatedAt": "2025-11-20T12:30:00"
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "내 피드 수정 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = FeedDetailResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "요청 필드 Validation 실패",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "timestamp": "2025-11-20T10:30:00",
                      "code": "INVALID_REQUEST_FIELD",
                      "message": "제목 또는 내용 형식이 올바르지 않습니다."
                    }
                    """
                )
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "피드 소유자가 아님",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "timestamp": "2025-11-20T10:31:00",
                      "code": "NOT_FEED_OWNER",
                      "message": "해당 피드에 대한 수정 권한이 없습니다."
                    }
                    """
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "피드 또는 사용자 없음",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "피드 없음",
                        value = """
                        {
                          "timestamp": "2025-11-20T10:32:00",
                          "code": "FEED_NOT_FOUND",
                          "message": "해당 피드를 찾을 수 없습니다."
                        }
                        """
                    ),
                    @ExampleObject(
                        name = "사용자 없음",
                        value = """
                        {
                          "timestamp": "2025-11-20T10:32:30",
                          "code": "USER_NOT_FOUND",
                          "message": "사용자를 찾을 수 없습니다."
                        }
                        """
                    )
                }
            )
        )
    })
    @PatchMapping("/{feedId}")
    public FeedDetailResponse updateMyFeed(
        @PathVariable Long feedId,
        @RequestBody @Valid MyFeedUpdateRequest request
    ) {
        return myFeedService.updateFeed(feedId, request);
    }

    // =========================================================
    // 5. 피드 삭제
    // =========================================================
    @Operation(
        summary = "내 피드 삭제",
        description = """
        ### 기능 설명
        - 내가 작성한 피드를 **삭제**합니다.
        - 구현에 따라 실제 삭제 또는 Soft Delete(플래그 처리)로 동작할 수 있습니다.

        ### 제약조건
        - 로그인 필수 (Bearer Token)
        - `feedId`는 PathVariable
        - 본인 피드만 삭제 가능

        ### 예외상황 / 에러코드
        - `FEED_NOT_FOUND (404)`
          - 이미 삭제되었거나 존재하지 않는 피드
        - `NOT_FEED_OWNER (403)`
          - 다른 사용자의 피드를 삭제하려는 경우
        - `USER_NOT_FOUND (404)`
          - 인증 정보 없음

        ### 정상 응답
        - 상태 코드 204 또는 200 (현재 구현은 void이므로 200 OK/204 No Content 중 하나로 응답)

        ### 에러 응답 예시
        - 피드 소유자가 아님
        ```json
        {
          "timestamp": "2025-11-20T10:40:00",
          "code": "NOT_FEED_OWNER",
          "message": "해당 피드를 삭제할 권한이 없습니다."
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "내 피드 삭제 성공(본문 없음)"
        ),
        @ApiResponse(
            responseCode = "403",
            description = "피드 소유자가 아님",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "timestamp": "2025-11-20T10:41:00",
                      "code": "NOT_FEED_OWNER",
                      "message": "해당 피드를 삭제할 권한이 없습니다."
                    }
                    """
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "피드 또는 사용자 없음",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "피드 없음",
                        value = """
                        {
                          "timestamp": "2025-11-20T10:42:00",
                          "code": "FEED_NOT_FOUND",
                          "message": "해당 피드를 찾을 수 없습니다."
                        }
                        """
                    ),
                    @ExampleObject(
                        name = "사용자 없음",
                        value = """
                        {
                          "timestamp": "2025-11-20T10:42:30",
                          "code": "USER_NOT_FOUND",
                          "message": "사용자를 찾을 수 없습니다."
                        }
                        """
                    )
                }
            )
        )
    })
    @DeleteMapping("/{feedId}")
    public void deleteMyFeed(@PathVariable Long feedId) {
        myFeedService.deleteFeed(feedId);
    }
}