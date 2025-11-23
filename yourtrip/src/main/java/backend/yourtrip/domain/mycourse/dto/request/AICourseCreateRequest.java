package backend.yourtrip.domain.mycourse.dto.request;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record AICourseCreateRequest(
    @NotBlank(message = "코스 제목은 필수 입력 항목입니다.")
    @Schema(example = "경주")
    String location,

    @NotNull(message = "여행 기간은 필수 입력 항목입니다.")
    @Schema(example = "2025-10-31", description = "여행 시작 날짜")
    LocalDate startDate,

    @NotNull(message = "여행 기간은 필수 입력 항목입니다.")
    @Schema(example = "2025-11-02", description = "여행 종료 날짜")
    LocalDate endDate,

    @ArraySchema(
        schema = @Schema(
            implementation = KeywordType.class
        ),
        arraySchema = @Schema(example = "[\"WALK\", \"FOOD\", \"HEALING\"]")
    )
    List<KeywordType> keywords
) {

}
