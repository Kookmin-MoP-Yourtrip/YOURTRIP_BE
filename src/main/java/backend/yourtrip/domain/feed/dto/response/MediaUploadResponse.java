package backend.yourtrip.domain.feed.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record MediaUploadResponse(
        @Schema(description = "업로드된 미디어 S3 키 리스트",
                example = "[\"uploads/2025-11-20/abc123.jpg\", \"uploads/2025-11-20/xyz789.mp4\"]")
        List<String> s3Keys
) {
}
