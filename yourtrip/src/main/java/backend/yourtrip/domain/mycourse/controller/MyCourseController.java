package backend.yourtrip.domain.mycourse.controller;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
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

    // ==========================
    //  2. 나의 코스에 장소 추가
    // ==========================
    @Operation(
        summary = "나의 코스에 장소 추가",
        description = """
            ### 제약조건
            - 경로 변수
                - 코스 ID(courseId): 존재하는 코스여야 함
                - 일차(day): 코스 기간 내 정수 (예: 3일 일정이면 1~3)
            - 요청 값
                - 장소명(placeName): 공백 불가
                - 시작 시간(startTime): 공백 불가, `HH:mm` 형식 (시/분 2자리)

            ### ⚠ 예외상황
            - `INVALID_REQUEST_FIELD(400)`: 필드 유효성 오류(빈 값, 포맷 불일치 등)
            - `COURSE_NOT_FOUND(404)`: 코스가 존재하지 않는 경우 (잘못된 courseId가 주어진 경우)
                    
            ### 참고사항
            - 장소 URL(placeUrl): 지도로 바로 연결되는 카카오 or 구글 장소 url
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "장소 등록 성공",
            content = @Content(
                schema = @Schema(implementation = PlaceCreateResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "placeId": 1,
                          "message": "장소 등록 완료"
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "필수값 미입력 시",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "장소명 미입력",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "placeName: 장소 이름은 필수 입력 항목입니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "시작 시간 미입력",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "startTime: 시작 시간은 필수 입력 항목입니다."
                            }
                            """
                    )
                }
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "경로변수로 올바르지 않은 day 혹은 courseId가 주어졌을 때",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                        {
                          "timestamp": "2025-11-10T11:00:00",
                          "code": "COURSE_OR_DAY_NOT_FOUND",
                          "message": "등록되지 않은 course 혹은 day입니다."
                        }
                        """
                )
            )
        )
    })
    @PostMapping("/{courseId}/{day}/places")
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceCreateResponse createPlace(@Valid @RequestBody PlaceCreateRequest request,
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") int day) {
        return myCourseService.savePlace(courseId, day, request);
    }

    // ==========================
    //  일차별 장소 리스트 조회
    // ==========================
    @GetMapping("/{courseId}/{day}")
    public DayScheduleResponse getDaySchedule(
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") int day) {
        return myCourseService.getPlaceListByDay(courseId, day);
    }

    // ==========================
    //  3. 나의 코스 목록 조회
    // ==========================
    @GetMapping
    @Operation(summary = "나의 코스 목록 조회")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "나의 코스 목록 조회 성공",
            content = @Content(
                schema = @Schema(implementation = MyCourseListResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "myCourses": [
                            {
                              "title": "개쩌는 호주 여행기",
                              "location": "호주",
                              "startDate": "2025-10-31",
                              "endDate": "2025-11-02",
                              "memberCount": 1
                            },
                            {
                              "title": "개쩌는 경주 여행기",
                              "location": "경주",
                              "startDate": "2025-10-31",
                              "endDate": "2025-11-02",
                              "memberCount": 1
                            }
                          ]
                        """
                )
            )
        )
    })
    public MyCourseListResponse getMyCourses() {
        return myCourseService.getMyCourseList();
    }


    // ==========================
    //  4. 나의 코스 상세 조회
    // ==========================
    @GetMapping("/{courseId}")
    @Operation(
        summary = "나의 코스 상세 조회",
        description = """
            ### 제약조건
            - 경로 변수
                - 코스 ID(courseId): 존재하는 코스여야 함
            ### 예외 상황
            - `COURSE_NOT_FOUND(404)`: 코스가 존재하지 않는 경우 (잘못된 courseId가 주어진 경우)
                        
            ### 참고사항
            - 응답 값
                - memberCount: 코스 편집 인원 수 (공동 편집이 아니라면 디폴트값 1)
                - role: 코스 최초 생성자는 OWNER, 초대받은 참여자는 PARTICIPANT, OWNER인 사람만 코스 초대 버튼과 업로드 버튼이 보여야함
            """)
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "나의 코스 상세 조회 성공",
            content = @Content(
                schema = @Schema(implementation = MyCourseDetailResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "courseId": 1,
                          "title": "개쩌는 경주 여행기",
                          "location": "경주",
                          "memberCount": 1,
                          "startDate": "2025-10-31",
                          "endDate": "2025-11-02",
                          "role": "OWNER",
                          "updatedAt": "2025-11-10T23:53:31.560798",
                          "daySchedules": [
                            {
                              "dayScheduleId": 1,
                              "day": 1,
                              "places": [
                                {
                                  "placeId": 1,
                                  "placeName": "황리단길",
                                  "startTime": "10:30:00",
                                  "memo": "황남시장에 짐보관",
                                  "latitude": 35.884,
                                  "longitude": 129.8341,
                                  "placeUrl": "http://place.map.kakao.com/26338954"
                                }
                              ]
                            },
                            {
                              "dayScheduleId": 2,
                              "day": 2,
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
            description = "존재하지 않는 courseId가 경로변수로 주어졌을 때",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                        {
                          "code": "COURSE_NOT_FOUND",
                          "timestamp": "2025-11-11T00:00:44.7553392",
                          "message": "코스를 찾을 수 없습니다."
                        }
                        """
                )
            )
        )
    })
    public MyCourseDetailResponse getMyCourseDetail(
        @PathVariable @Schema(example = "1") Long courseId) {
        return myCourseService.getMyCourseDetail(courseId);
    }

}
