package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DayScheduleMapper {

//    public static List<DayScheduleResponse> toListResponse(List<DaySchedule> daySchedules) {
//        return daySchedules.stream()
//            .map(day -> new DayScheduleResponse(
//                day.getId(),
//                day.getDay(),
//                day.getPlaces().stream()
//                    .map(PlaceMapper::toListResponse)
//                    .toList()
//            ))
//            .toList();
//    }

    public static DayScheduleResponse toDayScheduleResponse(DaySchedule daySchedule,
        List<String> presignedUrls) {
        return new DayScheduleResponse(
            daySchedule.getId(),
            daySchedule.getDay(),
            daySchedule.getPlaces().stream()
                .map(place -> PlaceMapper.toListResponse(place, presignedUrls))
                .toList()
        );
    }
}
