package backend.yourtrip.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "이메일 인증번호 발송 요청 DTO")
public record EmailSendRequest(
    @Schema(description = "회원가입용 이메일", example = "user@example.com")
    String email
) {}