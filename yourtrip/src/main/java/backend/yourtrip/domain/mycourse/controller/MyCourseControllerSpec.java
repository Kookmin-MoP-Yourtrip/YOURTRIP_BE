package backend.yourtrip.domain.mycourse.controller;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;

public interface MyCourseControllerSpec {
    @Operation(
        summary = "나의 코스 생성",
        description = """
            ### 제약조건
            - 제목(title): 공백 불가
            - 여행지(location): 공백 불가
            - 시작일(startDate), 종료일(endDate): 공백 불가, `startDate ≤ endDate`
                
            ### ⚠ 예외상황
            - `INVALID_REQUEST_FIELD(400)`: 필드 유효성 오류(빈 값, 날짜 범위 오류 등)
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
    MyCourseCreateResponse createMyCourse(MyCourseCreateRequest request);
}
