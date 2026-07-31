package backend.yourtrip.domain.mypage.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProfileImageResponse(

    @Schema(example = "https://s3-yourtrip/profile/image_123.png",
        description = "업로드된 프로필 이미지 URL")
    String profileImageUrl

) {}