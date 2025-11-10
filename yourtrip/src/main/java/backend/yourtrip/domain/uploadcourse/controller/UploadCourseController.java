package backend.yourtrip.domain.uploadcourse.controller;

import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
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
                schema = @Schema(implementation = PlaceCreateResponse.class),
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
            - keywords: 필수(빈 배열 허용). **키워드 목록 조회 API**로 받은 `code` 값만 배열에 담아 전송 (예: `"WALK"`, `"FOOD"`)
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
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "myCourseId: 나의 코스 id 연동은 필수 입니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "title: 코스 업로드 시 제목은 필수 입력입니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "keywords: 키워드 목록은 필수 입력값입니다. 선택된 키워드가 없을 시 빈 배열을 반환해주세요"
                            }
                            """
                    ),
                    @ExampleObject(
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
                mediaType = "application/json",
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

    @GetMapping("/{uploadCourseId}")
    @Operation(summary = "업로드 코스 상세 조회")
    public UploadCourseDetailResponse getUploadCourseDetail(
        @PathVariable @Schema(example = "1") Long uploadCourseId) {
        return uploadCourseService.getDetail(uploadCourseId);
    }

    @GetMapping
    @Operation(summary = "업로드 코스 전체 조회(인기순 or 최신순 정렬)", description = "인기순으로 정렬 시 쿼리 파라미터(sortType)으로 POPULAR를 넘겨주고, 최신 순으로 정렬 시 NEW를 넘겨줍니다 (디폴트는 인기순입니다)")
    public UploadCourseListResponse getAllUploadCourses(
        @RequestParam(defaultValue = "POPULAR") UploadCourseSortType sortType
    ) {
        return uploadCourseService.getAllList(sortType);
    }


}
