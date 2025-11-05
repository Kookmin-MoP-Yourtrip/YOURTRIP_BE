package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleListResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DayScheduleMapper {

    public static List<DayScheduleListResponse> toListResponse(List<DaySchedule> daySchedules) {
        return daySchedules.stream()
            .map(day -> new DayScheduleListResponse(
                day.getId(),
                day.getDay(),
                day.getPlaces().stream()
                    .map(PlaceMapper::toListResponse)
                    .toList()
            ))
            .toList();
    }
}
