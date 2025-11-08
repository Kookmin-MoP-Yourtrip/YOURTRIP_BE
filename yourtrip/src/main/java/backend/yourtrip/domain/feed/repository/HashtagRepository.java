package backend.yourtrip.domain.feed.repository;

import backend.yourtrip.domain.feed.entity.Hashtag;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HashtagRepository extends JpaRepository<Hashtag, Long> {
}
