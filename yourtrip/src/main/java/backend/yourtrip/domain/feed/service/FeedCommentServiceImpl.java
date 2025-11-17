package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCommentRequest;
import backend.yourtrip.domain.feed.dto.request.FeedCommentUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentResponse;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.FeedComment;
import backend.yourtrip.domain.feed.repository.FeedCommentRepository;
import backend.yourtrip.domain.feed.repository.FeedRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.FeedErrorCode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedCommentServiceImpl implements FeedCommentService {

    private final FeedCommentRepository feedCommentRepository;
    private final FeedRepository feedRepository;
    private final UserService userService;

    private static final DateTimeFormatter COMMENT_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    @Transactional
    public FeedCommentResponse createComment(Long feedId, FeedCommentRequest request) {
        Feed feed = feedRepository.findById(feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        Long currentUserId = userService.getCurrentUserId();
        User user = userService.getUser(currentUserId);

        FeedComment comment = FeedComment.builder()
            .feed(feed)
            .user(user)
            .content(request.content())
            .deleted(false)
            .build();

        FeedComment savedComment = feedCommentRepository.save(comment);

        feed.increaseCommentCount();

        return toResponse(savedComment);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedCommentListResponse getComments(Long feedId, int page, int size) {
        Feed feed = feedRepository.findById(feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        PageRequest pageable =
            PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));

        Page<FeedComment> commentPage =
            feedCommentRepository.findByFeedIdAndDeletedFalse(feedId, pageable);

        List<FeedCommentResponse> responses = commentPage.getContent().stream()
            .map(this::toResponse)
            .toList();

        return new FeedCommentListResponse(
            responses,
            commentPage.getNumber(),
            commentPage.getTotalPages(),
            commentPage.getTotalElements(),
            commentPage.hasNext(),
            commentPage.hasPrevious()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public FeedCommentResponse getComment(Long feedId, Long commentId) {
        FeedComment comment = feedCommentRepository
            .findByIdAndFeedIdAndDeletedFalse(commentId, feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        return toResponse(comment);
    }

    @Override
    @Transactional
    public FeedCommentResponse updateComment(Long feedId, Long commentId, FeedCommentUpdateRequest request) {
        FeedComment comment = feedCommentRepository
            .findByIdAndFeedIdAndDeletedFalse(commentId, feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        Long currentUserId = userService.getCurrentUserId();
        if (!comment.getUser().getId().equals(currentUserId)) {
            throw new BusinessException(FeedErrorCode.FEED_UPDATE_NOT_AUTHORIZED);
        }

        comment.updateContent(request.content());

        return toResponse(comment);
    }

    @Override
    @Transactional
    public void deleteComment(Long feedId, Long commentId) {
        FeedComment comment = feedCommentRepository
            .findByIdAndFeedIdAndDeletedFalse(commentId, feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        Long currentUserId = userService.getCurrentUserId();
        if (!comment.getUser().getId().equals(currentUserId)) {
            throw new BusinessException(FeedErrorCode.FEED_DELETE_NOT_AUTHORIZED);
        }

        comment.delete();
        comment.getFeed().decreaseCommentCount();
    }

    private FeedCommentResponse toResponse(FeedComment comment) {
        return FeedCommentResponse.builder()
            .commentId(comment.getId())
            .userId(comment.getUser().getId())
            .nickname(comment.getUser().getNickname())
            .content(comment.getContent())
            .createdAt(comment.getCreatedAt().format(COMMENT_TIME_FORMATTER))
            .build();
    }
}