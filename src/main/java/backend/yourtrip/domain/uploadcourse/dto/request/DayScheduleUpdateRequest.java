package backend.yourtrip.domain.uploadcourse.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Builder;

@Builder
public record DayScheduleUpdateRequest(
    @Schema(description = "일차 ID (기존 일차 수정 시 전달, 신규 일차 추가 시 null)", example = "1")
    Long dayScheduleId,

    @Schema(description = "일차 (n일차)", example = "1")
    int day,

    @Schema(description = "해당 일차의 장소 목록")
    @NotNull(message = "장소 목록은 필수입니다.")
    @Valid
    List<PlaceUpdateRequest> places
) {

}
