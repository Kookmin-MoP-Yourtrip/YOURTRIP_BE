package backend.yourtrip.domain.heart.repository;

import backend.yourtrip.domain.heart.entity.FeedHeart;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedHeartRepository extends JpaRepository<FeedHeart, Long> {

    List<FeedHeart> findByUser(User user);

    boolean existsByUserAndFeed(User user, Feed feed);
}