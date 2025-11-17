package backend.yourtrip.domain.mycourse.controller;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceMemoRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceStartTimeRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceUpdateRequest;
import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceImageCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceMemoUpdateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceStartTimeUpdateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceUpdateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "MyCourse API", description = "나의 코스 관련 API")
public interface MyCourseControllerSpec {

    // ==========================
    //  나의 코스 생성
    // ==========================
    @Operation(
        summary = "나의 코스 생성",
        description = """
            ### 설명
            - 새로운 나의 코스를 생성합니다.
            - 생성 시 코스의 제목, 여행지, 여행 시작일과 종료일을 입력받습니다.
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
                          "title": "개쩌는 경주 여행기",
                          "location": "경주",
                          "startDate": "2025-10-31",
                          "endDate": "2025-11-02"
                          "memberCount": 1
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

    // ==========================
    //  나의 코스 목록 조회
    // ==========================
    @Operation(summary = "나의 코스 목록 조회", description = """
        ### 설명
        - 내가 생성한 코스들의 요약 정보를 가장 최근에 수정한 순서대로 조회합니다.
        - 각 코스의 ID, 제목, 여행지, 여행 기간, 편집 인원 수를 포함합니다.
        """)
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
                              "courseId": 1,
                              "title": "개쩌는 호주 여행기",
                              "location": "호주",
                              "startDate": "2025-10-31",
                              "endDate": "2025-11-02",
                              "memberCount": 1
                            },
                            {
                              "courseId": 2,
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
        ),
    })
    MyCourseListResponse getMyCourses();

    // ==========================
    //  나의 코스 단건 조회
    // ==========================]
    @Operation(summary = "나의 코스 단건 조회", description = """
        ### 설명
        - 특정 코스의 상세 정보를 조회합니다.
        - 코스의 ID, 제목, 여행지, 여행 기간, 편집 인원 수, 여행 기간, 내 역할(OWNER/PARTICIPANT), 마지막 수정시간, 일차별 일정 요약 정보를 포함합니다.
        - role: 코스 최초 생성자는 OWNER, 초대받은 참여자는 PARTICIPANT, OWNER인 사람만 코스 초대 버튼과 업로드 버튼이 보여야 합니다.
        - daySchedules: 각 일차를 나타내는 고유 ID(dayId)와 몇 일차인지(day)를 포함합니다. dayId는 일차별 장소 리스트 조회 api에 사용됩니다.
        ### 제약조건
        - 경로 변수
            - 코스 ID(courseId): 존재하는 코스여야 함
        ### ⚠ 예외상황
        - `COURSE_NOT_FOUND(404)`: 코스가 존재하지 않는 경우 (잘못된 courseId가 주어진 경우)
        """)
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "나의 코스 단건 조회 성공",
            content = @Content(
                schema = @Schema(implementation = MyCourseDetailResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "courseId": 1,
                          "title": "개쩌는 경주 여행기",
                          "location": "경주",
                          "startDate": "2025-10-31",
                          "endDate": "2025-11-02",
                          "memberCount": 1,
                          "role": "OWNER",
                          "updatedAt": "2025-11-10T11:00:00",
                          "daySchedules": [
                            {
                              "dayId": 1,
                              "day": 1
                            },
                            {
                              "dayId": 2,
                              "day": 2
                            },
                            {
                              "dayId": 3,
                              "day": 3
                            }
                          ]
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "잘못된 courseId가 주어졌을 때",
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
    MyCourseDetailResponse getMyCourse(@Schema(example = "1") Long courseId);

    // ==========================
    //  일차별 장소 리스트 조회
    // ==========================
    @Operation(
        summary = "일차별 장소 리스트 조회",
        description = """
            ### 설명
            - 특정 코스의 특정 일차에 해당하는 장소 리스트를 조회합니다. (피그마에서 일차별로 탭 누르는 화면)
            - 앞서 나의 코스 단건 조회 API에서 제공된 dayId를 사용하여 해당 일차의 장소들을 불러올 수 있습니다.
            - 장소의 latitude, longitude가 null인 경우, 수기로 추가된 장소입니다.
            - 반환받는 image url들은 임시 url로 15분간만 유효합니다(보안상 문제), 로드한 이미지가 15분 뒤에 사라지는게 아니라 발급받은 url로 15분이 지난 후 로드를 시도하면 유효하지 않다는 뜻입니다.

            ### 제약조건
            - 경로 변수
                - 코스 ID(courseId): 존재하는 코스여야 함
                - 일차 ID(dayId): 해당 코스에 존재하는 일차여야 함
            ### ⚠ 예외상황
            - `COURSE_NOT_FOUND(404)`: 코스가 존재하지 않는 경우 (잘못된 courseId가 주어진 경우)
            - `DAY_SCHEDULE_NOT_FOUND)(404)`: 해당 코스에 존재하지 않는 일차인 경우 (잘못된 dayId가 주어진 경우)
            ### 참고사항
            - 반환되는 장소 리스트는 각 장소가 생성된 순서대로 오름차순 정렬되어 있습니다 (place의 startTime과 무관).
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "일차별 장소 리스트 조회 성공",
            content = @Content(
                schema = @Schema(implementation = DayScheduleResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "dayId": 1,
                          "day": 1,
                          "places": [
                            {
                              "placeId": 1,
                              "placeName": "첨성대",
                              "startTime": "10:30",
                              "memo": "야경 맛집",
                              "latitude": 35.842123,
                              "longitude": 129.224456,
                              "placeLocation": "경상북도 경주시 인왕동 789-12",
                              "placeUrl": "https://place.map.kakao.com/12345678",
                            },
                            {
                              "placeId": 2,
                              "placeName": "불국사",
                              "startTime": "12:00",
                              "memo": "세계문화유산",
                              "latitude": 35.831234,
                              "longitude": 129.222345,
                              "placeLocation": "경상북도 경주시 진현동 456-78
                              "placeUrl": "https://place.map.kakao.com/87654321",
                              "startTime": "13:00"
                            }
                          ]
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "잘못된 courseId가 주어졌을 때",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "COURSE_NOT_FOUND",
                              "message": "코스를 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 dayId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "DAY_SCHEDULE_NOT_FOUND",
                              "message": "해당 일차 일정을 찾을 수 없습니다."
                            }
                            """
                    )
                }
            )
        )
    })
    DayScheduleResponse getDaySchedule(@Schema(example = "1") Long courseId,
        @Schema(example = "1") Long dayId);

    // ==========================
    //  장소 추가
    // ==========================
    @Operation(
        summary = "일차에 장소 추가",
        description = """
            ### 설명
            - 특정 코스의 특정 일차에 새로운 장소를 추가합니다.
            - 장소명, 위도(latitude), 경도(longitude), 장소 URL(placeUrl), 장소 주소(placeLocation)를 입력받아 해당 일차에 장소를 등록합니다.
            - 추가된 장소는 해당 일차의 장소 리스트에 포함됩니다.
            - 수기로 장소 추가를 하는 경우 latitude, longitude, placeUrl 등의 정보는 입력하지 않으시면 됩니다.
            - 장소가 추가된 바로 직후엔 startTime과 memo는 비어있는(null) 상태입니다.
            ### 제약조건
            - 경로 변수
                - 코스 ID(courseId): 존재하는 코스여야 함
                - 일차 ID(dayId): 해당 코스에 존재하는 일차여야 함
            - 요청 값
                - 장소명(placeName): 공백 불가
            ### ⚠ 예외상황
            - `INVALID_REQUEST_FIELD(400)`: 필드 유효성 오류(빈 값, 포맷 불일치 등)
            - `DAY_SCHEDULE_NOT_FOUND(404)`: 해당 코스에 존재하지 않는 일차인 경우 (잘못된 dayId가 주어진 경우)
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
                          "placeName": "황리단길",
                          "latitude": 35.842123,
                          "longitude": 129.224456,
                          "placeUrl": "https://place.map.kakao.com/12345678",
                          "placeLocation": "경상북도 경주시 황남동 123-45",
                          "memo": null,
                          "startTime": null
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
                          "code": "DAY_SCHEDULE_NOT_FOUND",
                          "message": "해당 일차 일정을 찾을 수 없습니다."
                        }
                        """
                )
            )
        )
    })
    PlaceCreateResponse createPlace(PlaceCreateRequest request,
        @Schema(example = "1") Long courseId, @Schema(example = "1") Long dayId);

    @Operation(summary = "장소 수정", description = """
        ### 설명
        - 특정 코스의 특정 일차에 있는 특정 장소를 수정합니다.
        - 카카오맵을 통해 장소 위치를 바꾸는 경우로 장소명, 위도(latitude), 경도(longitude), 장소 URL(placeUrl), 장소 주소(placeLocation)를 입력받아 해당 장소의 정보를 업데이트합니다.
        - 기존 startTime, memo, placeImages 등은 유지됩니다.
        - 장소 추가 api에서의 요청 필드값이 동일합니다.
        - 수기로 장소 추가의 경우 장소 추가 api와 동일하게 latitude, longitude, placeUrl 등의 정보는 입력하지 않으시면 됩니다.
        ### 제약조건
        - 경로 변수
            - 코스 ID(courseId): 존재하는 코스여야 함
            - 일차 ID(dayId): 해당 코스에 존재하는 일차여야 함
            - 장소 ID(placeId): 해당 일차에 존재하는 장소여야 함
        - 요청 값
            - 장소명(placeName): 공백 불가
        ### ⚠ 예외상황
        - `INVALID_REQUEST_FIELD(400)`: 필드 유효성 오류(빈 값, 포맷 불일치 등)
        - `COURSE_NOT_FOUND(404)`: 코스가 존재하지 않는 경우 (잘못된 courseId가 주어진 경우)
        - `DAY_SCHEDULE_NOT_FOUND(404)`: 해당 코스에 존재하지 않는 일차인 경우 (잘못된 dayId가 주어진 경우)
        - `PLACE_NOT_FOUND(404)`: 해당 일차에 존재하지 않는 장소인 경우 (잘못된 placeId가 주어진 경우)
        ### 참고사항
        - 장소 URL(placeUrl): 지도로 바로 연결되는 카카오 or 구글 장소 url
        """)
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "장소 수정 성공",
            content = @Content(
                schema = @Schema(implementation = PlaceUpdateResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "placeId": 1,
                          "placeName": "황리단길",
                          "latitude": 35.842123,
                          "longitude": 129.224456,
                          "placeUrl": "https://place.map.kakao.com/12345678",
                          "placeLocation": "경상북도 경주시 황남동 123-45"
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "장소명 미입력",
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
                    )
                }
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "경로변수로 올바르지 않은 courseId, dayId, placeId가 주어졌을 때",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "잘못된 courseId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "COURSE_NOT_FOUND",
                              "message": "코스를 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 dayId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "DAY_SCHEDULE_NOT_FOUND",
                              "message": "해당 일차 일정을 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 placeId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "PLACE_NOT_FOUND",
                              "message": "해당 장소를 찾을 수 없습니다."
                            }
                            """
                    )
                }
            )
        )
    })
    PlaceUpdateResponse updatePlace(
        @Schema(example = "1") Long courseId,
        @Schema(example = "1") Long dayId,
        @Schema(example = "1") Long placeId,
        PlaceUpdateRequest request);

    @Operation(summary = "장소 삭제", description = """
        ### 설명
        - 특정 코스의 특정 일차에 있는 특정 장소를 삭제합니다.
        - 삭제된 장소는 더 이상 해당 일차의 장소 리스트에 포함되지 않습니다.
        - 장소에 포함된 이미지, 메모, 시간, 위치 등 장소에 대한 모든 데이터가 삭제됩니다. 
        ### 제약조건
        - 경로 변수
            - 코스 ID(courseId): 존재하는 코스여야 함
            - 일차 ID(dayId): 해당 코스에 존재하는 일차여야 함
            - 장소 ID(placeId): 해당 일차에 존재하는 장소여야 함
        ### ⚠ 예외상황
        - `COURSE_NOT_FOUND(404)`: 코스가 존재하지 않는 경우 (잘못된 courseId가 주어진 경우)
        - `DAY_SCHEDULE_NOT_FOUND(404)`: 해당 코스에 존재하지 않는 일차인 경우 (잘못된 dayId가 주어진 경우)
        - `PLACE_NOT_FOUND(404)`: 해당 일차에 존재하지 않는 장소인 경우 (잘못된 place Id가 주어진 경우)
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "204",
            description = "장소 삭제 성공"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "경로변수로 올바르지 않은 courseId, dayId, placeId가 주어졌을 때",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "잘못된 courseId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "COURSE_NOT_FOUND",
                              "message": "코스를 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 dayId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "DAY_SCHEDULE_NOT_FOUND",
                              "message": "해당 일차 일정을 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 placeId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "PLACE_NOT_FOUND",
                              "message": "해당 장소를 찾을 수 없습니다."
                            }
                            """
                    )
                }
            )
        )
    })
    void deletePlace(
        @Schema(example = "1") Long courseId,
        @Schema(example = "1") Long dayId,
        @Schema(example = "1") Long placeId);

    @Operation(summary = "장소 시간 수정", description = """
        ### 설명
        - 특정 코스의 특정 일차에 있는 특정 장소의 시간을 수정합니다.
        - 초기 장소의 시간은 null 상태이며, 이 api를 통해 시간을 추가하거나 변경할 수 있습니다.
        - 시간을 삭제하고 싶은 경우 startTime에 null 값을 보내면 됩니다.
        ### 제약조건
        - 경로 변수
            - 코스 ID(courseId): 존재하는 코스여야 함
            - 일차 ID(dayId): 해당 코스에 존재
            - 장소 ID(placeId): 해당 일차에 존재하는 장소여야 함
        - 요청 값
            - 시작 시간(startTime): HH:mm 형식의 시간 문자열 또는 null
        ### ⚠ 예외상황
        - `COURSE_NOT_FOUND(404)`: 코스가 존재하지 않는 경우 (잘못된 courseId가 주어진 경우)
        - `DAY_SCHEDULE_NOT_FOUND(404)`: 해당 코스에 존재하지 않는 일차인 경우 (잘못된 dayId가 주어진 경우)
        - `PLACE_NOT_FOUND(404)`: 해당 일차에 존재하지 않는 장소인 경우 (잘못된 placeId가 주어진 경우)
        - `INVALID_REQUEST_FIELD(400)`: 필드 유효성 오류(시간 포맷 불일치 등)
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "장소 시간 수정 성공",
            content = @Content(
                schema = @Schema(implementation = PlaceStartTimeUpdateResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "placeId": 1,
                          "startTime": "10:30"
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "시간 포맷 불일치",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "시간 포맷 불일치",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "INVALID_REQUEST_FIELD",
                              "message": "startTime: 시간은 HH:mm 형식이어야 합니다."
                            }
                            """
                    )
                }
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "경로변수로 올바르지 않은 courseId, dayId, placeId가 주어졌을 때",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "잘못된 courseId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "COURSE_NOT_FOUND",
                              "message": "코스를 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 dayId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "DAY_SCHEDULE_NOT_FOUND",
                              "message": "해당 일차 일정을 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 placeId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "PLACE_NOT_FOUND",
                              "message": "해당 장소를 찾을 수 없습니다."
                            }
                            """
                    )
                }
            )
        )
    })
    PlaceStartTimeUpdateResponse updatePlaceTime(
        @Schema(example = "1") Long courseId,
        @Schema(example = "1") Long dayId,
        @Schema(example = "1") Long placeId,
        PlaceStartTimeRequest startTime);

    @Operation(summary = "장소 메모 수정", description = """
        ### 설명
        - 특정 코스의 특정 일차에 있는 특정 장소의 메모를 수정합니다
        - 초기 장소의 메모는 null 상태이며, 이 api를 통해 메모를 추가하거나 변경할 수 있습니다.
        - 메모를 삭제하고 싶은 경우 memo에 null 값을 보내면 됩니다.
        ### 제약조건
        - 경로 변수
            - 코스 ID(courseId): 존재하는 코스여야 함
            - 일차 ID(dayId): 해당 코스에 존재하는 일차여야 함
            - 장소 ID(placeId): 해당 일차에 존재하는 장소여야 함
        - 요청 값
            - 메모(memo): 문자열 또는 null
        ### ⚠ 예외상황
        - `COURSE_NOT_FOUND(404)`: 코스가 존재하지 않는 경우 (잘못된 courseId가 주어진 경우)
        - `DAY_SCHEDULE_NOT_FOUND(404)`: 해당 코스에 존재하지 않는 일차인 경우 (잘못된 dayId가 주어진 경우)
        - `PLACE_NOT_FOUND(404)`: 해당 일차에 존재하지 않는 장소인 경우 (잘못된 placeId가 주어진 경우)
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "장소 메모 수정 성공",
            content = @Content(
                schema = @Schema(implementation = PlaceMemoUpdateResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "placeId": 1,
                          "memo": "야경 맛집"
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "경로변수로 올바르지 않은 courseId, dayId, placeId가 주어졌을 때",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "잘못된 courseId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "COURSE_NOT_FOUND",
                              "message": "코스를 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 dayId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "DAY_SCHEDULE_NOT_FOUND",
                              "message": "해당 일차 일정을 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 placeId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "PLACE_NOT_FOUND",
                              "message": "해당 장소를 찾을 수 없습니다."
                            }
                            """
                    )
                }
            )
        )
    })
    PlaceMemoUpdateResponse updatePlaceMemo(
        @Schema(example = "1") Long courseId,
        @Schema(example = "1") Long dayId,
        @Schema(example = "1") Long placeId,
        PlaceMemoRequest request
    );


    @Operation(summary = "장소 사진 추가", description = """
        ### 설명
        - 특정 코스의 특정 일차에 있는 특정 장소에 사진을 추가합니다.
        - 추가된 사진은 해당 장소의 사진 리스트에 포함됩니다.
        - multipart/form-data 타입으로 이미지를 업로드합니다
        - png, jpeg, jpg, webp, mp4, quicktime, webm 타입만 업로드 가능합니다.
        - 반환받는 image url은 임시 url로 15분간만 유효합니다(보안상 문제), 로드한 이미지가 15분 뒤에 사라지는게 아니라 발급받은 url로 15분이 지난 후 로드를 시도하면 유효하지 않다는 뜻입니다.

        ### 제약조건
        - 경로 변수
            - 코스 ID(courseId): 존재하는 코스여야 함
            - 일차 ID(dayId): 해당 코스에 존재하는 일차여야 함
            - 장소 ID(placeId): 해당 일차에 존재하는 장소여야 함
        - 요청 값
            - 장소 이미지(placeImage): 이미지 파일 (MultipartFile)
        ### ⚠ 예외상황
        - `COURSE_NOT_FOUND(404)`: 코스가 존재하지 않는 경우 (잘못된 courseId가 주어진 경우)
        - `DAY_SCHEDULE_NOT_FOUND(404)`: 해당 코스에 존재하지 않는 일차인 경우 (잘못된 dayId가 주어진 경우)
        - `PLACE_NOT_FOUND(404)`: 해당 일차에 존재하지 않는 장소인 경우 (잘못된 placeId가 주어진 경우)
        - `FAIL_UPLOAD_FILE(503)`: 파일 업로드에 실패한 경우
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "장소 사진 추가 성공",
            content = @Content(
                schema = @Schema(implementation = PlaceImageCreateResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                          "imageId": 1,
                          "imageUrl": "https://yourtrip-bucket.s3.ap-northeast-2.amazonaws.com/place-images/abcd1234.jpg"
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "경로변수로 올바르지 않은 courseId, dayId, placeId가 주어졌을 때",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "잘못된 courseId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "COURSE_NOT_FOUND",
                              "message": "코스를 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 dayId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "DAY_SCHEDULE_NOT_FOUND",
                              "message": "해당 일차 일정을 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 placeId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "PLACE_NOT_FOUND",
                              "message": "해당 장소를 찾을 수 없습니다."
                            }
                            """
                    )
                }
            )
        ),
        @ApiResponse(
            responseCode = "503",
            description = "파일 업로드 실패 시",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                        {
                          "timestamp": "2025-11-10T11:00:00",
                          "code": "FAIL_UPLOAD_FILE",
                          "message": "파일 업로드에 실패하였습니다. 잠시 후에 다시 시도해주세요"
                        }
                        """
                )
            )
        )
    })
    PlaceImageCreateResponse addPlaceImage(
        @Schema(example = "1") Long courseId,
        @Schema(example = "1") Long dayId,
        @Schema(example = "1") Long placeId,
        MultipartFile placeImage);

    @Operation(summary = "장소 사진 삭제", description = """
        ### 설명
        - 특정 코스의 특정 일차에 있는 특정 장소에서 특정 이미지를 삭제합니다
        - 삭제된 이미지는 더 이상 해당 장소의 이미지 리스트에 포함되지 않습니다.
        ### 제약조건
        - 경로 변수
            - 코스 ID(courseId): 존재하는 코스여야 함
            - 일차 ID(dayId): 해당 코스에 존재하는 일차여야 함
            - 장소 ID(placeId): 해당 일차에 존재하는 장소여야 함
            - 이미지 ID(imageId): 해당 장소에 존재하는 이미지여야 함. 앞서 장소 사진 추가 혹은 일차별 장소 리스트 조회에서 반환받은 placeImageId를 사용합니다.
        ### ⚠ 예외상황
        - `COURSE_NOT_FOUND(404)`: 코스가 존재하지 않는 경우 (잘못된 courseId가 주어진 경우)
        - `DAY_SCHEDULE_NOT_FOUND(404)`: 해당 코스에 존재하지 않는 일차인 경우 (잘못된 dayId가 주어진 경우)
        - `PLACE_NOT_FOUND(404)`: 해당 일차에 존재하지 않는 장소인 경우 (잘못된 placeId가 주어진 경우)
        - `PLACE_IMAGE_NOT_FOUND(404)`: 해당 장소에 존재하지 않는 이미지인 경우 (잘못된 placeImageId가 주어진 경우)
        - `FAIL_DELETE_FILE(503)`: 파일 삭제에 실패한 경우
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "204",
            description = "장소 사진 삭제 성공"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "경로변수로 올바르지 않은 courseId, dayId, placeId, imageId가 주어졌을 때",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "잘못된 courseId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "COURSE_NOT_FOUND",
                              "message": "코스를 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 dayId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "DAY_SCHEDULE_NOT_FOUND",
                              "message": "해당 일차 일정을 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 placeId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "PLACE_NOT_FOUND",
                              "message": "해당 장소를 찾을 수 없습니다."
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "잘못된 imageId가 주어졌을 때",
                        value = """
                            {
                              "timestamp": "2025-11-10T11:00:00",
                              "code": "PLACE_IMAGE_NOT_FOUND",
                              "message": "해당 장소 이미지를 찾을 수 없습니다."
                            }
                            """
                    )
                }
            )
        ),
        @ApiResponse(
            responseCode = "503",
            description = "파일 삭제 실패 시",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                        {
                          "timestamp": "2025-11-10T11:00:00",
                          "code": "FAIL_DELETE_FILE",
                          "message": "파일 삭제에 실패하였습니다. 잠시 후에 다시 시도해주세요"
                        }
                        """
                )
            )
        )
    })
    void deletePlaceImage(
        @Schema(example = "1") Long courseId,
        @Schema(example = "1") Long dayId,
        @Schema(example = "1") Long placeId,
        @Schema(example = "1") Long imageId);

}
