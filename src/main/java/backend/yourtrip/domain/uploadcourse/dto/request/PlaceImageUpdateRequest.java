package backend.yourtrip.domain.uploadcourse.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record PlaceImageUpdateRequest(
    @Schema(description = "기존 장소 사진 ID (기존 사진 유지 시 전달)", example = "1")
    Long placeImageId,

    @Schema(description = "신규 장소 사진 파일 인덱스 (placeImages 멀티파트 파일 배열 내 0부터 시작하는 인덱스)", example = "0")
    Integer newImageIndex
) {

}
