package backend.yourtrip.domain.mypage.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로필 이미지 업로드 응답 DTO")
public record ProfileImageResponse(

    @Schema(example = "https://yourtrip.s3.ap-northeast-2.amazonaws.com/profile/abc123.png",
        description = "업로드된 프로필 이미지 URL")
    String profileImageUrl
) {}