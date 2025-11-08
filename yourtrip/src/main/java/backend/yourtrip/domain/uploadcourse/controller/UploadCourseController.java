package backend.yourtrip.domain.uploadcourse.controller;

import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseCreateResponse;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "UploadCourse API", description = "업로드 코스 관련 api")
@RequestMapping("/api/upload-courses")
public class UploadCourseController {

    private final UploadCourseService uploadCourseService;

    @GetMapping("/keywords")
    @Operation(summary = "코스 키워드 목록 조회 (코스 업로드 시 선택할 수 있는 키워드 보여주는 용도")
    public CourseKeywordListResponse getCourseKeywordList() {
        return uploadCourseService.getCourseKeywordList();
    }

    // TODO: 멀티파트 데이터 입력으로 변경 (썸네일 이미지 고려)
    @PostMapping
    @Operation(summary = "코스 업로드")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadCourseCreateResponse courseUpload(
        @Valid @RequestBody UploadCourseCreateRequest request) {
        return uploadCourseService.createUploadCourse(request);
    }


}
