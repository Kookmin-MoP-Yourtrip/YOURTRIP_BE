package backend.yourtrip.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로필 등록(최종 회원가입) 요청 DTO")
public record ProfileCreateRequest(
    @Schema(description = "회원가입용 이메일", example = "user@example.com")
    String email,

    @Schema(description = "닉네임 (1~20자)", example = "여행러버")
    String nickname
) {

}