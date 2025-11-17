package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse.DayScheduleSummary;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DayScheduleMapper {

    public static List<DayScheduleSummary> toSummaryResponse(List<DaySchedule> daySchedules) {
        return daySchedules.stream()
            .map(day -> new DayScheduleSummary(
                day.getId(),
                day.getDay()
            ))
            .toList();
    }

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
