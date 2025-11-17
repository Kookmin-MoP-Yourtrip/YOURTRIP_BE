package backend.yourtrip.domain.mycourse.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;

public record PlaceStartTimeRequest(
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    @Schema(type = "string", example = "10:30", description = "HH:mm 형식 (시와 분은 반드시 2자리로)")
    LocalTime startTime
) {

}
