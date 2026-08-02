package backend.yourtrip.domain.uploadcourse.dto.cache;

/**
 * 장소 사진 1건을 캐싱하기 위한 DTO. presigned URL은 만료(15분)가 있어 캐싱하지 않고
 * S3 key만 담아, 응답 조립 시점에 매번 새로 발급한다.
 */
public record PlaceImageCacheItem(
    Long placeId,
    Long placeImageId,
    String placeImageS3Key
) {

}
