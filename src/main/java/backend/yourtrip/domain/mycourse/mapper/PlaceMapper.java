package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.ai.ResolvedPlace;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceImageResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceUpdateResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PlaceMapper {

    public static Place toEntity(PlaceCreateRequest request, DaySchedule daySchedule) {
        return Place.builder()
            .placeName(request.placeName())
            .latitude(request.latitude())
            .longitude(request.longitude())
            .placeUrl(request.placeUrl())
            .placeLocation(request.placeLocation())
            .daySchedule(daySchedule)
            .build();
    }

    public static PlaceResponse toListResponse(Place place,
        List<PlaceImageResponse> placeImages) {
        List<PlaceImageResponse> filteredImages = placeImages.stream()
            .filter(img -> Objects.equals(img.placeId(), place.getId()))
            .toList();

        return PlaceResponse.builder()
            .placeId(place.getId())
            .placeName(place.getPlaceName())
            .startTime(place.getStartTime())
            .memo(place.getMemo())
            .latitude(place.getLatitude())
            .longitude(place.getLongitude())
            .placeUrl(place.getPlaceUrl())
            .placeLocation(place.getPlaceLocation())
            .placeImages(filteredImages)
            .build();
    }

    public static PlaceCreateResponse toCreateResponse(Place place) {
        return PlaceCreateResponse.builder()
            .placeId(place.getId())
            .placeName(place.getPlaceName())
            .latitude(place.getLatitude())
            .longitude(place.getLongitude())
            .placeUrl(place.getPlaceUrl())
            .placeLocation(place.getPlaceLocation())
            .memo(place.getMemo())
            .startTime(place.getStartTime())
            .build();
    }

    public static PlaceUpdateResponse toUpdateResponse(Place place) {
        return PlaceUpdateResponse.builder()
            .placeId(place.getId())
            .placeName(place.getPlaceName())
            .latitude(place.getLatitude())
            .longitude(place.getLongitude())
            .placeUrl(place.getPlaceUrl())
            .placeLocation(place.getPlaceLocation())
            .build();
    }

    public static Place toCopyEntity(Place originalPlace, DaySchedule daySchedule) {
        return Place.builder()
            .daySchedule(daySchedule)
            .placeName(originalPlace.getPlaceName())
            .startTime(originalPlace.getStartTime())
            .memo(originalPlace.getMemo())
            .latitude(originalPlace.getLatitude())
            .longitude(originalPlace.getLongitude())
            .placeUrl(originalPlace.getPlaceUrl())
            .placeLocation(originalPlace.getPlaceLocation())
            .build();
    }

    /**
     * 카카오 검증이 끝난 중간 표현으로 Place를 만든다.
     *
     * <p>기존 {@code toEntityFromGeminiDto}를 대체한다. 그쪽은 좌표를 세팅하지 않아
     * 빌더 기본값 0.0이 저장됐고(적도 앞바다), 좌표는 저장 후 더티체킹으로 채워졌다.
     * 그 구조 때문에 카카오 호출이 트랜잭션 안에 있어야 했다.
     */
    public static Place toEntityFromResolved(ResolvedPlace resolvedPlace,
        DaySchedule daySchedule) {
        return Place.builder()
            .daySchedule(daySchedule)
            .placeName(resolvedPlace.placeName())
            .startTime(resolvedPlace.startTime())
            .latitude(resolvedPlace.latitude())
            .longitude(resolvedPlace.longitude())
            .placeUrl(resolvedPlace.placeUrl())
            .placeLocation(resolvedPlace.placeLocation())
            .build();
    }
}
