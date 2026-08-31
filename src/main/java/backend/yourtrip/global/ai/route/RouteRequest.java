package backend.yourtrip.global.ai.route;

import java.time.LocalTime;
import java.util.List;

/**
 * {@code RouteOptimizer}에 넘기는 하루치 입력.
 *
 * <p><b>파라미터를 나열하지 않고 record 로 묶은 이유.</b> 이 다섯 값을 그대로 메서드 인자로 두면
 * {@code optimize(int, List, LocalTime, LocalTime, TravelMode)}가 되어 <b>같은 타입의
 * {@code LocalTime} 두 개가 인접한다.</b> 시작과 종료를 바꿔 넣어도 컴파일이 통과하고, 그 버그는
 * "왜 항상 하루 초과 처리가 발동하지"라는 형태로 한참 뒤에야 드러난다. record 는 호출부에서
 * 이름이 보이고, 기본값 흡수와 검증을 한 곳에 모아준다.
 *
 * <p>{@code null}로 들어온 시각과 이동수단은 여기서 기본값으로 바뀌므로, 최적화기 본체에는
 * null 분기가 없다.
 *
 * @param day           며칠째인지(1-based). 출력에 그대로 실려 나간다
 * @param places        방문할 장소. 순서는 의미가 없다 — 그 순서를 정하는 것이 최적화기의 일이다
 * @param dayStartTime  하루 시작 시각. {@code null}이면 {@link #DEFAULT_DAY_START}
 * @param dayEndTime    하루 종료 시각. {@code null}이면 {@link #DEFAULT_DAY_END}
 * @param travelMode    이동수단. {@code null}이면 {@link TravelMode#UNSPECIFIED}
 */
public record RouteRequest(
    int day,
    List<RoutePlace> places,
    LocalTime dayStartTime,
    LocalTime dayEndTime,
    TravelMode travelMode
) {

    /** RouteOptimizer 설계의 기본 시작 시각. 6단계에서 Planner 가 day 별로 덮어쓴다. */
    public static final LocalTime DEFAULT_DAY_START = LocalTime.of(9, 30);

    /**
     * 하루 종료 시각의 기본값.
     *
     * <p>설계 초안의 21:00 대신 <b>자정 직전으로 넓혔다</b>(RouteOptimizer 설계에 반영됨). 그 결과 기본값 상태에서는
     * 하루 예산이 869분이 되어, 장소 7개(체류 약 525분 + 이동 약 120분)를 넣어도 200분 넘게 남는다
     * — 즉 <b>축소·드롭 절차는 기본값에서 사실상 발동하지 않는다.</b>
     *
     * <p>그럼에도 절차를 구현해 두는 것은 이 값이 요청 필드이기 때문이다. 6단계에서 Planner 가
     * 더 이른 종료 시각을 넘기면 즉시 살아나므로 죽은 코드가 아니라 <b>호출자가 켜는 안전장치</b>다.
     */
    public static final LocalTime DEFAULT_DAY_END = LocalTime.of(23, 59);

    public RouteRequest {
        if (places == null) {
            throw new IllegalArgumentException("places 는 필수다 (빈 리스트는 허용된다)");
        }
        places = List.copyOf(places);

        dayStartTime = dayStartTime == null ? DEFAULT_DAY_START : dayStartTime;
        dayEndTime = dayEndTime == null ? DEFAULT_DAY_END : dayEndTime;
        travelMode = travelMode == null ? TravelMode.UNSPECIFIED : travelMode;

        if (!dayEndTime.isAfter(dayStartTime)) {
            throw new IllegalArgumentException(
                "하루 종료(%s)가 시작(%s)보다 늦어야 한다".formatted(dayEndTime, dayStartTime));
        }
    }

    /** 기본 시각·이동수단으로 하루치 요청을 만든다. 테스트와 단순 호출부용. */
    public static RouteRequest of(int day, List<RoutePlace> places) {
        return new RouteRequest(day, places, null, null, null);
    }
}
