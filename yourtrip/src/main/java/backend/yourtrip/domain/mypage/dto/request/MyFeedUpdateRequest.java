package backend.yourtrip.domain.mypage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record MyFeedUpdateRequest(

    @Schema(example = "제주도 우도 여행기")
    @NotBlank(message = "제목은 필수입니다.")
    String title,

    @Schema(example = "제주특별자치도 제주시")
    @NotBlank(message = "위치는 필수입니다.")
    String location,

    @Schema(example = "https://yourtrip.s3.../feed_20.jpg")
    @NotBlank(message = "대표 이미지 URL은 필수입니다.")
    String contentUrl
) {}