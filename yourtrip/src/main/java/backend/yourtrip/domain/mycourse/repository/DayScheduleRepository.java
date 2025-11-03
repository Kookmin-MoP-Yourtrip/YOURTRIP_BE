package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DayScheduleRepository extends JpaRepository<DaySchedule, Long> {

    Optional<DaySchedule> findByDay(int day);
}
