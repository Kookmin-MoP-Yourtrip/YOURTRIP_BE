package backend.yourtrip.domain.mycourse.controller;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/my-course")
@Tag(name = "MyCourse API", description = "나의 코스 관련 API")
public class MyCourseController {

    private final MyCourseService myCourseService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "나의 코스 생성 API")
    public MyCourseCreateResponse createMyCourse(@RequestBody MyCourseCreateRequest request) {
        return myCourseService.saveCourse(request);
    }

    @PostMapping("{courseId}/{day}/places")
    @Operation(summary = "나의 코스에 장소 추가 API")
    public PlaceCreateResponse createPlace(@RequestBody PlaceCreateRequest request,
        @PathVariable @Schema(example = "1") Long courseId,
        @PathVariable @Schema(example = "1") int day) {
        return myCourseService.savePlace(courseId, day, request);
    }

}
