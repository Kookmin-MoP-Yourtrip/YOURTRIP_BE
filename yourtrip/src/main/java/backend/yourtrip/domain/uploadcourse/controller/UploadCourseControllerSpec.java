package backend.yourtrip.domain.uploadcourse.controller;

import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseCreateResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListResponse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.entity.enums.UploadCourseSortType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.multipart.MultipartFile;

public interface UploadCourseControllerSpec {

    // ==========================
    //  코스 키워드 목록 조회
    // ==========================
    @GetMapping("/keywords")
    @Operation(
        summary = "코스 키워드 목록 조회",
        description = """
              코스 업로드 시 선택할 수 있는 키워드 목록을 보여줍니다.
              이동수단, 동행유형, 여행 분위기, 여행 기간, 예산에 해당하는 키워드들을 반환하도록 분류해놓았습니다.
              업로드 코스 목록 조회 api 호출 시 여기서 반환받은 code값을 쿼리 파라미터로 넘기면 태그 기반 필터링이 가능합니다.
              
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
    CourseKeywordListResponse getCourseKeywordList();

    // ==========================
    //   업로드 코스 목록 조회
    // ==========================
    @Operation(
        summary = "업로드 코스 목록 검색 및 조회(여행지/제목 기반 키워드 검색, 태그 기반 필터링, 최신순/인기순 정렬)",
        description = """
            ### 제약조건
            - 경로 변수
                - 업로드 코스 ID(uploadCourseId): 존재하는 코스여야 함
            - 쿼리 파라미터
                - keyword(선택): 여행지 또는 제목 기반 키워드 검색을 수행합니다. 파라미터 미포함 시 전체 조회됩니다.
                - tags(선택, 복수 가능): 태그 기반 필터링을 수행합니다. **코스 키워드 목록 조회 API**로 받은 `code` 값만 배열에 담아 전송 (예: `"WALK"`, `"FOOD"`). 배열에 담긴 태그들 중 하나라도 포함된 업로드 코스들이 반환됩니다. 파라미터 미포함 시 태그 필터링이 적용되지 않습니다.
                - sort: 업로드 코스 목록 정렬 기준을 설정합니다. (최신순/인기순). 파라미터 미포함 시 인기순이 디폴트로 적용됩니다. 
                    - NEW(최신순): 업로드 코스 목록이 최신순으로 정렬되어 주어집니다.
                    - POPULAR(인기순): 업로드 코스 목록이 인기순으로 정렬되어 주어집니다. 인기순은 조회순 기반입니다.
                아무 파라미터를 넘겨주지 않으면 인기순으로 정렬됩니다.
            ### 에외 상황
            - `INVALID_REQUEST_FIELD(400)`: 잘못된 정렬 기준이 주어진 경우(POPULAR, NEW 외의 값)
            - 반환받는 image url들은 임시 url로 15분간만 유효합니다(보안상 문제), 로드한 이미지가 15분 뒤에 사라지는게 아니라 발급받은 url로 15분이 지난 후 로드를 시도하면 유효하지 않다는 뜻입니다.
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
                              "thumbnailImageUrl": "http://example.com",
                              "forkCount": 10,
                              "keywords": [
                                "뚜벅이",
                                "맛집탐방",
                                "힐링"
                              ]
                            },
                            {
                              "uploadCourseId": 1,
                              "title": "개쩌는 호주 여행기",
                              "location": "호주",
                              "thumbnailImageUrl": "http://example.com",
                              "forkCount": 15,
                              "keywords": [
                                "뚜벅이",
                                "맛집탐방",
                                "힐링"
                              ]
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
    UploadCourseListResponse getAllUploadCourses(String keyword, List<KeywordType> tags,
        UploadCourseSortType sortType);

    // ==========================
    //  업로드 코스 상세 조회
    // ==========================
    @Operation(
        summary = "업로드 코스 상세 조회",
        description = """
            - 반환받는 image url들은 임시 url로 15분간만 유효합니다(보안상 문제), 로드한 이미지가 15분 뒤에 사라지는게 아니라 발급받은 url로 15분이 지난 후 로드를 시도하면 유효하지 않다는 뜻입니다.
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
                          "title": "경주 인생샷 1일 코스",
                          "location": "경주",
                          "introduction": "황리단길부터 첨성대까지, 인생샷 남기기 좋은 스팟만 모았어요.",
                          "thumbnailImageUrl": "http://example.com/thumbnail.jpg",
                          "startDate": "2025-03-01",
                          "endDate": "2025-03-01",
                          "forkCount": 4,
                          "keywords": [
                            "힐링",
                            "뚜벅이",
                            "혼자"
                          ],
                          "daySchedules": [
                            {
                              "dayScheduleId": 1,
                              "day": 1,
                              "places": [
                                {
                                  "placeId": 1,
                                  "placeName": "황리단길 카페 거리",
                                  "startTime": "10:30:00",
                                  "memo": "감성 카페 골목 산책",
                                  "latitude": 35.8375,
                                  "longitude": 129.2123,
                                  "placeUrl": "http://place.map.kakao.com/26338954",
                                  "placeLocation": "경북 경주시 포석로 인근",
                                  "placeImages": [
                                    {
                                      "placeId": 1,
                                      "placeImageId": 1,
                                      "imageUrl": "http://example.com/image1.jpg"
                                    },
                                    {
                                      "placeId": 1,
                                      "placeImageId": 2,
                                      "imageUrl": "http://example.com/image2.jpg"
                                    }
                                  ]
                                },
                                {
                                  "placeId": 2,
                                  "placeName": "첨성대 야경",
                                  "startTime": "19:30:00",
                                  "memo": "야경 사진 스팟",
                                  "latitude": 35.8341,
                                  "longitude": 129.2185,
                                  "placeUrl": "http://place.map.kakao.com/12345678",
                                  "placeLocation": "경북 경주시 인왕동",
                                  "placeImages": []
                                }
                              ]
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
    UploadCourseDetailResponse getUploadCourseDetail(@Schema(example = "1") Long uploadCourseId);

    // ==========================
    //  코스 업로드
    // ==========================
    @Operation(
        summary = "코스 업로드",
        description = """
            ### 제약조건
            - myCourseId: 필수, 존재하는 '나의 코스'와 연동
            - title: 공백 불가
            - keywords: 필수(빈 배열 허용). **키워드 목록 조회 API**로 받은 `code` 값만 배열에 담아 전송 (예: `"WALK"`, `"FOOD"`), 선택된 키워드가 없다면 빈 배열 반환
            - thumbnailImage: multipart/form-data 타입으로 썸네일 이미지를 업로드합니다 (선택)
              png, jpeg, jpg, webp, mp4, quicktime, webm 타입만 업로드 가능합니다.

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
                          "uploadCourseId": 1,
                          "title": "경주 인생샷 1일 코스",
                          "location": "경주",
                          "introduction": "황리단길부터 첨성대까지, 인생샷 남기기 좋은 스팟만 모았어요.",
                          "startDate": "2025-03-01",
                          "endDate": "2025-03-01",
                          "keywords": [
                            "힐링",
                            "뚜벅이",
                            "혼자"
                          ],
                          "daySchedules": [
                            {
                              "dayScheduleId": 1,
                              "day": 1,
                              "places": [
                                {
                                  "placeId": 1,
                                  "placeName": "황리단길 카페 거리",
                                  "startTime": "10:30:00",
                                  "memo": "감성 카페 골목 산책",
                                  "latitude": 35.8375,
                                  "longitude": 129.2123,
                                  "placeUrl": "http://place.map.kakao.com/26338954",
                                  "placeLocation": "경북 경주시 포석로 인근",
                                  "placeImages": [
                                    {
                                      "placeId": 1,
                                      "placeImageId": 1,
                                      "imageUrl": "http://example.com/image1.jpg"
                                    },
                                    {
                                      "placeId": 1,
                                      "placeImageId": 2,
                                      "imageUrl": "http://example.com/image2.jpg"
                                    }
                                  ]
                                },
                                {
                                  "placeId": 2,
                                  "placeName": "첨성대 야경",
                                  "startTime": "19:30:00",
                                  "memo": "야경 사진 스팟",
                                  "latitude": 35.8341,
                                  "longitude": 129.2185,
                                  "placeUrl": "http://place.map.kakao.com/12345678",
                                  "placeLocation": "경북 경주시 인왕동",
                                  "placeImages": []
                                }
                              ]
                            }
                          ]
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
    UploadCourseCreateResponse courseUpload(
        MultipartFile thumbnailImage,
        @Parameter(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = UploadCourseCreateRequest.class)
        )) UploadCourseCreateRequest request);

}
