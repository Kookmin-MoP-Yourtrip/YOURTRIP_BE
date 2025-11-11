package backend.yourtrip.domain.mycourse.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

public record PlaceCreateRequest(
    @Schema(example = "황리단길")
    @NotBlank(message = "장소 이름은 필수 입력 항목입니다.")
    String placeName,

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    @Schema(type = "string", example = "10:30", description = "HH:mm 형식 (시와 분은 반드시 2자리로)")
    @NotNull(message = "시작 시간은 필수 입력 항목입니다.")
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
