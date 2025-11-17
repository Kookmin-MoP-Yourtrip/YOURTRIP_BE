package backend.yourtrip.domain.mypage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "닉네임 변경 요청 DTO")
public record NicknameUpdateRequest(

    @Schema(example = "여행가는고양이", description = "1~20자 닉네임")
    @NotBlank(message = "닉네임은 필수 입력입니다.")
    @Size(max = 20, message = "닉네임은 최대 20자까지 가능합니다.")
    String nickname
) {}