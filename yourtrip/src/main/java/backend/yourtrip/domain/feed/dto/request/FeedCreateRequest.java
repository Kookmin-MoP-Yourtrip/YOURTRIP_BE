package backend.yourtrip.domain.feed.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record FeedCreateRequest(
        @Schema(description = "피드 제목", example = "경주 여행 후기")
        @NotBlank(message = "피드 제목은 필수 입력 항목입니다.")
        String title,

        @Schema(description = "위치", example = "경주")
        @NotBlank(message = "위치는 필수 입력 항목입니다.")
        String location,

        @Schema(description = "피드 내용", example = "경주를 다녀왔습니다.")
        String content,

        @Schema(description = "피드 해시태그", example = "[\"경주\", \"여행\", \"맛집\"]")
        List<String> hashtags,

        @Schema(description = "피드 관련 업로드 코스 ID(선택 사항", nullable = true)
        Long uploadCourseId
) {
}
