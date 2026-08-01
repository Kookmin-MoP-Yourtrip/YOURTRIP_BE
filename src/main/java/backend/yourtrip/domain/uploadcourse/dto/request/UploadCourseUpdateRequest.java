package backend.yourtrip.domain.uploadcourse.dto.request;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

@Builder
public record UploadCourseUpdateRequest(
    @Schema(example = "경주 인생샷 1일 코스 (수정본)")
    @NotBlank(message = "코스 제목은 필수 입력입니다.")
    String title,

    @Schema(example = "황리단길부터 첨성대까지, 인생샷 남기기 좋은 스팟만 모았어요.")
    String introduction,

    @Schema(example = "경주")
    String location,

    @Schema(example = "2025-03-01")
    @NotNull(message = "시작일은 필수 입력입니다.")
    LocalDate startDate,

    @Schema(example = "2025-03-01")
    @NotNull(message = "종료일은 필수 입력입니다.")
    LocalDate endDate,

    @ArraySchema(
        schema = @Schema(implementation = KeywordType.class),
        arraySchema = @Schema(example = "[\"WALK\", \"FOOD\", \"HEALING\"]")
    )
    @NotNull(message = "키워드 목록은 필수 입력값입니다. 선택된 키워드가 없을 시 빈 배열을 전송해주세요")
    List<KeywordType> keywords,

    @Schema(description = "일차별 장소 일정 목록")
    @NotNull(message = "일정 목록은 필수 입력입니다.")
    @Valid
    List<DayScheduleUpdateRequest> daySchedules
) {

}
