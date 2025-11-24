package backend.yourtrip.domain.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 및 토큰 재발급 응답 DTO")
public record UserLoginResponse(

    @Schema(description = "회원 고유 ID", example = "1")
    Long userId,

    @Schema(description = "닉네임", example = "여행러버")
    String nickname,

    @Schema(description = "프로필 이미지 URL (15분 유효 Presigned URL)",
        example = "https://yourtrip-bucket.s3.ap-northeast-2.amazonaws.com/presigned-url")
    String profileImageUrl,

    @Schema(description = "JWT Access Token", example = "eyJhbGciOiJIUzI1NiIs...")
    String accessToken
) {}
