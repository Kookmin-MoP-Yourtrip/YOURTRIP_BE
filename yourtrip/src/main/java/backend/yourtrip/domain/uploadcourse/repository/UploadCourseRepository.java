package backend.yourtrip.domain.uploadcourse.repository;

import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UploadCourseRepository extends JpaRepository<UploadCourse, Long> {

    @Query("""
            SELECT uc
            FROM UploadCourse uc
            JOIN FETCH uc.user
            JOIN FETCH uc.myCourse
            LEFT JOIN FETCH uc.keywords kw
            WHERE uc.id = :uploadCourseId
        """)
    Optional<UploadCourse> findUploadCourseWithMyCourseAndUserAndKeywords(Long uploadCourseId);

}
