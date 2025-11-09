package backend.yourtrip.domain.feed.repository;

import backend.yourtrip.domain.feed.entity.Feed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FeedRepository extends JpaRepository<Feed, Long> {
    @Query("""
        SELECT DISTINCT f
        FROM Feed f
        LEFT JOIN FETCH f.hashtags ht
        WHERE f.id = :feedId
        """)
    Optional<Feed> findFeedWithHashtag(@Param("feedId") Long feedId);

    @Query("""
        SELECT DISTINCT f
        FROM Feed f
        LEFT JOIN FETCH f.hashtags ht
        """)
    List<Feed> findAllFeedWithHashtag();

    @Query("""
        SELECT DISTINCT f
        FROM Feed f
        LEFT JOIN FETCH f.hashtags ht
        WHERE f.user.id = :userId
        """)
    List<Feed> findFeedByUserIdWithHashtag(@Param("userId") Long userId);

    @Query("""
        SELECT DISTINCT f
        FROM Feed f
        LEFT JOIN FETCH f.hashtags ht
        WHERE f.title LIKE CONCAT('%', :keyword, '%')
                 OR f.location LIKE CONCAT('%', :keyword, '%')
        """)
    List<Feed> findFeedByKeywordWithHashtag(@Param("keyword") String keyword);
}
