package backend.yourtrip.global.gemini.dto;

import java.time.LocalTime;
import java.util.List;

public record GeminiCourseDto(String title, List<DayScheduleDto> daySchedules) {

    public static record DayScheduleDto(int day, List<PlaceDto> places) {

    }

    public static record PlaceDto(String placeName, LocalTime startTime, String placeLocation,
                                  String memo) {

    }

}