package backend.yourtrip.domain.mycourse.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record MyCourseListItemResponse(
    String title,
    @Schema(description = "여행지")
    String location,
    @Schema(description = "여행 시작 날짜")
    LocalDate startDate,
    @Schema(description = "여행 종료 날짜")
    LocalDate endDate,
    @Schema(example = "1", description = "코스 편집 인원 수")
    int memberCount
) {

}
