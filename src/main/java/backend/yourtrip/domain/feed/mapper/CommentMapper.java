package backend.yourtrip.domain.feed.mapper;


import backend.yourtrip.domain.feed.dto.request.FeedCommentCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentDetailResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.entity.Comment;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.s3.service.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CommentMapper {

    private final S3Service s3Service;

    public Comment toEntity(Feed feed, User user, FeedCommentCreateRequest request) {
        return Comment.builder()
                .feed(feed)
                .user(user)
                .sentence(request.sentence())
                .build();
    }

    public FeedCommentDetailResponse toDetailResponse(Comment comment) {

        String profileImageUrl = null;
        User user = comment.getUser();
        if (user != null && user.getProfileImageS3Key() != null && !user.getProfileImageS3Key().isBlank()) {
            profileImageUrl = s3Service.getPresignedUrl(user.getProfileImageS3Key());
        }

        return FeedCommentDetailResponse.builder()
                .feedCommentId(comment.getId())
                .feedId(comment.getFeed().getId())
                .userId(comment.getUser().getId())
                .nickname(comment.getUser().getNickname())
                .profileImageUrl(profileImageUrl)
                .sentence(comment.getSentence())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
    }

    public FeedCommentListResponse toListResponse(Page<Comment> commentPage) {
        List<FeedCommentDetailResponse> comments = commentPage.getContent().stream()
                .map(this::toDetailResponse)
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
