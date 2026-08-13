package backend.yourtrip.global.ai.route;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 하루치 장소의 <b>방문 순서와 시각을 계산</b>한다. 외부 의존이 없는 결정론적 계산이다.
 *
 * <p>지금까지 이 일은 LLM 프롬프트가 맡고 있었다 — "시간이 겹치지 않게", "역주행하지 않게" 같은
 * 문장으로 부탁하는 방식이다. LLM은 좌표를 모른 채 텍스트로만 배치하므로 지그재그 동선이 나오고,
 * 부탁한 규칙이 지켜지는지 확인할 방법도 없다. 좌표가 확보된 뒤 여기서 계산하면
 * <b>"시간 겹침 없음"이 규칙이 아니라 알고리즘의 성질</b>이 된다(설계 문서 §2 원칙 1).
 *
 * <p>시간 모델은 다음 한 줄이다.
 * <pre>
 * t[0] = dayStartTime
 * t[i] = t[i-1] + 체류시간(i-1) + 이동시간(i-1 → i)
 * </pre>
 * 앞 장소에서 나온 뒤에야 다음 장소에 도착하므로 <b>겹침이 구조적으로 불가능하다.</b>
 *
 * <p><b>내부 시각을 {@code LocalTime}이 아니라 {@code int}(자정 기준 분)로 다룬다.</b>
 * {@code LocalTime.plusMinutes()}는 자정을 넘으면 조용히 랩어라운드해서, 23:50 + 30분이
 * 00:20이 된다. 그 순간 "하루를 넘겼는지" 판정이 <b>정반대로 뒤집힌다</b> — 축소·드롭이
 * 발동해야 할 상황에서 여유롭다고 판단하는 것이다. 하루 종료 기본값이 23:59라 이건 이론적
 * 위험이 아니다.
 */
@Component
public class RouteOptimizer {

    /** 하루를 분 단위로 표현할 때의 자정. */
    private static final int MINUTES_PER_DAY = 24 * 60;

    /** 사용자에게 보여줄 시각의 단위(분). {@code 09:37}은 코스 표에 어울리지 않는다. */
    private static final int DISPLAY_TIME_UNIT_MINUTES = 5;

    /**
     * 하루치 장소의 순서와 시각을 계산한다.
     *
     * <p>현재는 <b>입력 순서를 그대로 두고 시각만</b> 계산한다. 순서를 고르는 완전탐색은 다음
     * 커밋에서 이 위에 얹힌다.
     */
    public RoutedDay optimize(RouteRequest request) {
        List<RoutePlace> places = request.places();
        if (places.isEmpty()) {
            return RoutedDay.empty(request.day(), request.dayStartTime());
        }

        int[] stayMinutes = defaultStayMinutes(places);
        double[][] distances = distanceMatrix(places);
        int[] order = identityOrder(places.size());

        Schedule schedule = buildSchedule(order, stayMinutes, distances,
            toMinutes(request.dayStartTime()), request.travelMode());

        return toRoutedDay(request, places, schedule, stayMinutes, List.of());
    }

    /**
     * 한 순열을 시간축에 배치한 결과. 비용 계산과 출력 생성이 모두 이것을 읽는다.
     *
     * @param order          방문 순서. {@code places} 인덱스의 배열이다
     * @param arrivalMinutes {@code order} 순서대로의 도착 시각(자정 기준 분)
     * @param endMinutes     마지막 장소에서 나오는 시각
     */
    private record Schedule(int[] order, int[] arrivalMinutes, int endMinutes) {
    }

    /**
     * 주어진 순서대로 도착 시각을 누적한다.
     *
     * <p><b>5분 올림을 여기서 하지 않는 것이 중요하다.</b> 올림은 단조 증가라 장소마다 최대 4분씩
     * 밀리고, 7개면 하루 종료가 최대 28분 뒤로 간다. 그 28분이 하루 초과 판정에 그대로 들어가서
     * <b>실제로는 넘치지 않는 코스가 넘친 것으로 판정돼 장소가 삭제된다.</b> 표시 편의를 위한
     * 반올림이 알고리즘의 결정을 바꾸는 것은 명백히 틀렸다. 올림은 출력 직전 한 번만 한다.
     */
    private Schedule buildSchedule(int[] order, int[] stayMinutes, double[][] distances,
        int startMinutes, TravelMode travelMode) {

        int[] arrivals = new int[order.length];
        int t = startMinutes;

        for (int i = 0; i < order.length; i++) {
            arrivals[i] = t;
            t += stayMinutes[order[i]];
            if (i < order.length - 1) {
                t += travelMinutes(distances[order[i]][order[i + 1]], travelMode);
            }
        }

        return new Schedule(order, arrivals, t);
    }

