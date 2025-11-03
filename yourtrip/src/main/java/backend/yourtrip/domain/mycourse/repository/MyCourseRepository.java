package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.MyCourse;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MyCourseRepository extends JpaRepository<MyCourse, Long> {

    @Query("""
        SELECT mc FROM MyCourse mc
        JOIN FETCH mc.daySchedules ds
        JOIN FETCH ds.places
        WHERE mc.id = :courseId
        """)
    Optional<MyCourse> findByIdWithSchedulesAndPlaces(@Param("courseId") Long courseId);
}
