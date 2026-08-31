package backend.yourtrip.global.ai.route;

import java.time.LocalTime;
import java.util.List;

/**
 * 순서와 시각이 정해진 하루치 결과.
 *
 * <p>{@link #places}의 순서가 곧 방문 순서다. 8단계에서 이 순서 그대로 저장해야 하는데,
 * {@code DaySchedule.places}에 {@code @OrderBy("id ASC")}가 걸려 있고 별도 sequence 컬럼이 없어
 * <b>삽입 순서가 곧 표시 순서</b>이기 때문이다(로드맵 8-3).
 *
 * <p><b>{@link #droppedPlaces}를 남기는 이유.</b> 하루가 넘쳐 장소를 뺐다는 사실이 아무 데도
 * 남지 않으면, 사용자 입장에서는 요청한 장소가 조용히 사라진 것으로 보인다. 이 목록은 7-5
 * 파이프라인 메트릭의 재료이고, 초과 처리 테스트가 단언할 수 있는 유일한 증거이기도 하다.
 * V1이 소비하지 않더라도 유지 비용이 사실상 없다.
 *
 * @param day            며칠째인지. 입력의 값이 그대로 온다
 * @param startTime      첫 장소 방문 시각
 * @param endTime        마지막 장소에서 나오는 시각. <b>{@code dayEndTime}을 넘을 수 있다</b> —
 *                       장소를 최소 3개까지만 빼기 때문에, 그 아래에서는 초과인 채로 반환한다
 * @param places         방문 순서대로 정렬된 장소
 * @param droppedPlaces  하루 안에 담기지 않아 제외된 장소. 보통 비어 있다
 */
public record RoutedDay(
    int day,
    LocalTime startTime,
    LocalTime endTime,
    List<RoutedPlace> places,
    List<RoutePlace> droppedPlaces
) {

    public RoutedDay {
        places = List.copyOf(places);
        droppedPlaces = List.copyOf(droppedPlaces);
    }

    /** 장소가 하나도 없는 day. 입력이 비었을 때 반환한다. */
    static RoutedDay empty(int day, LocalTime dayStartTime) {
        return new RoutedDay(day, dayStartTime, dayStartTime, List.of(), List.of());
    }
}
