package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;

public interface MyCourseService {
    void forkCourse(Long userId, UploadCourse uploadCourse);
}