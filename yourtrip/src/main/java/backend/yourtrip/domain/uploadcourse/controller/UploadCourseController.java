package backend.yourtrip.domain.uploadcourse.controller;

import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/upload-courses")
public class UploadCourseController {

    private final UploadCourseService uploadCourseService;

    @GetMapping("/keywords")
    public CourseKeywordListResponse getCourseKeywordList() {
        return uploadCourseService.getCourseKeywordList();
    }

}
