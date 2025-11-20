package backend.yourtrip.domain.mypage.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProfileResponse(

    @Schema(example = "user@example.com", description = "회원 이메일")
    String email,

    @Schema(example = "혼여행러", description = "현재 닉네임")
    String nickname,

    @Schema(example = "https://s3-yourtrip/profile/myprofile.png",
        description = "프로필 이미지 URL")
    String profileImageUrl

) {}