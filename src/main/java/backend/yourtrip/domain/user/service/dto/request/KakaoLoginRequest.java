package backend.yourtrip.domain.user.service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record KakaoLoginRequest(
    @Schema(description = "카카오 로그인 인가 코드 (1회용)", example = "abc123xyz", required = true)
    @NotBlank(message = "인가 코드는 필수 입력값입니다.")
    String code,

    @Schema(description = "닉네임 (1~20자)", example = "여행러버", required = true)
    @NotBlank(message = "닉네임은 필수 입력값입니다.")
    String nickname
) {}
