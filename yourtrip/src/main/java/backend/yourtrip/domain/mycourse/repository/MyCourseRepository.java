package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.MyCourse;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MyCourseRepository extends JpaRepository<MyCourse, Long> {

    @Query("""
        SELECT DISTINCT c
        FROM MyCourse c
        JOIN c.participants p
        LEFT JOIN FETCH c.daySchedules ds
        WHERE c.id = :courseId
            AND p.user.id = :userId
        """)
    Optional<MyCourse> findOwnedDetail(@Param("courseId") Long courseId,
        @Param("userId") Long userId);
}
