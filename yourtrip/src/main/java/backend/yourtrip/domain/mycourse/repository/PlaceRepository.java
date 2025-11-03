package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.dayschedule.Place;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceRepository extends JpaRepository<Place, Long> {

}
