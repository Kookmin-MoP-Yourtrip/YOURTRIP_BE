package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.MyCourse;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyCourseRepository extends JpaRepository<MyCourse,Long> {

}
