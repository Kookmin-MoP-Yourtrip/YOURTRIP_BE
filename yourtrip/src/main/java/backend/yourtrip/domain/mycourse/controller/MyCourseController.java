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
public class MyCourseController implements MyCourseControllerSpec {

    private final MyCourseService myCourseService;

    // ==========================
    //  나의 코스 생성
    // ==========================
    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MyCourseCreateResponse createMyCourse(
        @Valid @RequestBody MyCourseCreateRequest request) {
        return myCourseService.saveCourse(request);
    }

    // ==========================
    //  나의 코스 목록 조회
    // ==========================
    @Override
    @GetMapping
    public MyCourseListResponse getMyCourses() {
        return myCourseService.getMyCourseList();
    }

    // ==========================
    //  일차별 장소 리스트 조회
    // ==========================
    @Override
    @GetMapping("/{courseId}/{day}")
    public DayScheduleResponse getDaySchedule(
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") int day) {
        return myCourseService.getPlaceListByDay(courseId, day);
    }

    // ==========================
    //  장소 추가
    // ==========================
    @PostMapping("/{courseId}/{day}/places")
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceCreateResponse createPlace(@Valid @RequestBody PlaceCreateRequest request,
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") int day) {
        return myCourseService.savePlace(courseId, day, request);
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
