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
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/my-courses")
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
    //  나의 코스 단건 조회
    // ==========================
    @Override
    @GetMapping("/{courseId}")
    public MyCourseDetailResponse getMyCourse(@PathVariable Long courseId) {
        return myCourseService.getMyCourseDetail(courseId);
    }

    // ==========================
    //  일차별 장소 리스트 조회
    // ==========================
    @Override
    @GetMapping("/{courseId}/days/{dayId}/places")
    public DayScheduleResponse getDaySchedule(
        @PathVariable Long courseId,
        @PathVariable Long dayId) {
        return myCourseService.getPlaceListByDay(courseId, dayId);
    }

    // ==========================
    //  장소 추가
    // ==========================
    @Override
    @PostMapping("/{courseId}/days/{dayId}/places")
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceCreateResponse createPlace(@Valid @RequestBody PlaceCreateRequest request,
        @PathVariable Long courseId,
        @PathVariable Long dayId) {
        return myCourseService.savePlace(courseId, dayId, request);
    }

    // ==========================
    //  장소 수정
    // ==========================
    @Override
    @PatchMapping("/{courseId}/days/{dayId}/places/{placeId}")
    public PlaceUpdateResponse updatePlace(
        @PathVariable Long courseId,
        @PathVariable Long dayId,
        @PathVariable Long placeId,
        @Valid @RequestBody PlaceUpdateRequest request
    ) {
        return myCourseService.updatePlace(courseId, dayId, placeId, request);
    }

    // ==========================
    // 장소 삭제
    // =========================
    @Override
    @DeleteMapping("/{courseId}/days/{dayId}/places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlace(
        @PathVariable Long courseId,
        @PathVariable Long dayId,
        @PathVariable Long placeId
    ) {
        myCourseService.deletePlace(courseId, dayId, placeId);
    }


    // ==========================
    //  장소 시간 수정
    // ==========================
    @Override
    @PatchMapping("/{courseId}/days/{dayId}/places/{placeId}/start-time")
    public PlaceStartTimeUpdateResponse updatePlaceTime(
        @PathVariable Long courseId,
        @PathVariable Long dayId,
        @PathVariable Long placeId,
        @RequestBody PlaceStartTimeRequest request) {

        return myCourseService.updatePlaceTime(courseId, dayId, placeId, request.startTime());
    }

    // ==========================
    // 장소 메모 수정
    // ==========================
    @Override
    @PatchMapping("/{courseId}/days/{dayId}/places/{placeId}/memo")
    public PlaceMemoUpdateResponse updatePlaceMemo(
        @PathVariable Long courseId,
        @PathVariable Long dayId,
        @PathVariable Long placeId,
        @RequestBody PlaceMemoRequest request
    ) {
        return myCourseService.updatePlaceMemo(courseId, dayId, placeId, request.memo());
    }

    // ==========================
    // 장소 사진 추가
    // =========================
    @Operation(summary = "장소 사진 추가", description = """
        ### 설명
        - 특정 코스의 특정 일차에 있는 특정 장소에 사진을 추가합니다.
        - 추가된 사진은 해당 장소의 사진 리스트에 포함됩니다.
        - png, jpeg, jpg, webp, mp4, quicktime, webm 타입만 업로드 가능합니다.

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
        """)
    @Override
    @PostMapping(value = "/{courseId}/days/{dayId}/places/{placeId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceImageCreateResponse addPlaceImage(
        @PathVariable Long courseId,
        @PathVariable Long dayId,
        @PathVariable Long placeId,
        @RequestPart("placeImage") MultipartFile placeImage
    ) {
        return myCourseService.addPlaceImage(courseId, dayId, placeId, placeImage);
    }

    // ==========================
    // 장소 이미지 삭제
    // =========================
    @DeleteMapping("/{courseId}/days/{dayId}/places/{placeId}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlaceImage(
        @PathVariable Long courseId,
        @PathVariable Long dayId,
        @PathVariable Long placeId,
        @PathVariable Long imageId
    ) {
        myCourseService.deletePlaceImage(courseId, dayId, placeId, imageId);
    }

}
