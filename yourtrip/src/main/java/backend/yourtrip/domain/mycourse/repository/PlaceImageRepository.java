package backend.yourtrip.domain.mycourse.repository;

import backend.yourtrip.domain.mycourse.entity.place.PlaceImage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceImageRepository extends JpaRepository<PlaceImage, Long> {

    Optional<PlaceImage> findByIdAndPlace_Id(Long imageId, Long placeId);
}
