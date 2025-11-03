package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.dayschedule.Place;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PlaceMapper {

    public static Place toEntity(PlaceCreateRequest request, DaySchedule daySchedule) {
        return Place.builder()
            .name(request.placeName())
            .startTime(request.startTime())
            .memo(request.memo())
            .budget(request.budget())
            .latitude(request.latitude())
            .longitude(request.longitude())
            .placeUrl(request.placeUrl())
            .daySchedule(daySchedule)
            .build();
    }

}
