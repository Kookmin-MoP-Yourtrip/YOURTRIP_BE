package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceUpdateResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PlaceMapper {

    public static Place toEntity(PlaceCreateRequest request, DaySchedule daySchedule) {
        return Place.builder()
            .name(request.placeName())
            .latitude(request.latitude())
            .longitude(request.longitude())
            .placeUrl(request.placeUrl())
            .placeLocation(request.placeLocation())
            .daySchedule(daySchedule)
            .build();
    }

    public static PlaceListResponse toListResponse(Place place, List<String> presignedUrls) {
        return PlaceListResponse.builder()
            .placeId(place.getId())
            .placeName(place.getName())
            .startTime(place.getStartTime())
            .memo(place.getMemo())
            .latitude(place.getLatitude())
            .longitude(place.getLongitude())
            .placeUrl(place.getPlaceUrl())
            .placeLocation(place.getPlaceLocation())
            .placeImagesUrls(presignedUrls)
            .build();
    }

    public static PlaceCreateResponse toCreateResponse(Place place) {
        return PlaceCreateResponse.builder()
            .placeId(place.getId())
            .placeName(place.getName())
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
            .placeName(place.getName())
            .latitude(place.getLatitude())
            .longitude(place.getLongitude())
            .placeUrl(place.getPlaceUrl())
            .placeLocation(place.getPlaceLocation())
            .build();
    }
}
