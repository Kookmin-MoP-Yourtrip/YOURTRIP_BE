package backend.yourtrip.domain.feed.repository;

import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.FeedComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedCommentRepository extends JpaRepository<FeedComment, Long> {

    List<FeedComment> findByFeedAndDeletedFalse(Feed feed);
}