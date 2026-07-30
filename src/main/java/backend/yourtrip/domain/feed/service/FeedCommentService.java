package backend.yourtrip.domain.feed.service;


import backend.yourtrip.domain.feed.dto.request.FeedCommentCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedCommentUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentCreateResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentListResponse;
import backend.yourtrip.domain.feed.dto.response.FeedCommentUpdateResponse;

public interface FeedCommentService {
    FeedCommentCreateResponse saveComment(Long feedId, FeedCommentCreateRequest request);
    FeedCommentListResponse getCommentsByFeedId(Long feedId, int page, int size);
    FeedCommentUpdateResponse updateComment(Long commentId, FeedCommentUpdateRequest request);
    void deleteComment(Long commentId);
}
