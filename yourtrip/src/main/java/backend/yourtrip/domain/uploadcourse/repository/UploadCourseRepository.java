package backend.yourtrip.domain.uploadcourse.repository;

import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    Optional<UploadCourse> findByMyCourse(MyCourse myCourse);

    @Query("""
            SELECT uc
            FROM UploadCourse uc
            JOIN FETCH uc.myCourse mc
            WHERE uc.id = :uploadCourseId
        """)
    Optional<UploadCourse> findWithMyCourseById(Long uploadCourseId);

    @Query("""
            SELECT DISTINCT uc
            FROM UploadCourse uc
            LEFT JOIN FETCH uc.keywords kw
            WHERE (:keyword IS NULL
                   OR uc.location LIKE :keyword
                   OR uc.title LIKE :keyword)
              AND (:keywords IS NULL
                   OR EXISTS (
                        SELECT 1
                        FROM CourseKeyword ck
                        WHERE ck.uploadCourse = uc
                          AND ck.keywordType IN :keywords
                   ))
            ORDER BY uc.id DESC
        """)
    List<UploadCourse> findAllByKeywordsOrderByCreatedAtDesc(@Param("keyword") String keyword,
        @Param("keywords") List<KeywordType> keywords);

    @Query("""
            SELECT DISTINCT uc
            FROM UploadCourse uc
            LEFT JOIN FETCH uc.keywords kw
            WHERE (:keyword IS NULL
                   OR uc.location LIKE :keyword
                   OR uc.title LIKE :keyword)
              AND (:keywords IS NULL
                   OR EXISTS (
                        SELECT 1
                        FROM CourseKeyword ck
                        WHERE ck.uploadCourse = uc
                          AND ck.keywordType IN :keywords
                   ))
            ORDER BY uc.viewCount DESC
        """)
    List<UploadCourse> findAllByKeywordsOrderByViewCountDesc(@Param("keyword") String keyword,
        @Param("keywords") List<KeywordType> keywords);
}
