package backend.yourtrip.domain.heart.repository;

import backend.yourtrip.domain.heart.entity.FeedHeart;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.mypage.dto.response.LikedFeedResponse;
import backend.yourtrip.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FeedHeartRepository extends JpaRepository<FeedHeart, Long> {

    List<FeedHeart> findByUser(User user);

    boolean existsByUserAndFeed(User user, Feed feed);

    @Query("""
        SELECT new backend.yourtrip.domain.mypage.dto.response.LikedFeedResponse(
            fh.feed.id,
            fh.feed.title,
            fh.feed.location,
            null,
            fh.feed.heartCount,
            fh.feed.commentCount
        )
        FROM FeedHeart fh
        WHERE fh.user.id = :userId
        ORDER BY fh.id DESC
    """)
    List<LikedFeedResponse> findLikedFeeds(@Param("userId") Long userId);

}