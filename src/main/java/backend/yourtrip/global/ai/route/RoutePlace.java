package backend.yourtrip.global.ai.route;

/**
 * {@code RouteOptimizer}에 들어가는 장소 1건 — 좌표가 <b>반드시 있는</b> 장소다.
 *
 * <p>최적화기가 알아야 할 최소한만 담는다. 주소·전화번호·블로그 인기도 같은 것은 순서와 시각을
 * 정하는 데 쓰이지 않으므로 여기 없다. 대신 {@link #id}를 그대로 되돌려주므로 호출자가 자기
 * 타입을 다시 찾아갈 수 있다.
 *
 * <p><b>왜 5단계의 {@code GroundedPlace}를 직접 받지 않는가.</b> 그 타입은 아직 존재하지 않고,
 * 3단계가 뒷단계 산출물에 묶이면 순수 함수라는 성질이 유지되지 않는다. 대신 좌표와 슬롯 종류만
 * 요구하는 얇은 타입을 두고, 변환은 7단계가 한다({@code Map<String, GroundedPlace>} 한 줄).
 *
 * <p><b>좌표가 {@code Double}이 아니라 {@code double}인 이유.</b> 로드맵 5-2가 "Grounding을
 * 통과하지 못한 장소는 파이프라인에 존재하지 않는다"고 못박았다. 좌표 없는 장소가 최적화기까지
 * 왔다면 그건 앞 단계가 깨진 것이고, 이 시점에 할 수 있는 옳은 처리가 없다 —
 * <ul>
 *   <li>건너뛰면 "동선이 계산되지 않은 장소"가 코스에 섞여 나간다</li>
 *   <li>0.0/0.0으로 채우면 로드맵 1-1에서 지운 결함(적도 앞바다 좌표)이 그대로 돌아온다</li>
 * </ul>
 * 그래서 <b>거르는 책임은 7단계에 두고</b>, 여기서는 생성 자체를 막아 문제를 조기에 드러낸다.
 * 3단계가 조용히 통과시키면 그 판단이 어디서 났는지 나중에 추적할 수 없다.
 *
 * @param id        호출자가 붙이는 불투명 키(5단계에서는 카카오 {@code Document.id}). 최적화기는
 *                  내용을 해석하지 않고 그대로 돌려준다
 * @param name      로그와 테스트 가독성을 위한 이름. 알고리즘은 쓰지 않는다
 * @param slotType  체류시간을 결정한다
 * @param latitude  위도. {@code -90 ~ 90}
 * @param longitude 경도. {@code -180 ~ 180}
 */
public record RoutePlace(
    String id,
    String name,
    SlotType slotType,
    double latitude,
    double longitude
) {

    public RoutePlace {
        if (slotType == null) {
            throw new IllegalArgumentException("slotType 은 필수다 — 체류시간을 결정할 수 없다");
        }
        requireValidCoordinate(latitude, -90.0, 90.0, "위도");
        requireValidCoordinate(longitude, -180.0, 180.0, "경도");
    }

    /**
     * 좌표 검증은 이 한 곳에서만 한다. {@code GeoUtils}에도 같은 검사를 넣으면 두 규칙이 언젠가
     * 어긋나고, 어느 쪽이 진짜인지 알 수 없게 된다.
     */
    private static void requireValidCoordinate(double value, double min, double max, String label) {
        if (Double.isNaN(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                "%s 가 유효하지 않다: %s (허용 범위 %s ~ %s). 좌표 없는 장소를 거르는 책임은 호출자에 있다"
                    .formatted(label, value, min, max));
        }
    }
}
