package backend.yourtrip.domain.feed.repository;

import backend.yourtrip.domain.feed.entity.FeedComment;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedCommentRepository extends JpaRepository<FeedComment, Long> {

    Page<FeedComment> findByFeedIdAndDeletedFalse(Long feedId, Pageable pageable);

    Optional<FeedComment> findByIdAndFeedIdAndDeletedFalse(Long commentId, Long feedId);
}