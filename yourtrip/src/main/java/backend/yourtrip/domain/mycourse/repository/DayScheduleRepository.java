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
            WHERE p.user.id = :userId
                AND c.id = :courseId
                AND ds.id = :dayId
        """)
    Optional<DaySchedule> findByIdAndUserId(@Param("userId") Long userId,
        @Param("dayId") Long dayId, @Param("courseId") Long courseId);

    @Query("""
            SELECT ds
            FROM DaySchedule ds
            LEFT JOIN FETCH ds.places
            WHERE ds.course.id = :courseId
        """
    )
    List<DaySchedule> findDaySchedulesWithPlaces(Long courseId);

    @Query("""
        select distinct ds
        from DaySchedule ds
        left join fetch ds.places p
        where ds.id = :dayId
            and ds.course.id = :courseId
        order by p.id
        """)
    Optional<DaySchedule> findByIdWithPlaces(@Param("courseId") Long courseId,
        @Param("dayId") Long daId);

    boolean existsByIdAndCourse_Id(Long dayId, Long courseId);

}
