package backend.yourtrip.domain.mycourse.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record MyCourseCreateRequest(
    @Schema(example = "개쩌는 경주 여행기")
    @NotBlank(message = "코스 제목은 필수 입력 항목입니다.")
    String title,

    @Schema(example = "경주", description = "여행지")
    @NotBlank(message = "여행지는 필수 입력 항목입니다.")
    String location,

    @Schema(example = "2025-10-31", description = "여행 시작 날짜")
    @NotNull(message = "여행 기간은 필수 입력 항목입니다.")
    LocalDate startDate,

    @Schema(example = "2025-11-02", description = "여행 종료 날짜")
    @NotNull(message = "여행 기간은 필수 입력 항목입니다.")
    LocalDate endDate
) {

    // 날짜 유효성 검사 (startDate ≤ endDate)
    @AssertTrue(message = "startDate는 endDate보다 이후일 수 없습니다.")
    public boolean checkValidDateRange() {
        // null 체크 (다른 필드 유효성 검사보다 먼저 호출될 수 있으므로)
        if (startDate == null || endDate == null) {
            return true; // @NotNull 검증에 맡김
        }
        return !startDate.isAfter(endDate);
    }

}
