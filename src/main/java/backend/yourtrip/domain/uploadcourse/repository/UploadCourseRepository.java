package backend.yourtrip.domain.uploadcourse.repository;

import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UploadCourseRepository extends JpaRepository<UploadCourse, Long> {

    @Query("""
            SELECT uc
            FROM UploadCourse uc
            JOIN FETCH uc.travelCourse
            LEFT JOIN FETCH uc.keywords kw
            WHERE uc.id = :uploadCourseId
        """)
    Optional<UploadCourse> findWithTravelCourseAndKeywords(Long uploadCourseId);

    @Query("""
            SELECT uc
            FROM UploadCourse uc
            JOIN FETCH uc.travelCourse tc
            WHERE uc.id = :uploadCourseId
        """)
    Optional<UploadCourse> findWithTravelCourseById(Long uploadCourseId);

    @Query("""
            SELECT DISTINCT uc
            FROM UploadCourse uc
            LEFT JOIN FETCH uc.keywords kw
            WHERE (:keyword IS NULL
                   OR uc.location LIKE :keyword
                   OR uc.title LIKE :keyword)
              AND (:keywords IS NULL
                   OR (SELECT COUNT(DISTINCT ck.keywordType)
                       FROM CourseKeyword ck
                       WHERE ck.uploadCourse = uc
                         AND ck.keywordType IN :keywords) = :keywordsCount)
            ORDER BY uc.id DESC
        """)
    List<UploadCourse> findAllByKeywordsOrderByCreatedAtDesc(@Param("keyword") String keyword,
        @Param("keywords") List<KeywordType> keywords, @Param("keywordsCount") int keywordsCount);

    @Query("""
            SELECT DISTINCT uc
            FROM UploadCourse uc
            LEFT JOIN FETCH uc.keywords kw
            WHERE (:keyword IS NULL
                   OR uc.location LIKE :keyword
                   OR uc.title LIKE :keyword)
              AND (:keywords IS NULL
                 OR (SELECT COUNT(DISTINCT ck.keywordType)
                   FROM CourseKeyword ck
                   WHERE ck.uploadCourse = uc
                     AND ck.keywordType IN :keywords) = :keywordsCount)
            ORDER BY uc.viewCount DESC
        """)
    List<UploadCourse> findAllByKeywordsOrderByViewCountDesc(@Param("keyword") String keyword,
        @Param("keywords") List<KeywordType> keywords, @Param("keywordsCount") int keywordsCount);

    @Query("""
            SELECT uc
            FROM UploadCourse uc
            LEFT JOIN FETCH uc.keywords kw
            WHERE uc.user.id = :userId
            ORDER BY uc.id DESC
        """)
    List<UploadCourse> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("""
            SELECT uc.id
            FROM UploadCourse uc
            WHERE (:theme IS NULL
                   OR EXISTS (
                       SELECT 1 FROM CourseKeyword ck
                       WHERE ck.uploadCourse = uc AND ck.keywordType = :theme
                   ))
            ORDER BY uc.viewCount DESC
        """)
    List<Long> findPopularCourseIds(@Param("theme") KeywordType theme, Pageable pageable);

    @Query("""
            SELECT DISTINCT uc
            FROM UploadCourse uc
            LEFT JOIN FETCH uc.keywords
            WHERE uc.id IN :ids
        """)
    List<UploadCourse> findAllByIdInWithKeywords(@Param("ids") List<Long> ids);

    /**
     * 청크 전체(최대 1,000건)의 증분을 SQL 한 문장으로 반영해 DB 왕복을 청크당 1회로 보장한다.
     * ids/increments는 병렬 배열(같은 인덱스가 한 코스의 id/증분 쌍)로 unnest()해 조인한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE upload_course uc
        SET view_count = uc.view_count + v.increment
        FROM (SELECT unnest(:ids) AS id, unnest(:increments) AS increment) AS v
        WHERE uc.upload_course_id = v.id
        """, nativeQuery = true)
    void incrementViewCounts(@Param("ids") Long[] ids, @Param("increments") Long[] increments);
}
