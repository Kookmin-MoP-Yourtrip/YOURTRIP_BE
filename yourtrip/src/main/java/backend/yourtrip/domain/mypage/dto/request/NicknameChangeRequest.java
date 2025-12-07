package backend.yourtrip.domain.mypage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record NicknameChangeRequest(

    @Schema(example = "혼여행러", description = "새로운 닉네임")
    @NotBlank(message = "닉네임은 비어 있을 수 없습니다.")
    String nickname

) {}