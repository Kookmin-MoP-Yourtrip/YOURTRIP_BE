package backend.yourtrip.domain.user.service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "카카오 닉네임 완료 요청 DTO")
public record KakaoCompleteRequest(
    @Schema(description = "카카오 사용자 ID (init 응답에서 받은 값)", example = "3456789012345", required = true)
    @NotBlank(message = "kakaoId는 필수 입력값입니다.")
    String kakaoId,

    @Schema(description = "닉네임 (1~20자)", example = "여행러버", required = true)
    @NotBlank(message = "닉네임은 필수 입력값입니다.")
    String nickname
) {}