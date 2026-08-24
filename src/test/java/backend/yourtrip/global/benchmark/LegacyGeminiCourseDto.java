package backend.yourtrip.global.benchmark;

import java.time.LocalTime;
import java.util.List;

/**
 * 구 Gemini 단일 호출 경로의 응답 계약 — {@link LegacyGeminiPrompt}와 짝이다.
 *
 * <p>원본은 {@code GeminiCourseDto}였으나 8-4에서 {@code global/gemini}와 함께 삭제됐고,
 * baseline 측정의 응답 바인딩이 그대로 재현되도록 필드 구조를 옮겨 왔다. 프롬프트와 같은 이유로
 * <b>수정 금지</b>다.
 */
record LegacyGeminiCourseDto(String title, List<DayScheduleDto> daySchedules) {

    record DayScheduleDto(int day, List<PlaceDto> places) {

    }

    record PlaceDto(String placeName, LocalTime startTime) {

    }

}
