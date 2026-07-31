package backend.yourtrip.domain.feed.repository;

import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.FeedLike;
import backend.yourtrip.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FeedLikeRepository extends JpaRepository<FeedLike, Long> {
    Optional<FeedLike> findByUserAndFeed(User user, Feed feed);
    boolean existsByUserAndFeed(User user, Feed feed);

    @Modifying
    @Query("DELETE FROM FeedLike fl WHERE fl.user = :user AND fl.feed = :feed")
    int deleteByUserAndFeed(@Param("user") User user, @Param("feed") Feed feed);

    List<FeedLike> findByUserAndFeed_IdIn(User user, List<Long> feedIds);
}
