package backend.yourtrip.global.ai.pipeline;

import java.time.LocalTime;
import java.util.List;

/**
 * 순서와 시각이 정해진 하루.
 *
 * <p><b>{@link #places}의 순서가 곧 방문 순서이자 저장 순서다.</b> {@code DaySchedule.places}에
 * {@code @OrderBy("id ASC")}가 걸려 있고 별도 sequence 컬럼이 없어, 8-3이 이 순서 그대로
 * {@code save()}해야 동선이 재현된다.
 *
 * <p>장소가 하나도 없는 day가 나올 수 있다 — 그 day의 슬롯이 전멸한 경우다. <b>그것만으로는
 * 실패가 아니다</b>(hard fail은 전 day가 0개일 때 하나뿐).
 */
public record AiCourseDay(
    int day,
    LocalTime startTime,
    LocalTime endTime,
    List<AiCoursePlace> places
) {

    public AiCourseDay {
        places = places == null ? List.of() : List.copyOf(places);
    }
}
