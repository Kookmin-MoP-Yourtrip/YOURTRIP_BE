package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCommentRequest;
import backend.yourtrip.domain.feed.dto.request.FeedCommentUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentResponse;

public interface FeedCommentService {

    FeedCommentResponse createComment(Long feedId, FeedCommentRequest request);

    FeedCommentListResponse getComments(Long feedId, int page, int size);

    FeedCommentResponse getComment(Long feedId, Long commentId);

    FeedCommentResponse updateComment(Long feedId, Long commentId, FeedCommentUpdateRequest request);

    void deleteComment(Long feedId, Long commentId);
}