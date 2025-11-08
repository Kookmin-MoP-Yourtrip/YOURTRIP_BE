package backend.yourtrip.domain.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record KakaoUserResponse(

    @Schema(description = "유저 고유 ID")
    Long userId,

    @Schema(description = "이메일 주소")
    String email,

    @Schema(description = "닉네임")
    String nickname,

    @Schema(description = "프로필 이미지 URL")
    String profileImageUrl,

    @Schema(description = "JWT 액세스 토큰")
    String accessToken
) {}
