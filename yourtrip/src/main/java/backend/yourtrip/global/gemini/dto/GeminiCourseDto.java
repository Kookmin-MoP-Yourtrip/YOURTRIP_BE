package backend.yourtrip.global.gemini.dto;

import java.time.LocalTime;
import java.util.List;

public record GeminiCourseDto(String title, List<DayScheduleDto> daySchedules) {

    public record DayScheduleDto(int day, List<PlaceDto> places) {

    }

    public record PlaceDto(String placeName, LocalTime startTime, String placeLocation,
                           String memo) {

    }

}