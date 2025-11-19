package backend.yourtrip.domain.uploadcourse.repository;

import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UploadCourseRepository extends JpaRepository<UploadCourse, Long> {

    @Query("""
            SELECT uc
            FROM UploadCourse uc
            JOIN FETCH uc.myCourse
            LEFT JOIN FETCH uc.keywords kw
            WHERE uc.id = :uploadCourseId
        """)
    Optional<UploadCourse> findWithMyCourseAndKeywords(Long uploadCourseId);

    @Query("""
            SELECT uc
            FROM UploadCourse uc
            JOIN FETCH uc.keywords
            ORDER BY uc.createdAt DESC
        """)
    List<UploadCourse> findAllOrderByCreatedAtDesc();

    @Query("""
            SELECT uc
            FROM UploadCourse uc
            JOIN FETCH uc.keywords
            ORDER BY uc.viewCount DESC
        """)
    List<UploadCourse> findAllOrderByViewCountDesc();

    @Query("""
            SELECT uc
            FROM UploadCourse uc
            JOIN FETCH uc.myCourse mc
            ORDER BY uc.viewCount DESC
            LIMIT 5
        """)
    List<UploadCourse> findFiveOrderByViewCountDesc();

    Optional<UploadCourse> findByMyCourse(MyCourse myCourse);

    @Query("""
            SELECT uc
            FROM UploadCourse uc
            JOIN FETCH uc.myCourse mc
            WHERE uc.id = :uploadCourseId
        """)
    Optional<UploadCourse> findWithMyCourseById(Long uploadCourseId);
}
