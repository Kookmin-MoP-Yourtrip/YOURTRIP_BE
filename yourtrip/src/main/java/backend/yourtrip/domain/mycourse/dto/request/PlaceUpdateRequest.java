package backend.yourtrip.domain.mycourse.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record PlaceUpdateRequest(
    @Schema(example = "첨성대")
    @NotBlank(message = "장소 이름은 필수 입력 항목입니다.")
    String placeName,

    @Schema(example = "39.684")
    double latitude,

    @Schema(example = "125.4321")
    double longitude,

    @Schema(example = "http://place.map.kakao.com/12345678")
    String placeUrl,

    @Schema(example = "경북 경주시 인왕동 839-1")
    String placeLocation
) {

}
