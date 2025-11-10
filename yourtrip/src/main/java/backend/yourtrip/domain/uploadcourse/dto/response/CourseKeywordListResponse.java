package backend.yourtrip.domain.uploadcourse.dto.response;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(
    description = "여행 키워드 목록 응답",
    example = """
        {
          "travelMode": [
            { "label": "뚜벅이", "code": "WALK" }
          ],
          "companionType": [
            { "label": "혼자", "code": "SOLO" }
          ],
          "mood": [
            { "label": "힐링", "code": "HEALING" }
          ],
          "duration": [
            { "label": "하루", "code": "ONE_DAY" }
          ],
          "budget": [
            { "label": "가성비", "code": "COST_EFFECTIVE" }
          ]
        }
        """
)
public record CourseKeywordListResponse(
    @Schema(description = "이동수단")
    List<KeywordType> travelMode,

    @Schema(description = "동행유형")
    List<KeywordType> companionType,

    @Schema(description = "여행분위기")
    List<KeywordType> mood,

    @Schema(description = "여행기간")
    List<KeywordType> duration,

    @Schema(description = "예산")
    List<KeywordType> budget
) {

}
