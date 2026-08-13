package backend.yourtrip.global.ai.route;

/**
 * 두 좌표 사이의 대권거리(great-circle distance)를 구하는 순수 수학.
 *
 * <p><b>유클리드 근사 대신 haversine을 쓰는 이유</b>(설계 문서 §5-2). 한국 도시 규모(50km 미만)에서
 * 둘의 차이는 0.1% 미만이라 실용적으로는 근사로도 충분하다. 그럼에도 haversine을 쓰는 것은
 * 20줄이고 CPU 비용이 무의미하기 때문이다 — <b>최적화가 필요 없는 곳을 최적화하지 않고, 대신
 * "근사 오차"라는 변수를 아예 없앤다.</b> 동선 비교가 어긋났을 때 의심할 후보가 하나 줄어든다.
 *
 * <p>이 클래스는 {@code RoutePlace} 같은 route 타입을 알지 못한다. 좌표 네 개만 받는 원시 함수로
 * 두어야 4·5단계(근접 dedupe 등)에서 재사용할 때 route 패키지를 끌고 들어오지 않는다.
 *
 * <p><b>위경도 범위는 여기서 검증하지 않는다.</b> 검증은 {@code RoutePlace}의 생성자 한 곳에만
 * 둔다 — 같은 규칙이 두 곳에 있으면 언젠가 서로 어긋난다.
 */
public final class GeoUtils {

    /**
     * 지구 평균 반지름(km). IUGG가 정의한 평균 반지름 R1 값이다.
     *
     * <p>6371 대신 소수점을 유지하는 이유는 정밀도가 필요해서가 아니라, 이 값이 어디서 온
     * 숫자인지 되짚을 수 있게 하기 위해서다.
     */
    public static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoUtils() {
    }

    /**
     * 두 지점 사이의 대권거리(km). 인자 순서를 바꿔도 <b>비트 단위로 같은 값</b>이 나온다.
     *
     * <p>대칭성이 중요한 이유는 {@code RouteOptimizer}의 동점 처리 때문이다. 순열 하나를 뒤집은
     * 배열은 논리적으로 같은 비용이어야 하는데, {@code d(a,b) != d(b,a)}이면 마지막 비트가 달라져
     * 승자가 부동소수점 잡음으로 정해진다.
     *
     * @return 항상 0 이상의 유한한 값. {@code NaN}은 반환하지 않는다
     */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);

        double sinHalfDLat = Math.sin(dLat / 2);
        double sinHalfDLon = Math.sin(dLon / 2);

        double a = sinHalfDLat * sinHalfDLat
            + Math.cos(radLat1) * Math.cos(radLat2) * sinHalfDLon * sinHalfDLon;

        // 대척점에서 a 는 수학적으로 정확히 1이지만, 부동소수점 합산이 1 + 1e-16 을 낼 수 있다.
        // 그러면 아래 sqrt(1 - a) 가 sqrt(음수) = NaN 이 되고, NaN 거리는 비교 연산을 전부
        // false 로 만들어 예외 하나 없이 최적 순열 선택을 망가뜨린다. 한 줄로 막을 수 있는
        // 무성(silent) 실패라 국내 좌표만 들어오더라도 방어한다.
        a = Math.min(1.0, Math.max(0.0, a));

        // asin(sqrt(a)) 대신 atan2 를 쓴다 — a 가 1에 가까울 때 asin 이 정밀도를 잃는데,
        // 두 방식의 계산 비용은 같다.
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }
}
