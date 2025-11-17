package backend.yourtrip.domain.heart.repository;

import backend.yourtrip.domain.heart.entity.UploadCourseHeart;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UploadCourseHeartRepository extends JpaRepository<UploadCourseHeart, Long> {

    List<UploadCourseHeart> findByUser(User user);

    boolean existsByUserAndUploadCourse(User user, UploadCourse uploadCourse);
}