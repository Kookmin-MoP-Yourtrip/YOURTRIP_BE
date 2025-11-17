package backend.yourtrip.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "비밀번호 재설정 요청 DTO")
public record PasswordResetRequest(
    @Schema(description = "가입된 이메일")
    String email,

    @Schema(description = "새로운 비밀번호(8자 이상)", example = "abc12345!")
    String newPassword
) {}