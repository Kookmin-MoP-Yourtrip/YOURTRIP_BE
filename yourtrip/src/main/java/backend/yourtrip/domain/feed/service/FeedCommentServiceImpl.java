package backend.yourtrip.domain.feed.service;


import backend.yourtrip.domain.feed.dto.request.FeedCommentCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedCommentUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentUpdateResponse;
import backend.yourtrip.domain.feed.entity.Comment;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.mapper.CommentMapper;
import backend.yourtrip.domain.feed.repository.CommentRepository;
import backend.yourtrip.domain.feed.repository.FeedRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.FeedCommentErrorCode;
import backend.yourtrip.global.exception.errorCode.FeedCommentResponseCode;
import backend.yourtrip.global.exception.errorCode.FeedErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedCommentServiceImpl implements FeedCommentService{

    private final CommentRepository commentRepository;
    private final FeedRepository feedRepository;
    private final UserService userService;
    private final CommentMapper commentMapper;

    @Override
    @Transactional
    public FeedCommentCreateResponse saveComment(Long feedId, FeedCommentCreateRequest request) {

        if (request.sentence() == null || request.sentence().trim().isEmpty()) {
            throw new BusinessException(FeedCommentErrorCode.COMMENT_SENTENCE_REQUIRED);
        }

        Feed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        Comment comment = commentMapper.toEntity(feed, user, request);
        Comment savedComment = commentRepository.save(comment);

        feed.increaseCommentCount();

        return new FeedCommentCreateResponse(savedComment.getId(), FeedCommentResponseCode.FEED_COMMENT_CREATED.getMessage());
    }

    @Override
    @Transactional(readOnly = true)
    public FeedCommentListResponse getCommentsByFeedId(Long feedId, int page, int size) {

        feedRepository.findById(feedId)
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Comment> commentPage = commentRepository.findByFeedIdAndDeletedFalse(feedId, pageable);

        return commentMapper.toListResponse(commentPage);
    }

    @Override
    @Transactional
    public FeedCommentUpdateResponse updateComment(Long commentId, FeedCommentUpdateRequest request) {

        if (request.sentence() == null || request.sentence().trim().isEmpty()) {
            throw new BusinessException(FeedCommentErrorCode.COMMENT_SENTENCE_REQUIRED);
        }

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(FeedCommentErrorCode.COMMENT_NOT_FOUND));

        Long currentUserId = userService.getCurrentUserId();

        if (!comment.getUser().getId().equals(currentUserId)) {
            throw new BusinessException(FeedCommentErrorCode.COMMENT_UPDATE_NOT_AUTHORIZED);
        }

        comment.updateSentence(request.sentence());

        return new FeedCommentUpdateResponse(
                comment.getId(),
                FeedCommentResponseCode.FEED_COMMENT_UPDATED.getMessage()
        );
    }

    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(FeedCommentErrorCode.COMMENT_NOT_FOUND));

        Long currentUserId = userService.getCurrentUserId();
        if (!comment.getUser().getId().equals(currentUserId)) {
            throw new BusinessException(FeedCommentErrorCode.COMMENT_DELETE_NOT_AUTHORIZED);
        }
        comment.delete();
        comment.getFeed().decreaseCommentCount();
    }
}
