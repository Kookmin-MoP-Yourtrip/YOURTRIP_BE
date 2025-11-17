package backend.yourtrip.domain.feed.mapper;


import backend.yourtrip.domain.feed.dto.request.FeedCommentCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.entity.Comment;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.user.entity.User;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.stream.Collectors;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CommentMapper {

    public static Comment toEntity(Feed feed, User user, FeedCommentCreateRequest request) {
        return Comment.builder()
                .feed(feed)
                .user(user)
                .sentence(request.sentence())
                .build();
    }

    public static FeedCommentDetailResponse toDetailResponse(Comment comment) {
        return FeedCommentDetailResponse.builder()
                .feedCommentId(comment.getId())
                .feedId(comment.getFeed().getId())
                .userId(comment.getUser().getId())
                .nickname(comment.getUser().getNickname())
                .profileImageUrl(comment.getUser().getProfileImageS3Key())
                .sentence(comment.getSentence())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
    }

    public static FeedCommentListResponse toListResponse(Page<Comment> commentPage) {
        List<FeedCommentDetailResponse> comments = commentPage.getContent().stream()
                .map(CommentMapper::toDetailResponse)
                .collect(Collectors.toList());

        return FeedCommentListResponse.builder()
                .comments(comments)
                .totalPages(commentPage.getTotalPages())
                .totalElements(commentPage.getTotalElements())
                .currentPage(commentPage.getNumber())
                .pageSize(commentPage.getSize())
                .build();
    }
}
