package backend.yourtrip.domain.mypage.controller;

import backend.yourtrip.domain.mypage.dto.response.LikedCourseResponse;
import backend.yourtrip.domain.mypage.dto.response.LikedFeedResponse;
import backend.yourtrip.domain.mypage.service.MyPageLikedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/mypage/liked")
@RequiredArgsConstructor
public class MyPageLikedController {

    private final MyPageLikedService myPageLikedService;

    // =========================================================
    // 1. 좋아요한 코스 목록 조회
    // =========================================================
    @Operation(
        summary = "좋아요한 코스 목록 조회",
        description = """
        ### 기능 설명
        - 로그인한 사용자가 **좋아요(Heart)** 한 업로드 코스 리스트를 조회합니다.
        - 반환 정보:
          - 업로드 코스 ID(uploadCourseId)
          - 제목(title)
          - 소개(introduction)
          - 대표 썸네일(thumbnailImage)
          - 키워드 목록(keywords)

        ### 제약조건
        - **로그인 필수**: Authorization 헤더에 `Bearer {AccessToken}` 필요

        ### 예외상황 / 에러코드
        - `USER_NOT_FOUND (404)`
          - 유효하지 않은 사용자거나, AccessToken이 잘못된 경우

        ### 정상 응답 예시
        ```json
        [
          {
            "uploadCourseId": 12,
            "title": "제주 힐링 당일치기 코스",
            "introduction": "한라산, 협재, 애월을 하루에!",
            "thumbnailImage": "https://yourtrip.s3.ap.../thumb_12.png",
            "keywords": ["힐링", "제주도", "여행"]
          }
        ]
        ```

        ### 에러 응답 예시
        ```json
        {
          "timestamp": "2025-11-22T15:00:00",
          "code": "USER_NOT_FOUND",
          "message": "사용자를 찾을 수 없습니다."
        }
        ```

        ### 테스트 방법
        1. Authorization → `Bearer {AccessToken}` 설정
        2. GET `/api/mypage/liked/courses`
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "좋아요한 코스 리스트 정상 반환",
            content = @Content(
                mediaType = "application/json",
                array = @ArraySchema(schema = @Schema(implementation = LikedCourseResponse.class))
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "timestamp": "2025-11-22T15:00:00",
                      "code": "USER_NOT_FOUND",
                      "message": "사용자를 찾을 수 없습니다."
                    }
                    """
                )
            )
        )
    })
    @GetMapping("/courses")
    public List<LikedCourseResponse> getLikedCourses() {
        return myPageLikedService.getLikedCourses();
    }


    // =========================================================
    // 2. 좋아요한 피드 목록 조회
    // =========================================================
    @Operation(
        summary = "좋아요한 피드 목록 조회",
        description = """
        ### 기능 설명
        - 로그인한 사용자가 좋아요한 **피드 목록**을 조회합니다.
        - 반환 정보:
          - feedId
          - 제목(title)
          - 위치(location)
          - 대표 이미지(contentUrl)
          - 좋아요 수 / 댓글 수

        ### 제약조건
        - **로그인 필수**

        ### 예외상황 / 에러코드
        - `USER_NOT_FOUND (404)`
          - 인증 정보가 없거나 잘못됨

        ### 정상 응답 예시
        ```json
        [
          {
            "feedId": 5,
            "title": "부산 해운대 야경",
            "location": "부산 해운대구",
            "contentUrl": "https://yourtrip.../feed_5.jpg",
            "heartCount": 18,
            "commentCount": 2
          }
        ]
        ```

        ### 에러 응답 예시
        ```json
        {
          "timestamp": "2025-11-22T15:40:00",
          "code": "USER_NOT_FOUND",
          "message": "사용자를 찾을 수 없습니다."
        }
        ```

        ### 테스트 방법
        1. Authorization → Bearer {AccessToken}
        2. GET `/api/mypage/liked/feeds`
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "좋아요한 피드 리스트 정상 반환",
            content = @Content(
                mediaType = "application/json",
                array = @ArraySchema(schema = @Schema(implementation = LikedFeedResponse.class))
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "timestamp": "2025-11-22T15:40:00",
                      "code": "USER_NOT_FOUND",
                      "message": "사용자를 찾을 수 없습니다."
                    }
                    """
                )
            )
        )
    })
    @GetMapping("/feeds")
    public List<LikedFeedResponse> getLikedFeeds() {
        return myPageLikedService.getLikedFeeds();
    }



    // =========================================================
    // 3. 코스 가져오기(Fork)
    // =========================================================
    @Operation(
        summary = "좋아요한 코스 가져오기(Fork)",
        description = """
        ### 기능 설명
        - 사용자가 좋아요한 업로드 코스를 자신의 MyCourse로 **복사(Fork)** 합니다.
        - 복사되는 정보:
          - 제목(title)
          - 위치(location)

        ### 제약조건
        - 로그인 필수
        - 같은 코스를 여러 번 fork 해도 허용 (중복 허용)

        ### 예외상황 / 에러코드
        - `COURSE_NOT_FOUND (404)`  
          → 해당 업로드 코스가 삭제되었거나 존재하지 않는 경우
        - `FORK_FAILED (500)`  
          → DB 저장 실패 등 내부 오류

        ### 정상 응답
        - 200 OK (본문 없음)

        ### 에러 응답 예시
        - 코스 없음
        ```json
        {
          "timestamp": "2025-11-22T16:00:00",
          "code": "COURSE_NOT_FOUND",
          "message": "업로드된 코스를 찾을 수 없습니다."
        }
        ```

        - 저장 실패
        ```json
        {
          "timestamp": "2025-11-22T16:01:00",
          "code": "FORK_FAILED",
          "message": "코스 가져오기에 실패했습니다."
        }
        ```

        ### 테스트 방법
        1. GET `/api/mypage/liked/courses` 로 uploadCourseId 확인
        2. POST `/api/mypage/liked/courses/{uploadCourseId}/fork`
        3. GET `/api/mycourse` 등에서 복사된 코스 존재 여부 확인
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Fork 성공 (본문 없음)"),
        @ApiResponse(
            responseCode = "404",
            description = "코스 없음",
            content = @Content(
                examples = @ExampleObject("""
                {
                  "timestamp": "2025-11-22T16:00:00",
                  "code": "COURSE_NOT_FOUND",
                  "message": "업로드된 코스를 찾을 수 없습니다."
                }
                """)
            )
        ),
        @ApiResponse(
            responseCode = "500",
            description = "저장 실패",
            content = @Content(
                examples = @ExampleObject("""
                {
                  "timestamp": "2025-11-22T16:01:00",
                  "code": "FORK_FAILED",
                  "message": "코스 가져오기에 실패했습니다."
                }
                """)
            )
        )
    })
    @PostMapping("/courses/{uploadCourseId}/fork")
    public void forkCourse(@PathVariable Long uploadCourseId) {
        myPageLikedService.forkCourse(uploadCourseId);
    }

}