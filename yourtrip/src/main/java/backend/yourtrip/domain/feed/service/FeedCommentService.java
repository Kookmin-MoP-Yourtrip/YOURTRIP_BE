package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCommentRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCommentResponse;

import java.util.List;

public interface FeedCommentService {

    List<FeedCommentResponse> getComments(Long feedId);

    FeedCommentResponse addComment(Long feedId, FeedCommentRequest request);

    void deleteComment(Long feedId, Long commentId);
}