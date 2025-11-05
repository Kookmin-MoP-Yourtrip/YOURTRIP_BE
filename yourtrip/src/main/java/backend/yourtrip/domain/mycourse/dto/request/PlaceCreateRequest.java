package backend.yourtrip.domain.mycourse.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;

public record PlaceCreateRequest(
    @Schema(example = "황리단길")
    String placeName,

    @Schema(example = "10:30", description = "HH:mm 형식 (시와 분은 반드시 2자리로)")
    LocalTime startTime,

    @Schema(example = "황남시장에 짐보관")
    String memo,

//    int budget,

    @Schema(example = "35.884")
    double latitude,

    @Schema(example = "129.8341")
    double longitude,

    @Schema(example = "http://place.map.kakao.com/26338954")
    String placeUrl
) {

}
