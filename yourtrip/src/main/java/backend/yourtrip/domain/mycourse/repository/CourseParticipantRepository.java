package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.CourseParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseParticipantRepository extends JpaRepository<CourseParticipant, Long> {

}
