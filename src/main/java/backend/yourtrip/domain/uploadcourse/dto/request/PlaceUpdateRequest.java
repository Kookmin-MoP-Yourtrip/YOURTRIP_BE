package backend.yourtrip.domain.uploadcourse.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalTime;
import java.util.List;
import lombok.Builder;

@Builder
public record PlaceUpdateRequest(
    @Schema(description = "장소 ID (기존 장소 수정 시 전달, 신규 장소 추가 시 null)", example = "1")
    Long placeId,

    @Schema(description = "장소 이름", example = "황리단길 카페 거리")
    @NotBlank(message = "장소 이름은 필수입니다.")
    String placeName,

    @Schema(description = "방문 시작 시간", example = "10:30:00")
    LocalTime startTime,

    @Schema(description = "장소 메모", example = "감성 카페 골목 산책")
    String memo,

    @Schema(description = "위도", example = "35.8375")
    double latitude,

    @Schema(description = "경도", example = "129.2123")
    double longitude,

    @Schema(description = "카카오 지도 장소 URL", example = "http://place.map.kakao.com/26338954")
    String placeUrl,

    @Schema(description = "장소 주소", example = "경북 경주시 포석로 인근")
    String placeLocation,

    @Schema(description = "장소 사진 수정 목록 (기존 사진 유지 및 신규 사진 인덱스 매핑)")
    List<PlaceImageUpdateRequest> placeImages
) {

}
