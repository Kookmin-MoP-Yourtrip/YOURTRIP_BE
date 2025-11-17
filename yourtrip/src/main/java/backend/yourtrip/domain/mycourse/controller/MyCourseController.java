package backend.yourtrip.domain.mycourse.controller;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceMemoRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceStartTimeRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceUpdateRequest;
import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceImageCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceMemoUpdateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceStartTimeUpdateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceUpdateResponse;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    @GetMapping("/{courseId}/days/{dayId}/places")
    public DayScheduleResponse getDaySchedule(
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") Long dayId) {
        return myCourseService.getPlaceListByDay(courseId, dayId);
    }

    // ==========================
    //  장소 추가
    // ==========================
    @PostMapping("/{courseId}/days/{dayId}/places")
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceCreateResponse createPlace(@Valid @RequestBody PlaceCreateRequest request,
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") Long dayId) {
        return myCourseService.savePlace(courseId, dayId, request);
    }

    // ==========================
    //  장소 수정
    // ==========================
    @PatchMapping("/{courseId}/days/{dayId}/places/{placeId}")
    public PlaceUpdateResponse updatePlace(
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") Long dayId,
        @PathVariable @Schema(example = "1") Long placeId,
        @Valid @RequestBody PlaceUpdateRequest request
    ) {
        return myCourseService.updatePlace(courseId, dayId, placeId, request);
    }

    // ==========================
    //  장소 시간 수정
    // ==========================
    @PatchMapping("/{courseId}/days/{dayId}/places/{placeId}/start-time")
    public PlaceStartTimeUpdateResponse addPlaceTime(
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") Long dayId,
        @PathVariable @Schema(example = "1") Long placeId,
        @RequestBody PlaceStartTimeRequest request) {

        return myCourseService.addPlaceTime(courseId, dayId, placeId, request.startTime());
    }

    // ==========================
    // 장소 메모 수정
    // ==========================
    @PatchMapping("/{courseId}/days/{dayId}/places/{placeId}/memo")
    public PlaceMemoUpdateResponse addPlaceMemo(
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") Long dayId,
        @PathVariable @Schema(example = "1") Long placeId,
        @RequestBody PlaceMemoRequest request
    ) {
        return myCourseService.addPlaceMemo(courseId, dayId, placeId, request.memo());
    }

    // ==========================
    // 장소 사진 추가
    // =========================
    @PostMapping(value = "/{courseId}/days/{dayId}/places/{placeId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceImageCreateResponse addPlaceImage(
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") Long dayId,
        @PathVariable @Schema(example = "1") Long placeId,
        @RequestPart("placeImage") MultipartFile placeImage
    ) {
        return myCourseService.addPlaceImage(courseId, dayId, placeId, placeImage);
    }


}
