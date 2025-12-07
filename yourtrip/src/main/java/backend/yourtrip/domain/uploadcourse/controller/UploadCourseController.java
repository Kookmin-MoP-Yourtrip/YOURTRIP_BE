package backend.yourtrip.domain.uploadcourse.controller;

import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseCreateResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListResponse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.entity.enums.UploadCourseSortType;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@Tag(name = "UploadCourse API", description = "업로드 코스 관련 api")
@RequestMapping("/api/upload-courses")
public class UploadCourseController implements UploadCourseControllerSpec {

    private final UploadCourseService uploadCourseService;

    // ==========================
    //  코스 키워드 목록 조회
    // ==========================
    @Override
    @GetMapping("/keywords")
    public CourseKeywordListResponse getCourseKeywordList() {
        return uploadCourseService.getCourseKeywordList();
    }

    // ==========================
    //   업로드 코스 목록 조회
    // ==========================
    @Override
    @GetMapping
    public UploadCourseListResponse getAllUploadCourses(
        @RequestParam(name = "keyword", required = false) String keyword,
        @RequestParam(name = "tag", required = false) List<KeywordType> keywords,
        @RequestParam(name = "sort", defaultValue = "POPULAR") UploadCourseSortType sortType
    ) {
        return uploadCourseService.getAllForSearch(keyword, keywords, sortType);
    }

    // ==========================
    //  업로드 코스 상세 조회
    // ==========================
    @Override
    @GetMapping("/{uploadCourseId}")
    public UploadCourseDetailResponse getUploadCourseDetail(
        @PathVariable Long uploadCourseId) {
        return uploadCourseService.getDetail(uploadCourseId);
    }

    // ==========================
    //  코스 업로드
    // ==========================
    @Override
    @PostMapping(value = "/", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UploadCourseCreateResponse courseUpload(
        @RequestPart(value = "thumbnailImage", required = false) MultipartFile thumbnailImage,
        @Valid @RequestPart(value = "request") UploadCourseCreateRequest request) {
        return uploadCourseService.createUploadCourse(request, thumbnailImage);
    }

    // ==========================
    //  내가 올린 업로드 코스 리스트
    // ==========================
    @GetMapping("/me")
    public UploadCourseListResponse getMyUploadCourses() {
        return uploadCourseService.getMyUploadCourses();
    }


}
