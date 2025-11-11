package backend.yourtrip.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "비밀번호 설정 요청 DTO")
public record PasswordSetRequest(
    @Schema(description = "회원가입용 이메일", example = "user@example.com")
    String email,

    @Schema(description = "설정할 비밀번호 (최소 8자 이상, 공백 불가)", example = "Abcd1234!")
    String password
) {}