    private RoutedDay toRoutedDay(RouteRequest request, List<RoutePlace> places,
        Schedule schedule, int[] stayMinutes, List<RoutePlace> dropped) {

        List<RoutedPlace> routed = new ArrayList<>(schedule.order().length);
        for (int i = 0; i < schedule.order().length; i++) {
            int placeIndex = schedule.order()[i];
            routed.add(new RoutedPlace(
                places.get(placeIndex),
                toDisplayTime(schedule.arrivalMinutes()[i]),
                stayMinutes[placeIndex]));
        }

        return new RoutedDay(
            request.day(),
            toDisplayTime(schedule.arrivalMinutes()[0]),
            toDisplayTime(schedule.endMinutes()),
            routed,
            dropped);
    }

    /**
     * 두 장소 사이 이동시간(분). 항상 올림하고 고정 오버헤드를 더한다.
     *
     * <p><b>{@code 1e-9}를 빼는 이유.</b> {@code 1.0 × 60 / 15.0}은 수학적으로 정확히 4지만
     * double 연산에서는 {@code 4.000000000000001}이 나올 수 있고, 그러면 {@code Math.ceil}이
     * 5를 준다. 하루 여섯 구간이면 근거 없는 6분이 붙어 하루 초과 판정을 바꾼다.
     *
     * <p><b>거리가 0이어도 오버헤드는 붙는다.</b> 같은 건물 안 두 장소를 0분에 이동하는 것으로
     * 계산하면 안 된다 — 그게 오버헤드의 정의다(환승 대기, 주차).
     */
    static int travelMinutes(double distanceKm, TravelMode travelMode) {
        // 나눗셈보다 곱셈을 먼저 해 오차를 줄인다.
        double raw = distanceKm * 60.0 / travelMode.getEffectiveSpeedKmh();
        return (int) Math.ceil(raw - 1e-9) + travelMode.getFixedOverheadMinutes();
    }

    /**
     * 내부 계산값(자정 기준 분)을 사용자에게 보여줄 시각으로 바꾼다. 5분 단위 올림.
     *
     * <p>표시 시각은 내부 시각보다 <b>최대 4분 늦다.</b> 항상 늦는 방향이므로 사용자가 이 시각에
     * 맞춰 움직이면 계획보다 여유가 생긴다. 연속한 두 장소의 실제 간격이 최소 41분(축소된 체류
     * 36분 + 자차 오버헤드 5분)이라 <b>표시 시각이 겹치거나 역전되지는 않는다.</b>
     *
     * <p>자정을 넘긴 값은 23:55로 자른다. {@code LocalTime}은 24:00 이상을 표현할 수 없어
     * 그대로 두면 예외가 난다. 하루 최소 3개 하한 때문에 병적인 입력(늦은 시작 + 긴 체류)에서
     * 실제로 도달할 수 있는 경로다.
     */
    static LocalTime toDisplayTime(int minutes) {
        int ceiled = Math.floorDiv(minutes + DISPLAY_TIME_UNIT_MINUTES - 1, DISPLAY_TIME_UNIT_MINUTES)
            * DISPLAY_TIME_UNIT_MINUTES;

        if (ceiled >= MINUTES_PER_DAY) {
            return LocalTime.of(23, 55);
        }
        if (ceiled < 0) {
            return LocalTime.MIDNIGHT;
        }
        return LocalTime.of(ceiled / 60, ceiled % 60);
    }

    private static int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static int[] defaultStayMinutes(List<RoutePlace> places) {
        int[] stayMinutes = new int[places.size()];
        for (int i = 0; i < places.size(); i++) {
            stayMinutes[i] = places.get(i).slotType().getDefaultStayMinutes();
        }
        return stayMinutes;
    }

    /**
     * 장소 간 거리를 미리 전부 계산해 둔다.
     *
     * <p>완전탐색이 얹히면 haversine 호출이 순열마다 반복되는데, {@code n=7}이면 30,240회다.
     * 미리 계산하면 49회로 줄어든다. 10줄이고, 벤치마크가 "완전탐색은 공짜"라는 결론을 낼 때
     * 그 숫자를 흐리지 않는다.
     */
    private static double[][] distanceMatrix(List<RoutePlace> places) {
        int n = places.size();
        double[][] distances = new double[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                RoutePlace a = places.get(i);
                RoutePlace b = places.get(j);
                double km = GeoUtils.haversineKm(
                    a.latitude(), a.longitude(), b.latitude(), b.longitude());
                distances[i][j] = km;
                distances[j][i] = km;
            }
        }
        return distances;
    }

    private static int[] identityOrder(int size) {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        return order;
    }
}
