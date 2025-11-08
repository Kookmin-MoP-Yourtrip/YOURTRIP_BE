package backend.yourtrip.domain.uploadcourse.dto.request;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Builder;

@Builder
public record UploadCourseCreateRequest(
    @Schema(example = "1")
    @NotNull(message = "나의 코스 id 연동은 필수 입니다.")
    Long myCourseId,

    @Schema(example = "개쩌는 경주 여행기")
    @NotNull(message = "코스 업로드 시 제목은 필수 입력입니다.")
    String title,

    @Schema(example = "술과 음식을 좋아하는 분들 안성맞춤 코스")
    String introduction,

    @Schema(example = "multipart data")
    String thumbnailImage, //TODO: 멀티파트 데이터 입력으로 변경

    @ArraySchema(
        schema = @Schema(
            implementation = KeywordType.class,
            description = "코스의 키워드 코드 목록"
        ),
        arraySchema = @Schema(example = "[\"WALK\", \"FOOD\", \"HEALING\"]")
    )
    List<KeywordType> keywords
) {

}
