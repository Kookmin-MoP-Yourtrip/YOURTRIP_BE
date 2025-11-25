package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceImageResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceUpdateResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.global.gemini.dto.GeminiCourseDto.PlaceDto;
import java.util.List;
import java.util.Objects;
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

    public static PlaceResponse toListResponse(Place place,
        List<PlaceImageResponse> placeImages) {
        List<PlaceImageResponse> filteredImages = placeImages.stream()
            .filter(img -> Objects.equals(img.placeId(), place.getId()))
            .toList();

        return PlaceResponse.builder()
            .placeId(place.getId())
            .placeName(place.getName())
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

    public static Place toCopyEntity(Place originalPlace, DaySchedule daySchedule) {
        return Place.builder()
            .daySchedule(daySchedule)
            .name(originalPlace.getName())
            .startTime(originalPlace.getStartTime())
            .memo(originalPlace.getMemo())
            .latitude(originalPlace.getLatitude())
            .longitude(originalPlace.getLongitude())
            .placeUrl(originalPlace.getPlaceUrl())
            .placeLocation(originalPlace.getPlaceLocation())
            .build();
    }

    public static Place toEntityFromGeminiDto(PlaceDto placeDto, DaySchedule daySchedule) {
        return Place.builder()
            .name(placeDto.placeName())
            .startTime(placeDto.startTime())
            .placeLocation(placeDto.placeLocation()) //TODO: 카카오가 준 placeLocation으로 변경 필요
            .daySchedule(daySchedule)
            .build();
    }
}
