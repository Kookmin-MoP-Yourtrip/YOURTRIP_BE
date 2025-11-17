package backend.yourtrip.domain.feed.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record FeedCommentResponse(
    @Schema(example = "10")
    Long commentId,

    @Schema(example = "3")
    Long userId,

    @Schema(example = "여행덕후")
    String nickname,

    @Schema(example = "와 여기 진짜 예쁘네요!")
    String content,

    @Schema(example = "2025-11-22 18:10")
    String createdAt
) {}