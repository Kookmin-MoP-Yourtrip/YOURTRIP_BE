package backend.yourtrip.domain.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "최종 회원가입 완료 응답 DTO")
public record UserSignupResponse(
    @Schema(description = "회원 고유 ID", example = "1")
    Long userId,

    @Schema(description = "이메일", example = "user@example.com")
    String email,

    @Schema(description = "닉네임", example = "여행러버")
    String nickname,

    @Schema(description = "프로필 이미지 URL",
        example = "https://yourtrip-bucket.s3.ap-northeast-2.amazonaws.com/default-profile.png")
    String profileImageUrl,

    @Schema(description = "가입 일시", example = "2025-11-11T10:00:00")
    LocalDateTime createdAt
) {}