package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.PlaceListResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PlaceMapper {

    public static Place toEntity(PlaceCreateRequest request, DaySchedule daySchedule) {
        return Place.builder()
            .name(request.placeName())
            .startTime(request.startTime())
            .memo(request.memo())
//            .budget(request.budget())
            .latitude(request.latitude())
            .longitude(request.longitude())
            .placeUrl(request.placeUrl())
            .placeLocation(request.placeLocation())
            .daySchedule(daySchedule)
            .build();
    }

    public static PlaceListResponse toListResponse(Place place) {
        return PlaceListResponse.builder()
            .placeId(place.getId())
            .placeName(place.getName())
            .startTime(place.getStartTime())
            .memo(place.getMemo())
//            .budget(place.getBudget())
            .latitude(place.getLatitude())
            .longitude(place.getLongitude())
            .placeUrl(place.getPlaceUrl())
            .placeLocation(place.getPlaceLocation())
            .build();
    }

}
