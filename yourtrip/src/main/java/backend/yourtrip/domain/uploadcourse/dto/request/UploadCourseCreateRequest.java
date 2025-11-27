package backend.yourtrip.domain.uploadcourse.dto.request;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Builder;

@Builder
public record UploadCourseCreateRequest(
    @Schema(example = "1")
    @NotNull(message = "나의 코스 id 연동은 필수 입니다.")
    Long myCourseId,

    @Schema(example = "개쩌는 경주 여행기")
    @NotBlank(message = "코스 업로드 시 제목은 필수 입력입니다.")
    String title,

    @Schema(example = "술과 음식을 좋아하는 분들 안성맞춤 코스")
    String introduction,

    @ArraySchema(
        schema = @Schema(
            implementation = KeywordType.class),
        arraySchema = @Schema(example = "[\"WALK\", \"FOOD\", \"HEALING\"]")
    )
    @NotNull(message = "키워드 목록은 필수 입력값입니다. 선택된 키워드가 없을 시 빈 배열을 반환해주세요")
    List<KeywordType> keywords
) {

}
