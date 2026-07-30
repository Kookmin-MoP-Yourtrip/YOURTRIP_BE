package backend.yourtrip.domain.user.service.dto.response;

import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카카오 로그인 초기 응답 DTO")
public record KakaoLoginInitResponse(
    @Schema(description = "상태(EXISTING/NEED_PROFILE)", example = "NEED_PROFILE")
    String status,

    @Schema(description = "카카오 사용자 ID", example = "123456789")
    String kakaoId,

    @Schema(description = "이메일", example = "user@kakao.com")
    String email,

    @Schema(description = "기존 유저일 경우 로그인 정보")
    UserLoginResponse login
) {

}