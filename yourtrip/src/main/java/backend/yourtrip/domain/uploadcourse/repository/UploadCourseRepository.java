package backend.yourtrip.domain.uploadcourse.repository;

import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadCourseRepository extends JpaRepository<UploadCourse, Long> {

}
