package backend.yourtrip.domain.mycourse.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record PlaceCreateRequest(
    @Schema(example = "황리단길")
    @NotBlank(message = "장소 이름은 필수 입력 항목입니다.")
    String placeName,

    @Schema(example = "35.884")
    double latitude,

    @Schema(example = "129.8341")
    double longitude,

    @Schema(example = "http://place.map.kakao.com/26338954")
    String placeUrl,

    String placeLocation
) {

}
