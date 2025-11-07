package backend.yourtrip.domain.feed.repository;

import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.mycourse.entity.MyCourse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FeedRepository extends JpaRepository<Feed, Long> {
    @Query("""
        SELECT DISTINCT f
        FROM Feed f
        LEFT JOIN FETCH f.hashtags ht
        WHERE f.id = :feedId
        """)
    Optional<Feed> findFeedWithHashtag(@Param("feedId") Long feedId);
}
