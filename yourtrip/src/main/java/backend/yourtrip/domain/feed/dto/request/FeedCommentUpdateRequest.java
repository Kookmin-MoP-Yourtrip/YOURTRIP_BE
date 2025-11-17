package backend.yourtrip.domain.feed.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "피드 댓글 수정 요청 DTO")
public record FeedCommentUpdateRequest(

    @Schema(example = "내용 살짝 수정해봤어요!", description = "수정할 댓글 내용")
    @NotBlank(message = "댓글 내용은 비어 있을 수 없습니다.")
    String content
) {
}