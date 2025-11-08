package backend.yourtrip.domain.uploadcourse.dto.response;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import java.util.List;
import lombok.Builder;

@Builder
public record CourseKeywordListResponse(
    List<KeywordType> travelMode,
    List<KeywordType> companionType,
    List<KeywordType> mood,
    List<KeywordType> duration,
    List<KeywordType> budget
) {

}
