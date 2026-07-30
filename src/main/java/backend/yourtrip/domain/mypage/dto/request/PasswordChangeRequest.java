package backend.yourtrip.domain.mypage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(

    @Schema(example = "oldPassword123!", description = "현재 비밀번호")
    @NotBlank(message = "현재 비밀번호는 필수입니다.")
    String currentPassword,

    @Schema(example = "newPassword123!", description = "새 비밀번호")
    @NotBlank(message = "새 비밀번호는 필수입니다.")
    @Size(min = 8, message = "새 비밀번호는 최소 8자리 이상이어야 합니다.")
    String newPassword

) {}