package backend.yourtrip.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "이메일 인증번호 검증 요청 DTO")
public record EmailVerifyRequest(
    @Schema(description = "회원가입용 이메일", example = "user@example.com")
    String email,

    @Schema(description = "이메일로 수신한 인증번호 (6자리)", example = "123456")
    String code
) {}