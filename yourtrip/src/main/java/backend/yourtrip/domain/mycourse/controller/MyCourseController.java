package backend.yourtrip.domain.mycourse.controller;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/my-courses")
@Tag(name = "MyCourse API", description = "나의 코스 관련 API")
public class MyCourseController {

    private final MyCourseService myCourseService;

    // ==========================
    //  1. 나의 코스 생성
    // ==========================
    @Operation(
        summary = "나의 코스 생성",
        description = """
            ### 제약조건
            - 제목(title): 공백 불가
            - 여행지(location): 공백 불가
            - 시작일(startDate), 종료일(endDate): 공백 불가, `startDate ≤ endDate`
                
            ### ⚠ 예외상황
            - `INVALID_REQUEST_FIELD(400)`: 필드 유효성 오류(빈 값, 날짜 범위 오류 등)
                
            ### 예시 요청:
            ```json
            {
              "title": "개쩌는 경주 여행기",
              "location": "경주",
              "startDate": "2025-10-31",
              "endDate": "2025-11-02"
            }
            ```
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "코스 생성 성공",
            content = @Content(
                schema = @Schema(implementation = MyCourseCreateResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "myCourseId": 1,
                          "message": "코스 생성 완료"
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "잘못된 요청(유효성 실패/날짜 범위 오류), 아래는 날짜 범위 오류의 경우의 응답",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "날짜 범위 오류",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "startDate는 endDate보다 이후일 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "제목 미입력",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "title: 코스 제목은 필수 입력 항목입니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "여행지 미입력",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "location: 여행지는 필수 입력 항목입니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "startDate 미입력",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "startDate: 여행 기간은 필수 입력 항목입니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "endDate 미입력",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "endDate: 여행 기간은 필수 입력 항목입니다."
                            }
                            """
                    ),
                }
            )
        )
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MyCourseCreateResponse createMyCourse(
        @Valid @RequestBody MyCourseCreateRequest request) {
        return myCourseService.saveCourse(request);
    }

    @PostMapping("/{courseId}/{day}/places")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "나의 코스에 장소 추가 API")
    public PlaceCreateResponse createPlace(@Valid @RequestBody PlaceCreateRequest request,
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1", description = "일차 수로 3일짜리 여행일정이라면 1~3 사이의 값만 허용") int day) {
        return myCourseService.savePlace(courseId, day, request);
    }

    @GetMapping
    @Operation(summary = "나의 코스 목록 조회")
    public MyCourseListResponse getMyCourses() {
        return myCourseService.getMyCourseList();
    }

    @GetMapping("/{courseId}")
    @Operation(summary = "나의 코스 상세 조회 API")
    public MyCourseDetailResponse getMyCourseDetail(
        @PathVariable @Schema(example = "1") Long courseId) {
        return myCourseService.getMyCourseDetail(courseId);
    }

}
