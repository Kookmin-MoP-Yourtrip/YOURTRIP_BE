package backend.yourtrip.domain.uploadcourse.controller;

import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseCreateResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListResponse;
import backend.yourtrip.domain.uploadcourse.entity.enums.UploadCourseSortType;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "UploadCourse API", description = "업로드 코스 관련 api")
@RequestMapping("/api/upload-courses")
public class UploadCourseController {

    private final UploadCourseService uploadCourseService;

    // ==========================
    // 1. 코스 키워드 목록 조회
    // ==========================
    @GetMapping("/keywords")
    @Operation(
        summary = "코스 키워드 목록 조회",
        description = """
              코스 업로드 시 선택할 수 있는 키워드 목록을 보여줍니다.
              
              ### 참고사항
              - travelMode: 이동수단
              - companionType: 동행유형
              - mood: 여행 분위기
              - duration: 여행 기간
              - budget: 예산
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "업로드 코스 키워드 목록 조회",
            content = @Content(
                schema = @Schema(implementation = CourseKeywordListResponse.class),
                examples = @ExampleObject(
                    value = """
                            {
                              "travelMode": [
                                { "code": "WALK", "label": "뚜벅이" },
                                { "code": "CAR", "label": "자차" }
                              ],
                              "companionType": [
                                { "code": "SOLO", "label": "혼자" },
                                { "code": "COUPLE", "label": "연인" },
                                { "code": "FRIEND", "label": "친구" },
                                { "code": "FAMILY", "label": "가족" }
                              ],
                              "mood": [
                                { "code": "FOOD", "label": "맛집탐방" },
                                { "code": "HEALING", "label": "힐링" },
                                { "code": "ACTIVITY", "label": "액티비티" },
                                { "code": "SENTIMENTAL", "label": "감성" },
                                { "code": "CULTURE", "label": "문화·전시" },
                                { "code": "NATURE", "label": "자연" },
                                { "code": "SHOPPING", "label": "쇼핑" }
                              ],
                              "duration": [
                                { "code": "ONE_DAY", "label": "하루" },
                                { "code": "TWO_DAYS", "label": "1박2일" },
                                { "code": "WEEKEND", "label": "주말" },
                                { "code": "LONG_TERM", "label": "장기" }
                              ],
                              "budget": [
                                { "code": "COST_EFFECTIVE", "label": "가성비" },
                                { "code": "NORMAL", "label": "보통" },
                                { "code": "PREMIUM", "label": "프리미엄" }
                              ]
                            }
                        """
                )
            )
        )
    })
    public CourseKeywordListResponse getCourseKeywordList() {
        return uploadCourseService.getCourseKeywordList();
    }

    // TODO: 멀티파트 데이터 입력으로 변경 (썸네일 이미지 고려)
    // ==========================
    //  2. 코스 업로드
    // ==========================
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "코스 업로드",
        description = """
            ### 제약조건
            - myCourseId: 필수, 존재하는 '나의 코스'와 연동
            - title: 공백 불가
            - keywords: 필수(빈 배열 허용). **키워드 목록 조회 API**로 받은 `code` 값만 배열에 담아 전송 (예: `"WALK"`, `"FOOD"`), 선택된 키워드가 없다면 빈 배열 반환
            - thumbnailImage: 선택, `.jpg/.png`만 허용 — *추후 multipart 예정*

            ### ⚠ 예외상황
            - `INVALID_REQUEST_FIELD(400)`: 필드 유효성 실패(빈 값, 포맷 불일치, 잘못된 키워드 코드 등)
            - `COURSE_NOT_FOUND(404)`: myCourseId에 해당하는 코스가 없음
            - `COURSE_ALREADY_UPLOAD(400)`: 이미 코스가 업로드된 경우
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "업로드 성공",
            content = @Content(
                schema = @Schema(implementation = UploadCourseCreateResponse.class),
                examples = @ExampleObject(
                    value = """
                            {
                              "uploadCourseId": 5,
                              "message": "코스 업로드 완료"
                            }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "잘못된 요청(유효성 실패/형식 오류)",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "myCourseId 미입력",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "myCourseId: 나의 코스 id 연동은 필수 입니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "업로드 코스 제목 미입력",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "title: 코스 업로드 시 제목은 필수 입력입니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "키워드 미입력",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "keywords: 키워드 목록은 필수 입력값입니다. 선택된 키워드가 없다면 빈 배열을 반환해주세요"
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "유효하지 않은 키워드 입력",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "유효하지 않은 키워드 코드가 포함되어 있습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "이미 업로드된 코스",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "COURSE_ALREADY_UPLOAD",
                              "message": "이미 업로드된 코스입니다."
                            }
                            """
                    ),
                }
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "존재하지 않는 myCourseId가 주어짐",
            content = @Content(
                examples = @ExampleObject(
                    value = """
                        {
                          "timestamp": "2025-11-10T11:00:00",
                          "code": "COURSE_NOT_FOUND",
                          "message": "코스를 찾을 수 없습니다."
                        }
                        """
                )
            )
        )
    })
    public UploadCourseCreateResponse courseUpload(
        @Valid @RequestBody UploadCourseCreateRequest request) {
        return uploadCourseService.createUploadCourse(request);
    }

    // ==========================
    //  3. 업로드 코스 상세 조회
    // ==========================
    @GetMapping("/{uploadCourseId}")
    @Operation(
        summary = "업로드 코스 상세 조회",
        description = """
            ### 제약조건
            - 경로 변수
                - 업로드 코스 ID(uploadCourseId): 존재하는 코스여야 함
            ### 에외 상황
            - `UPLOAD_COURSE_NOT_FOUND(404)`: 업로드 코스가 존재하지 않는 경우 (잘못된 uploadCourseId가 주어진 경우)
            """)
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "업로드 코스 상세 조회 성공",
            content = @Content(
                schema = @Schema(implementation = UploadCourseDetailResponse.class),
                examples = @ExampleObject(
                    value = """
                            {
                              "uploadCourseId": 1,
                              "title": "개쩌는 경주 여행기",
                              "location": "경주",
                              "introduction": "술과 음식을 좋아하는 분들 안성맞춤 코스",
                              "thumbnailImageUrl": "example.png",
                              "keywords": [
                                {
                                  "label": "뚜벅이",
                                  "code": "WALK"
                                },
                                {
                                  "label": "맛집탐방",
                                  "code": "FOOD"
                                },
                                {
                                  "label": "힐링",
                                  "code": "HEALING"
                                }
                              ],
                              "heartCount": 0,
                              "commentCount": 0,
                              "viewCount": 1,
                              "createdAt": "2025-11-11T01:33:02.47324",
                              "writerId": 1,
                              "writerNickname": "string",
                              "writerProfileUrl": null,
                              "daySchedules": [
                                {
                                  "dayScheduleId": 2,
                                  "day": 2,
                                  "places": []
                                },
                                {
                                  "dayScheduleId": 1,
                                  "day": 1,
                                  "places": []
                                },
                                {
                                  "dayScheduleId": 3,
                                  "day": 3,
                                  "places": []
                                }
                              ]
                            }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "존재하지 않는 uploadCourseId가 경로변수로 주어졌을 때",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                        {
                          "code": "UPLOAD_COURSE_NOT_FOUND",
                          "timestamp": "2025-11-11T00:00:44.7553392",
                          "message": "업로드 코스를 찾을 수 없습니다."
                        }
                        """
                )
            )
        )
    })
    public UploadCourseDetailResponse getUploadCourseDetail(
        @PathVariable @Schema(example = "1") Long uploadCourseId) {
        return uploadCourseService.getDetail(uploadCourseId);
    }

    // ==========================
    //  4. 업로드 코스 목록 조회
    // ==========================
    @GetMapping
    @Operation(
        summary = "업로드 코스 목록 조회(최신순/인기순 정렬)",
        description = """
            ### 제약조건
            - 경로 변수
                - 업로드 코스 ID(uploadCourseId): 존재하는 코스여야 함
            - 쿼리 파라미터
                - NEW(최신순): 업로드 코스 목록이 최신순으로 정렬되어 주어집니다.
                - POPULAR(인기순): 업로드 코스 목록이 인기순으로 정렬되어 주어집니다. 인기순은 조회순 기반입니다.
                아무 파라미터를 넘겨주지 않으면 인기순으로 정렬됩니다.
            ### 에외 상황
            - `INVALID_REQUEST_FIELD(400)`: 잘못된 정렬 기준이 주어진 경우(POPULAR, NEW 외의 값)
            """)
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "업로드 코스 목록 조회 성공",
            content = @Content(
                schema = @Schema(implementation = UploadCourseListResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "uploadCourses": [
                            {
                              "uploadCourseId": 2,
                              "title": "개쩌는 경주 여행기",
                              "location": "경주",
                              "thumbnailImageUrl": "example.png",
                              "heartCount": 0,
                              "commentCount": 0,
                              "viewCount": 0,
                              "writerId": 1,
                              "writerNickname": "string",
                              "writerProfileUrl": null,
                              "createdAt": "2025-11-11T01:56:56.170613"
                            },
                            {
                              "uploadCourseId": 1,
                              "title": "개쩌는 경주 여행기",
                              "location": "경주",
                              "thumbnailImageUrl": "example.png",
                              "heartCount": 0,
                              "commentCount": 0,
                              "viewCount": 1,
                              "writerId": 1,
                              "writerNickname": "string",
                              "writerProfileUrl": null,
                              "createdAt": "2025-11-11T01:56:47.614249"
                            }
                          ]
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "잘못된 정렬 기준이 주어진 경우 (POPULAR, NEW 외의 값)",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                        {
                          "code": "INVALID_REQUEST_FIELD",
                          "timestamp": "2025-11-11T00:00:44.7553392",
                          "message": "요청 값을 바인딩할 수 없습니다."
                        }
                        """
                )
            )
        )
    })
    public UploadCourseListResponse getAllUploadCourses(
        @RequestParam(defaultValue = "POPULAR") UploadCourseSortType sortType
    ) {
        return uploadCourseService.getAllList(sortType);
    }


}
