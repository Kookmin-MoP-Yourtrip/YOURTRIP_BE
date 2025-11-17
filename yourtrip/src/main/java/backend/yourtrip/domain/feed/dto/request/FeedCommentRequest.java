package backend.yourtrip.domain.feed.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record FeedCommentRequest(
    @Schema(example = "사진 진짜 잘 나왔어요!")
    @NotBlank(message = "댓글 내용은 비어 있을 수 없습니다.")
    String content
) {}