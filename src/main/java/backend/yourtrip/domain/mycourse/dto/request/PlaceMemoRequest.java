package backend.yourtrip.domain.mycourse.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

public record PlaceMemoRequest(
    @Schema(example = "황남시장에 짐보관")
    String memo
) {

}
