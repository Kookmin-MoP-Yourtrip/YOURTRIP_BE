package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCommentRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.FeedComment;
import backend.yourtrip.domain.feed.repository.FeedCommentRepository;
import backend.yourtrip.domain.feed.repository.FeedRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.FeedErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FeedCommentServiceImpl implements FeedCommentService {

    private final FeedRepository feedRepository;
    private final FeedCommentRepository commentRepository;
    private final UserService userService;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    @Transactional(readOnly = true)
    public List<FeedCommentResponse> getComments(Long feedId) {

        Feed feed = feedRepository.findById(feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        List<FeedComment> comments = commentRepository.findByFeedAndDeletedFalse(feed);

        return comments.stream()
            .map(c -> FeedCommentResponse.builder()
                .commentId(c.getId())
                .userId(c.getUser().getId())
                .nickname(c.getUser().getNickname())
                .content(c.getContent())
                .createdAt(c.getCreatedAt().format(formatter))
                .build())
            .toList();
    }

    @Override
    @Transactional
    public FeedCommentResponse addComment(Long feedId, FeedCommentRequest request) {

        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        Feed feed = feedRepository.findById(feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        FeedComment comment = FeedComment.builder()
            .feed(feed)
            .user(user)
            .content(request.content())
            .deleted(false)
            .build();

        commentRepository.save(comment);

        feed.increaseCommentCount();

        return FeedCommentResponse.builder()
            .commentId(comment.getId())
            .userId(user.getId())
            .nickname(user.getNickname())
            .content(comment.getContent())
            .createdAt(comment.getCreatedAt().format(formatter))
            .build();
    }

    @Override
    @Transactional
    public void deleteComment(Long feedId, Long commentId) {

        FeedComment comment = commentRepository.findById(commentId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.COMMENT_NOT_FOUND));

        if (!comment.getFeed().getId().equals(feedId)) {
            throw new BusinessException(FeedErrorCode.INVALID_COMMENT_ACCESS);
        }

        comment.delete();

        Feed feed = comment.getFeed();
        feed.decreaseCommentCount();
    }
}