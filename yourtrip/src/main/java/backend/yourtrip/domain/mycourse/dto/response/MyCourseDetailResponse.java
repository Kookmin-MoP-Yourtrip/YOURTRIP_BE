package backend.yourtrip.domain.mycourse.dto.response;

import backend.yourtrip.domain.mycourse.entity.enums.CourseRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record MyCourseDetailResponse(
    Long courseId,
    String title,
    @Schema(example = "경주", description = "여행지")
    String location,
//    int totalBudget,
    @Schema(example = "1", description = "코스 편집 인원 수")
    int memberCount,
    @Schema(description = "여행 시작 날짜")
    LocalDate startDate,
    @Schema(description = "여행 종료 날짜")
    LocalDate endDate,
    @Schema(description = "코스 최초 생성자는 OWNER, 초대받은 참여자는 PARTICIPANT, OWNER인 사람만 코스 초대 버튼과 업로드 버튼이 보여야함")
    CourseRole role,
    LocalDateTime updatedAt,
    List<DayScheduleResponse> daySchedules
) {

}
