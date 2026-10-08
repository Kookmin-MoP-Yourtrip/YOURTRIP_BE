package backend.yourtrip.domain.mycourse.dto.request;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

    /**
     * AI 코스로 만들 수 있는 최대 여행 일수 (#178).
     *
     * <p>Curator 는 day 마다 LLM 호출을 1개씩 병렬로 내므로 일수가 곧 요청 하나의 LLM 작업 수다.
     * 상한이 없으면 30일 요청 하나가 작업 31개를 만들고, 입장 제한(요청 수 기준)을 통과한 몇 건만으로
     * {@code aiAgentExecutor}가 넘쳐 요청 스레드가 LLM 을 직접 부르는 경로가 살아난다.
     * 값의 근거(단일 요청 지연 실측, 실행기 용량 불변식)는 {@code docs/tasks/ai-course-day-limit/README.md}.
     */
    public static final int MAX_TRIP_DAYS = 5;

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

    // 여행 일수 상한 (시작일·종료일 포함). 날짜 순서가 뒤집힌 경우는 위 검사에 맡긴다
    @Schema(hidden = true)
    @AssertTrue(message = "AI 코스는 최대 " + MAX_TRIP_DAYS + "일까지 생성할 수 있습니다.")
    public boolean getValidTripLength() {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            return true;
        }
        return ChronoUnit.DAYS.between(startDate, endDate) + 1 <= MAX_TRIP_DAYS;
    }

}
