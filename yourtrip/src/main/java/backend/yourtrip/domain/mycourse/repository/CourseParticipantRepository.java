package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.myCourse.CourseParticipant;
import backend.yourtrip.domain.mycourse.entity.myCourse.enums.CourseRole;
import backend.yourtrip.domain.user.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseParticipantRepository extends JpaRepository<CourseParticipant, Long> {

    @Query("""
        SELECT cp
        FROM CourseParticipant cp
        JOIN FETCH cp.course c
        WHERE cp.user = :user
        ORDER BY c.updatedAt DESC
        """)
    List<CourseParticipant> findByUserOrderByCourseUpdatedAtDesc(@Param("user") User user);

    @Query("""
            SELECT p.role
            FROM CourseParticipant p
            WHERE p.user.id = :userId
                AND p.course.id = :courseId
        """)
    Optional<CourseRole> findRole(@Param("userId") Long userId, @Param("courseId") Long courseId);

    boolean existsByUser_IdAndCourse_Id(Long userId, Long courseId);


}
