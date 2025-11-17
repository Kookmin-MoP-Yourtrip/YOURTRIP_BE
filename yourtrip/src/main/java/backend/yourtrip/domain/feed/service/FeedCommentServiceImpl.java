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

    // =========================================================
    // 1. 댓글 생성
    // =========================================================
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

        FeedComment saved = feedCommentRepository.save(comment);

        // 댓글 수 증가
        feed.increaseCommentCount();

        return toResponse(saved);
    }

    // =========================================================
    // 2. 댓글 리스트 조회 (페이징)
    // =========================================================
    @Override
    @Transactional(readOnly = true)
    public FeedCommentListResponse getComments(Long feedId, int page, int size) {
        Feed feed = feedRepository.findById(feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        PageRequest pageable =
            PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));

        Page<FeedComment> commentPage =
            feedCommentRepository.findByFeedIdAndDeletedFalse(feed.getId(), pageable);

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

    // =========================================================
    // 3. 댓글 단건 조회
    // =========================================================
    @Override
    @Transactional(readOnly = true)
    public FeedCommentResponse getComment(Long feedId, Long commentId) {
        FeedComment comment = feedCommentRepository
            .findByIdAndFeedIdAndDeletedFalse(commentId, feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND)); // 댓글용 에러코드 따로 만들거면 여기 바꾸면 됨

        return toResponse(comment);
    }

    // =========================================================
    // 4. 댓글 수정
    // =========================================================
    @Override
    @Transactional
    public FeedCommentResponse updateComment(
        Long feedId,
        Long commentId,
        FeedCommentUpdateRequest request
    ) {
        FeedComment comment = feedCommentRepository
            .findByIdAndFeedIdAndDeletedFalse(commentId, feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        Long currentUserId = userService.getCurrentUserId();
        if (!comment.getUser().getId().equals(currentUserId)) {
            throw new BusinessException(FeedErrorCode.FEED_NOT_FOUND);
        }

        comment.updateContent(request.content());

        return toResponse(comment);
    }

    // =========================================================
    // 5. 댓글 삭제
    // =========================================================
    @Override
    @Transactional
    public void deleteComment(Long feedId, Long commentId) {
        FeedComment comment = feedCommentRepository
            .findByIdAndFeedIdAndDeletedFalse(commentId, feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        Long currentUserId = userService.getCurrentUserId();
        if (!comment.getUser().getId().equals(currentUserId)) {
            throw new BusinessException(FeedErrorCode.FEED_NOT_FOUND);
        }

        comment.delete();
        comment.getFeed().decreaseCommentCount();
    }

    // =========================================================
    // 내부 매퍼: 엔티티 -> 응답 DTO
    // =========================================================
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