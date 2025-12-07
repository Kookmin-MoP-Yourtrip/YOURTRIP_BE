package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.place.Place;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    Optional<Place> findByIdAndDaySchedule_Id(Long placeId, Long dayScheduleId);
}
