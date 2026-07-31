package backend.yourtrip.domain.feed.repository;

import backend.yourtrip.domain.feed.entity.Hashtag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HashtagRepository extends JpaRepository<Hashtag, Long> {
}
