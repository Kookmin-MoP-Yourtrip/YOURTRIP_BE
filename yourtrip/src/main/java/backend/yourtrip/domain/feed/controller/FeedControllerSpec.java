package backend.yourtrip.domain.feed.controller;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedListResponse;
import backend.yourtrip.domain.feed.dto.response.FeedUpdateResponse;
import backend.yourtrip.domain.feed.entity.enums.FeedSortType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Feed API", description = "피드 관련 API")
public interface FeedControllerSpec {

    // ==========================
    //  피드 생성
    // ==========================
    @Operation(
            summary = "피드 생성",
            description = """
          ### 설명
          - 새로운 피드를 생성합니다.
          - 위치, 내용, 미디어 파일을 입력받습니다.
          - 미디어 파일은 최소 1개 이상 필수로 업로드해야 합니다.

          ### 제약조건
          - 위치(location): 선택
          - 내용(content): 선택
          - 해시태그(hashtags): 선택 (빈 배열 가능)
          - 업로드 코스 ID(uploadCourseId): 선택
          - 미디어 파일(mediaFiles): 필수, 최소 1개 이상
          - 지원 파일 형식: png, jpeg, jpg, webp, mp4, mov, webm

          ### FormData 전송 구조
          **중요: 반드시 아래 key 이름을 정확히 맞춰서 전송해야 합니다**

          ```javascript
          const formData = new FormData();

          // 1. mediaFiles: 이미지/영상 파일 (필수, 다중 가능)
          formData.append('mediaFiles', file1);  // File 객체
          formData.append('mediaFiles', file2);  // 여러 파일 추가 가능

          // 2. request: JSON 데이터 (Blob으로 변환 필요)
          const requestData = {
            title: "제주도 여행",
            location: "제주도",
            content: "정말 좋았어요!",
            hashtags: ["제주도", "여행", "바다"],
            uploadCourseId: 1  // 선택사항
          };

          formData.append('request', new Blob(
            [JSON.stringify(requestData)],
            { type: 'application/json' }
          ));

          // 3. 전송
          fetch('/api/feeds', {
            method: 'POST',
            body: formData
          });
          ```

          ### Swagger UI 사용법
          1. **mediaFiles**: "파일 선택" 버튼으로 이미지/영상 선택 (다중 선택 가능)
          2. **request**: 아래 JSON 형식으로 입력
             ```json
             {
               "title": "제주도 여행",
               "location": "제주도",
               "content": "정말 좋았어요!",
               "hashtags": ["제주도", "여행"],
               "uploadCourseId": 1
             }
             ```

          ### ⚠ 예외상황
          - `INVALID_REQUEST_FIELD(400)`: 필드 유효성 오류(필수값 누락, 파일 형식 오류 등)
          - `FAIL_UPLOAD_FILE(503)`: 파일 업로드 실패
          """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "피드 생성 성공",
                    content = @Content(
                            schema = @Schema(implementation = FeedCreateResponse.class),
                            examples = @ExampleObject(
                                    value = """
                      {
                        "feedId": 1,
                        "message": "피드가 성공적으로 생성되었습니다."
                      }
                      """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청(유효성 실패/파일 형식 오류)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "제목 미입력",
                                            value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "INVALID_REQUEST_FIELD",
                            "message": "title: 제목은 필수 입력 항목입니다."
                          }
                          """
                                    ),
                                    @ExampleObject(
                                            name = "위치 미입력",
                                            value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "INVALID_REQUEST_FIELD",
                            "message": "location: 위치는 필수 입력 항목입니다."
                          }
                          """
                                    ),
                                    @ExampleObject(
                                            name = "내용 미입력",
                                            value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "INVALID_REQUEST_FIELD",
                            "message": "content: 내용은 필수 입력 항목입니다."
                          }
                          """
                                    ),
                                    @ExampleObject(
                                            name = "미디어 파일 미업로드",
                                            value = """
                          {
                            "timestamp": "2025-11-21T10:00:00",
                            "code": "INVALID_REQUEST_FIELD",
                            "message": "최소 1개 이상의 미디어 파일을 업로드해야 합다."
                          }
                          """
                                    )
                            }
                    )
            )
    })
    FeedCreateResponse createFeed(
            List<MultipartFile> mediaFiles,
            FeedCreateRequest request
    );

    // ==========================
    //  피드 단건 조회
    // ==========================
    @Operation(
            summary = "피드 단건 조회",
            description = """
          ### 설명
          - 특정 피드의 상세 정보를 조회합니다.
          - 피드 단건 조회 시 해당 피드의 조회수(viewCount)가 +1 증가합니다.
          - 피드의 모든 정보(제목, 위치, 내용, 해시태그, 미디어 리스트, 좋아요 수, 댓글 수, 조회수 등)를 포함합니다.
          - mediaList: 피드에 업로드된 이미지/영상 목록 (mediaId, mediaUrl, mediaType, displayOrder 포함)
          - mediaType: IMAGE 또는 VIDEO
          - displayOrder: 미디어 표시 순서

          ### 제약조건
          - 경로 변수
              - 피드 ID(feedId): 존재하는 피드여야 함

          ### ⚠ 예외상황
          - `FEED_NOT_FOUND(404)`: 피드가 존재하지 않는 경우
          """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "피드 조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = FeedDetailResponse.class),
                            examples = @ExampleObject(
                                    value = """
                      {
                        "feedId": 1,
                        "userId": 1,
                        "nickname": "홍길동",
                        "profileImageUrl": "https://example.com/profile.jpg",
                        "title": "멋진 경주 여행",
                        "location": "경주",
                        "content": "정말 좋은 여행이었습니다!",
                        "hashtags": ["경주", "여행", "한식"],
                        "commentCount": 5,
                        "heartCount": 10,
                        "viewCount": 100,
                        "uploadCourseId": 1,
                        "mediaList": [
                          {
                            "mediaId": 1,
                            "mediaUrl": "https://yourtrip-bucket.s3.ap-northeast-2.amazonaws.com/feed-media/image1.jpg",
                            "mediaType": "IMAGE",
                            "displayOrder": 1
                          },
                          {
                            "mediaId": 2,
                            "mediaUrl": "https://yourtrip-bucket.s3.ap-northeast-2.amazonaws.com/feed-media/video1.mp4",
                            "mediaType": "VIDEO",
                            "displayOrder": 2
                          }
                        ]
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
    FeedDetailResponse getFeedDetail(@Schema(example = "1") Long feedId);

    // ==========================
    //  피드 전체 조회
    // ==========================
    @Operation(
            summary = "피드 전체 조회",
            description = """
          ### 설명
          - 모든 피드를 페이징하여 조회합니다.
          - 정렬 기준에 따라 최신순(NEW) 또는 인기순(POPULAR)으로 조회할 수 있습니다.
          - 각 피드는 FeedDetailResponse 형식으로 반환되며, mediaList를 포함합니다.

          ### 제약조건
          - 쿼리 파라미터
              - sortType: NEW(최신순) 또는 POPULAR(인기순), 기본값 NEW
              - page: 페이지 번호 (0부터 시작), 기본값 0
              - size: 페이지 크기, 기본값 10
          """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "피드 목록 조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = FeedListResponse.class),
                            examples = @ExampleObject(
                                    value = """
                      {
                        "feeds": [
                          {
                            "feedId": 1,
                            "userId": 1,
                            "nickname": "홍길동",
                            "profileImageUrl": "https://example.com/profile.jpg",
                            "title": "멋진 경주 여행",
                            "location": "경주",
                            "content": "정말 좋은 여행이었습니다!",
                            "hashtags": ["경주", "여행", "한식"],
                            "commentCount": 5,
                            "heartCount": 10,
                            "viewCount": 100,
                            "uploadCourseId": 1,
                            "mediaList": [
                              {
                                "mediaId": 1,
                                "mediaUrl": "https://yourtrip-bucket.s3.ap-northeast-2.amazonaws.com/feed-media/image1.jpg",
                                "mediaType": "IMAGE",
                                "displayOrder": 1
                              }
                            ]
                          }
                        ],
                        "currentPage": 0,
                        "totalPages": 5,
                        "totalElements": 50,
                        "hasNext": true,
                        "hasPrevious": false
                      }
                      """
                            )
                    )
            )
    })
    FeedListResponse getAllFeed(
            @Schema(description = "정렬 기준 (NEW: 최신순, POPULAR: 인기순)", example = "NEW") FeedSortType sortType,
            @Schema(description = "페이지 번호 (0부터 시작)", example = "0") int page,
            @Schema(description = "페이지 크기", example = "10") int size
    );

    // ==========================
    //  유저 별 피드 조회
    // ==========================
    @Operation(
            summary = "유저 별 피드 조회",
            description = """
          ### 설명
          - 특정 유저가 작성한 모든 피드를 페이징하여 조회합니다.
          - 각 피드는 FeedDetailResponse 형식으로 반환되며, mediaList를 포함합니다.

          ### 제약조건
          - 경로 변수
              - 유저 ID(userId): 존재하는 유저여야 함
          - 쿼리 파라미터
              - page: 페이지 번호 (0부터 시작), 기본값 0
              - size: 페이지 크기, 기본값 10

          ### ⚠ 예외상황
          - `USER_NOT_FOUND(404)`: 유저가 존재하지 않는 경우
          """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "유저 피드 목록 조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = FeedListResponse.class),
                            examples = @ExampleObject(
                                    value = """
                      {
                        "feeds": [
                          {
                            "feedId": 1,
                            "userId": 1,
                            "nickname": "홍길동",
                            "profileImageUrl": "https://example.com/profile.jpg",
                            "title": "멋진 경주 여행",
                            "location": "경주",
                            "content": "정말 좋은 여행이었습니다!",
                            "hashtags": ["경주", "여행", "한식"],
                            "commentCount": 5,
                            "heartCount": 10,
                            "viewCount": 100,
                            "uploadCourseId": 1,
                            "mediaList": [
                              {
                                "mediaId": 1,
                                "mediaUrl": "https://yourtrip-bucket.s3.ap-northeast-2.amazonaws.com/feed-media/image1.jpg",
                                "mediaType": "IMAGE",
                                "displayOrder": 1
                              }
                            ]
                          }
                        ],
                        "currentPage": 0,
                        "totalPages": 3,
                        "totalElements": 25,
                        "hasNext": true,
                        "hasPrevious": false
                      }
                      """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "유저를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                      {
                        "timestamp": "2025-11-21T10:00:00",
                        "code": "USER_NOT_FOUND",
                        "message": "유저를 찾을 수 없습니다."
                      }
                      """
                            )
                    )
            )
    })
    FeedListResponse getUserFeed(
            @Schema(example = "1") Long userId,
            @Schema(description = "페이지 번호 (0부터 시작)", example = "0") int page,
            @Schema(description = "페이지 크기", example = "10") int size
    );

    // ==========================
    //  키워드 별 피드 조회
    // ==========================
    @Operation(
            summary = "키워드 별 피드 조회",
            description = """
          ### 설명
          - 키워드로 피드를 검색합니다.
          - 제목, 내용, 해시태그에서 키워드를 검색합니다.
          - 각 피드는 FeedDetailResponse 형식으로 반환되며, mediaList를 포함합니다.

          ### 제약조건
          - 쿼리 파라미터
              - keyword: 검색 키워드 (필수)
              - page: 페이지 번호 (0부터 시작), 기본값 0
              - size: 페이지 크기, 기본값 10
          """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "키워드 검색 성공",
                    content = @Content(
                            schema = @Schema(implementation = FeedListResponse.class),
                            examples = @ExampleObject(
                                    value = """
                      {
                        "feeds": [
                          {
                            "feedId": 1,
                            "userId": 1,
                            "nickname": "홍길동",
                            "profileImageUrl": "https://example.com/profile.jpg",
                            "title": "멋진 경주 여행",
                            "location": "경주",
                            "content": "정말 좋은 여행이었습니다!",
                            "hashtags": ["경주", "여행", "한식"],
                            "commentCount": 5,
                            "heartCount": 10,
                            "viewCount": 100,
                            "uploadCourseId": 1,
                            "mediaList": [
                              {
                                "mediaId": 1,
                                "mediaUrl": "https://yourtrip-bucket.s3.ap-northeast-2.amazonaws.com/feed-media/image1.jpg",
                                "mediaType": "IMAGE",
                                "displayOrder": 1
                              }
                            ]
                          }
                        ],
                        "currentPage": 0,
                        "totalPages": 2,
                        "totalElements": 15,
                        "hasNext": true,
                        "hasPrevious": false
                      }
                      """
                            )
                    )
            )
    })
    FeedListResponse getKeywordFeed(
            @Schema(description = "검색 키워드", example = "경주") String keyword,
            @Schema(description = "페이지 번호 (0부터 시작)", example = "0") int page,
            @Schema(description = "페이지 크기", example = "10") int size
    );

    // ==========================
    //  피드 수정
    // ==========================
    @Operation(
            summary = "피드 수정",
            description = """
          ### 설명
          - 기존 피드를 수정합니다.
          - 제목, 위치, 내용, 해시태그, 미디어 파일을 수정할 수 있습니다.

          ### 제약조건
          - 경로 변수
              - 피드 ID(feedId): 존재하는 피드여야 함
          - 요청 값
              - mediaFiles: 선택, 새로운 파일을 업로드하면 기존 미디어가 모두 삭제 되고 새로운 미디어로 교체됩니다.
              - mediaFiles를 보내지 않으면 기존 미디어가 유지됩니다.

          ### FormData 전송 구조
          **중요: 반드시 아래 key 이름을 정확히 맞춰서 전송해야 합니다**

          ```javascript
          const formData = new FormData();

          // 1. mediaFiles: 이미지/영상 파일 (선택사항)
          // 주의: 새 파일을 추가하면 기존 미디어가 모두 삭제되고 교체됩니다
          // 주의: 미디어를 유지하려면 mediaFiles를 전송하지 마세요
          formData.append('mediaFiles', newFile1);
          formData.append('mediaFiles', newFile2);

          // 2. request: JSON 데이터 (필수)
          const requestData = {
            title: "수정된 제목",
            location: "수정된 위치",
            content: "수정된 내용",
            hashtags: ["새태그"],
            uploadCourseId: null  // null 가능
          };

          formData.append('request', new Blob(
            [JSON.stringify(requestData)],
            { type: 'application/json' }
          ));

          // 3. 전송
          fetch('/api/feeds/{feedId}', {
            method: 'PUT',
            body: formData
          });
          ```

          ### Swagger UI 사용법
          1. **mediaFiles**: 새 미디어로 교체하려면 파일 선택 (선택사항)
             - 선택하지 않으면 기존 미디어 유지
             - 선택하면 기존 미디어 전체 삭제 후 새 미디어로 교체
          2. **request**: 수정할 정보를 JSON 형식으로 입력
             ```json
             {
               "title": "수정된 제목",
               "location": "수정된 위치",
               "content": "수정된 내용",
               "hashtags": ["새태그"],
               "uploadCourseId": 1
             }
             ```

          ### ⚠ 예외상황
          - `FEED_NOT_FOUND(404)`: 피드가 존재하지 않는 경우
          - `FEED_UPDATE_NOT_AUTHORIZED(403)`: 본인의 피드가 아닌 경우 수정 불가
          - `UPLOAD_COURSE_NOT_FOUND(404)`: 업로드 코스를 찾을 수 없음
          - `UPLOAD_COURSE_FORBIDDEN(403)`: 업로드 코스 권한 없음
          - `FAIL_UPLOAD_FILE(503)`: 파일 업로드 실패
          """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "피드 수정 성공",
                    content = @Content(
                            schema = @Schema(implementation = FeedUpdateResponse.class),
                            examples = @ExampleObject(
                                    value = """
                      {
                        "feedId": 1,
                        "message": "피드가 성공적으로 수정되었습니다."
                      }
                      """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                      {
                        "timestamp": "2025-11-21T10:00:00",
                        "code": "INVALID_REQUEST_FIELD",
                        "message": "필드 유효성 오류"
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
    FeedUpdateResponse updateFeed(
            @Schema(example = "1") Long feedId,
            List<MultipartFile> mediaFiles,
            FeedUpdateRequest request
    );

    // ==========================
    //  피드 삭제
    // ==========================
    @Operation(
            summary = "피드 삭제",
            description = """
              ### 설명
              - 특정 피드를 삭제합니다.
              - 피드와 관련된 모든 데이터(미디어, 댓글, 좋아요 등)가 함께 삭제됩니다.

              ### 제약조건
              - 경로 변수
                  - 피드 ID(feedId): 존재하는 피드여야 함

              ### ⚠ 예외상황
              - `FEED_NOT_FOUND(404)`: 피드가 존재하지 않는 경우
              - `UNAUTHORIZED(403)`: 본인의 피드가 아닌 경우 삭제 불가
              """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "피드 삭제 성공"
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
    void deleteFeed(@Schema(example = "1") Long feedId);
}
