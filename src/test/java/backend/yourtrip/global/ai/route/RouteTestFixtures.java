package backend.yourtrip.global.ai.route;

import java.util.List;

/**
 * route 테스트가 공유하는 픽스처 빌더.
 *
 * <p>{@code RoutePlace}는 인자가 다섯 개라, 헬퍼 없이 테스트를 쓰면 본문이 생성자 호출로 덮인다.
 * 여기서 노이즈를 걷어내야 각 테스트가 <b>무엇을 검증하는지</b>가 한눈에 보인다.
 *
 * <p>좌표는 실제 지명을 쓰지 않고 <b>계산하기 쉬운 격자</b>로 만든다. 기댓값을 손으로 검산할 수
 * 있어야 테스트가 "왜 이 순서가 정답인지"를 설명할 수 있기 때문이다. 기준점은 경주 대릉원
 * 근처이고, {@link #eastOf}가 만드는 1km 간격은 이 위도에서 경도 약 0.011도다.
 */
final class RouteTestFixtures {

    /** 기준 위도(경주 일대). 이 근처에서 경도 1도는 약 90.3km 다. */
    static final double BASE_LAT = 35.8347;

    /** 기준 경도. */
    static final double BASE_LON = 129.2094;

    /**
     * {@link #BASE_LAT} 위도에서 동쪽으로 1km 이동하는 데 필요한 경도 차이(근사).
     *
     * <p>흔히 쓰는 상수 111.32를 그대로 써서 실제 거리는 1km보다 약 0.1% <b>짧게</b> 나온다.
     * 이 오차를 굳이 없애지 않는다 — 정확히 1.000000km를 만들면 이동시간이
     * {@code 60/15 = 4.0}이라는 <b>정수 경계에 정확히 걸터앉게</b> 되고, 부동소수점 마지막
     * 비트에 따라 4분이 되기도 5분이 되기도 한다. 조금 모자라게 두는 편이 테스트를 안정시킨다.
     */
    static final double ONE_KM_IN_LON_DEGREES = 1.0 / (111.32 * Math.cos(Math.toRadians(BASE_LAT)));

    private RouteTestFixtures() {
    }

    /** 기준점에 놓인 장소. 좌표가 중요하지 않은 테스트용. */
    static RoutePlace place(String name, SlotType slotType) {
        return new RoutePlace(name, name, slotType, BASE_LAT, BASE_LON);
    }

    /** 좌표를 직접 지정하는 장소. */
    static RoutePlace place(String name, SlotType slotType, double latitude, double longitude) {
        return new RoutePlace(name, name, slotType, latitude, longitude);
    }

    /**
     * 기준점에서 동쪽으로 {@code km} 만큼 떨어진 장소.
     *
     * <p>일직선 위에 늘어놓으면 최적 순서를 손으로 계산할 수 있다 — 한쪽 끝에서 다른 쪽 끝으로
     * 훑는 것이 총 이동거리 최소이고, 그 역순도 같은 거리다(동점 처리 테스트의 재료).
     */
    static RoutePlace eastOf(String name, SlotType slotType, double km) {
        return new RoutePlace(name, name, slotType, BASE_LAT, BASE_LON + km * ONE_KM_IN_LON_DEGREES);
    }

    /** 방문 순서를 이름 목록으로 뽑는다. 단언을 읽기 쉽게 만든다. */
    static List<String> namesOf(RoutedDay day) {
        return day.places().stream().map(routed -> routed.place().name()).toList();
    }

    /** 드롭된 장소를 이름 목록으로 뽑는다. */
    static List<String> droppedNamesOf(RoutedDay day) {
        return day.droppedPlaces().stream().map(RoutePlace::name).toList();
    }
}
