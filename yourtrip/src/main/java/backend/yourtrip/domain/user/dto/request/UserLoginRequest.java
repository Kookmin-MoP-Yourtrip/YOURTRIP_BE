package backend.yourtrip.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "이메일 로그인 요청 DTO")
public record UserLoginRequest(
    @Schema(description = "가입된 이메일", example = "user@example.com")
    String email,

    @Schema(description = "비밀번호", example = "Abcd1234!")
    String password
) {}
