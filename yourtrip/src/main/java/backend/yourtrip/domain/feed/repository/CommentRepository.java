package backend.yourtrip.domain.feed.repository;

import backend.yourtrip.domain.feed.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    Page<Comment> findByFeedIdAndDeletedFalse(Long feedId, Pageable pageable);
}
