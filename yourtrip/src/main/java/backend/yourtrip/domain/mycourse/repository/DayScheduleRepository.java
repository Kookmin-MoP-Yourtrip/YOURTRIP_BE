package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DayScheduleRepository extends JpaRepository<DaySchedule, Long> {

    @Query("""
            SELECT ds
            FROM DaySchedule ds
            JOIN ds.course c
            JOIN c.participants p
            WHERE c.id = :courseId
                AND p.user.id = :userId
                AND ds.day = :day
        """)
    Optional<DaySchedule> findOwnedByCourseIdAndDay(@Param("courseId") Long courseId,
        @Param("userId") Long userId, @Param("day") int day);

    @Query("""
            SELECT ds
            FROM DaySchedule ds
            LEFT JOIN FETCH ds.places
            WHERE ds.course.id = :courseId
        """
    )
    List<DaySchedule> findDaySchedulesWithPlaces(Long courseId);

}
