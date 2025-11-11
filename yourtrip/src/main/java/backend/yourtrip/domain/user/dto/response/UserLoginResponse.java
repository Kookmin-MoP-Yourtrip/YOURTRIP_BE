package backend.yourtrip.domain.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 및 토큰 재발급 응답 DTO")
public record UserLoginResponse(
    @Schema(description = "회원 고유 ID", example = "1")
    Long userId,

    @Schema(description = "닉네임", example = "여행러버")
    String nickname,

    @Schema(description = "JWT Access Token", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    String accessToken
) {}