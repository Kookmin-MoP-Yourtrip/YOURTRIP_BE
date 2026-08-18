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

    /**
     * 인기 코스 top-N(테마 무관). 아래 findPopularCourseIdsByTheme과 반드시 분리돼 있어야 한다.
     * <p>
     * 하나로 합쳐 {@code (:theme IS NULL OR EXISTS (...))} 형태로 쓰면 PostgreSQL이 EXISTS를
     * 세미조인으로 끌어올리지 못한다 — 서브쿼리 pull-up은 WHERE의 AND 트리와 NOT만 재귀하고
     * OR 아래는 들여다보지 않기 때문이다. 그러면 EXISTS가 hashed SubPlan으로 강등돼, LIMIT 5인데도
     * keyword_type이 일치하는 행 전부로 해시를 짓고 나서야 첫 행이 나온다. 해시 사용 여부는 비용
     * 비교가 아니라 work_mem 크기 판정이라 LIMIT으로 억제되지도 않는다.
     * <p>
     * <b>다시 하나로 합치지 말 것.</b> 전후 실측은 docs/tasks/popular-theme-index/README.md 참고.
     */
    @Query("""
            SELECT uc.id
            FROM UploadCourse uc
            ORDER BY uc.viewCount DESC
        """)
    List<Long> findPopularCourseIds(Pageable pageable);

    /**
     * 테마(mood 키워드)를 지정한 인기 코스 top-N.
     * <p>
     * EXISTS가 WHERE 최상위 AND에 있어야 세미조인으로 승격되고, 그래야 플래너가 "view_count 인덱스를
     * 내림차순으로 훑으며 행마다 course_keyword를 인덱스로 probe"하는 Nested Loop Semi Join을
     * 고를 수 있다 — LIMIT 5면 약 30행만에 끝나 규모와 무관해진다. 이 플랜은
     * {@code course_keyword(upload_course_id, keyword_type)} 인덱스가 있어야 성립한다
     * (CourseKeyword의 @Table(indexes=...) 참고).
     * <p>
     * EXISTS를 JOIN으로 바꾸지 말 것 — 한 코스가 같은 keywordType을 두 번 가지면 중복 행이 나오고,
     * 그걸 막으려 DISTINCT를 붙이면 top-N 인덱스 스캔이 Sort/Unique로 퇴화해 규모 의존성이 되살아난다.
     */
    @Query("""
            SELECT uc.id
            FROM UploadCourse uc
            WHERE EXISTS (
                SELECT 1 FROM CourseKeyword ck
                WHERE ck.uploadCourse = uc AND ck.keywordType = :theme
            )
            ORDER BY uc.viewCount DESC
        """)
    List<Long> findPopularCourseIdsByTheme(@Param("theme") KeywordType theme, Pageable pageable);

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
