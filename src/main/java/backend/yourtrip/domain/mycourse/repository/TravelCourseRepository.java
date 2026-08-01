package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelCourseRepository extends JpaRepository<TravelCourse, Long> {

    @Query("""
        SELECT DISTINCT c
        FROM TravelCourse c
        LEFT JOIN FETCH c.daySchedules ds
        WHERE c.id = :courseId
        """)
    Optional<TravelCourse> findCourseWithDaySchedule(@Param("courseId") Long courseId);

    boolean existsById(Long courseId);

    boolean existsByIdAndUser_Id(Long courseId, Long userId);

    List<TravelCourse> findByUser_IdAndTypeNotOrderByUpdatedAtDesc(Long userId,
        TravelCourseType excludedType);
}
