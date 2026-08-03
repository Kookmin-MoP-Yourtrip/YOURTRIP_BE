package backend.yourtrip.domain.uploadcourse.dto.cache;

/**
 * 장소 사진 1건을 캐싱하기 위한 DTO. URL 자체를 캐싱하지 않고 S3 key만 담아,
 * 응답 조립 시점에 CloudFront 공개 URL(서명 없음)을 매번 새로 조합한다.
 */
public record PlaceImageCacheItem(
    Long placeId,
    Long placeImageId,
    String placeImageS3Key
) {

}
