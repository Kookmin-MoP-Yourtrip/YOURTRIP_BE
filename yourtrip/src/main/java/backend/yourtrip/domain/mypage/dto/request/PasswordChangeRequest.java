package backend.yourtrip.domain.mypage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "비밀번호 변경 요청 DTO")
public record PasswordChangeRequest(

    @Schema(example = "OldPw1234!", description = "현재 비밀번호")
    @NotBlank(message = "현재 비밀번호는 필수 입력입니다.")
    String currentPassword,

    @Schema(example = "NewPw9999!", description = "새 비밀번호(8자 이상)")
    @NotBlank(message = "새 비밀번호는 필수 입력입니다.")
    @Size(min = 8, message = "새 비밀번호는 최소 8자 이상이어야 합니다.")
    String newPassword
) {}