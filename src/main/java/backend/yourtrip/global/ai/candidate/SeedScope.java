package backend.yourtrip.global.ai.candidate;

/**
 * 시더 후보를 <b>어느 지명 단계에서 얻었는가</b> (이슈 #110의 캐스케이드, 이슈 #113).
 *
 * <p>세 단계는 전부 같은 네이버 {@code sort=comment} 지역검색이라 <b>단계마다 자기 응답 안에서
 * 1~5의 {@code seedRank}를 새로 낳는다.</b> 그런데 {@code NaverPlaceMapper}는 응답 순서대로 순위를
 * 매길 뿐 질의 문자열을 남기지 않아, 그대로 두면 <b>도시 전역 1위와 권역 안 1위가 구별되지 않는다.</b>
 * 이 값이 그 구별을 후보 레코드에 실어 나른다 — {@code GeocodeOutcome}이 "어느 단계에서 좌표를
 * 얻었는지"를 값으로 나르는 것과 같은 역할이다.
 *
 * <p><b>단계마다 데려오는 후보의 거리가 다르고, 그것이 이 값을 쓰는 근거다</b>(96칸 실측).
 *
 * <table>
 *   <tr><th>단계</th><th>하한선</th><th>추가분 거리 중앙값</th></tr>
 *   <tr><td>{@link #AREA}</td><td>–</td><td>1.20km</td></tr>
 *   <tr><td>{@link #ANCHOR}</td><td>3건 미만</td><td>1.07km</td></tr>
 *   <tr><td>{@link #LOCATION}</td><td>0건</td><td><b>6.34km</b></td></tr>
 * </table>
 *
 * <p><b>{@code seedRank}가 null인 후보에는 이 값도 없다</b> — TourAPI 단독 후보처럼 시드에 들지
 * 못했으면 "어느 질의의 순위인가"라는 질문 자체가 성립하지 않는다({@code PlaceCandidate}의 불변식).
 */
public enum SeedScope {

    /**
     * 권역명을 검색 가능한 지명으로 줄여 물은 1차 질의({@code AreaQueryNormalizer}).
     *
     * <p>캐스케이드가 생기기 전에 존재하던 유일한 단계라, <b>단계를 명시하지 않는 호출부의 기본값</b>
     * 이기도 하다({@code PlaceCandidate}의 위임 생성자).
     */
    AREA,

    /**
     * day의 랜드마크({@code PlannerDayPlan.anchor})로 넓혀 물은 2차 질의. 3건에 못 미칠 때 탄다.
     *
     * <p><b>표기를 낮추지 않는다.</b> 여기서 채운 후보는 거리 중앙값 1.07km로 {@link #AREA}(1.20km)와
     * 다르지 않아, 권역 안 후보와 등급을 나눌 근거가 없다.
     */
    ANCHOR,

    /**
     * 사용자가 입력한 여행지(도시명)로 넓혀 물은 3차 질의. <b>0건일 때만</b> 탄다.
     *
     * <p><b>이 단계의 순위는 권역 순위와 같은 등급이 아니다</b> — 거리 중앙값이 6.34km로 다섯 배라
     * day를 권역으로 나눈 의미가 사라진 상태다. 그래서 {@code CandidateListRenderer}는 이 후보의
     * 순위 숫자를 목록에 싣지 않는다(이슈 #113) — 모델은 질의가 무엇이었는지 모르므로 숫자를 보면
     * "이 권역 인기 1위"로 읽는다.
     */
    LOCATION
}
