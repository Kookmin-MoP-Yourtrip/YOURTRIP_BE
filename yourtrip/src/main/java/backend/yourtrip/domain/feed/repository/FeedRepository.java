package backend.yourtrip.domain.feed.repository;

import backend.yourtrip.domain.feed.entity.Feed;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface FeedRepository extends JpaRepository<Feed, Long> {
    @Query("""
        SELECT DISTINCT f
        FROM Feed f
        LEFT JOIN FETCH f.hashtags ht
        WHERE f.id = :feedId
        """)
    Optional<Feed> findFeedWithHashtag(@Param("feedId") Long feedId);

    @EntityGraph(attributePaths = {"hashtags", "user"})
    Page<Feed> findAll(Pageable pageable);

    @EntityGraph(attributePaths = {"hashtags", "user"})
    Page<Feed> findByUser_Id(@Param("userId") Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"hashtags", "user"})
    @Query("""
        SELECT DISTINCT f
        FROM Feed f
        WHERE f.title LIKE CONCAT('%', :keyword, '%')
                 OR f.location LIKE CONCAT('%', :keyword, '%')
        """)
    Page<Feed> findByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
