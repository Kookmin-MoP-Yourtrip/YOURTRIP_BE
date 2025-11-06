package backend.yourtrip.domain.mycourse.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record MyCourseCreateRequest(
    @Schema(example = "개쩌는 경주 여행기")
    @NotBlank(message = "코스 제목은 필수 입력 항목입니다.")
    String title,

    @Schema(example = "경주")
    String location,

    @Schema(example = "2")
    int nights,

    @Schema(example = "3")
    int days,

    @Schema(example = "2025-10-31")
    LocalDate startDay,

    @Schema(example = "2025-11-02")
    LocalDate endDay
) {

}
