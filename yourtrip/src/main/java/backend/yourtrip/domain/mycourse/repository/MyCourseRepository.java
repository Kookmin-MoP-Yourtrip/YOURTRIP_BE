package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MyCourseRepository extends JpaRepository<MyCourse, Long> {

    @Query("""
        SELECT DISTINCT c
        FROM MyCourse c
        LEFT JOIN FETCH c.daySchedules ds
        WHERE c.id = :courseId
        """)
    Optional<MyCourse> findCourseWithDaySchedule(@Param("courseId") Long courseId);

    boolean existsById(Long courseId);
}
