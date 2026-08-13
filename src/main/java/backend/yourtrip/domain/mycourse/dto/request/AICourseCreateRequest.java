package backend.yourtrip.domain.mycourse.dto.request;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record AICourseCreateRequest(
    @NotBlank(message = "여행지는 필수 입력 항목입니다.")
    @Schema(example = "경주")
    String location,

    @NotNull(message = "여행 기간은 필수 입력 항목입니다.")
    @Schema(example = "2025-10-31", description = "여행 시작 날짜")
    LocalDate startDate,

    @NotNull(message = "여행 기간은 필수 입력 항목입니다.")
    @Schema(example = "2025-11-02", description = "여행 종료 날짜")
    LocalDate endDate,

    // 빈 리스트도 막는다. buildKeywordsJson에 빈 리스트를 넘기면 "{}"가 나와
    // 취향이 하나도 반영되지 않은 코스가 생성되는데, 그건 이 API의 목적에 맞지 않는다.
    @NotEmpty(message = "여행 스타일 키워드는 최소 1개 이상 선택해야 합니다.")
    @ArraySchema(
        schema = @Schema(
            implementation = KeywordType.class
        ),
        arraySchema = @Schema(example = "[\"WALK\", \"FOOD\", \"HEALING\"]")
    )
    List<KeywordType> keywords
) {

    // 날짜 유효성 검사 (startDate ≤ endDate)
    @Schema(hidden = true)
    @AssertTrue(message = "startDate는 endDate보다 이후일 수 없습니다.")
    public boolean getValidDateRange() {
        // null 체크 (다른 필드 유효성 검사보다 먼저 호출될 수 있으므로)
        if (startDate == null || endDate == null) {
            return true; // @NotNull 검증에 맡김
        }
        return !startDate.isAfter(endDate);
    }

}
