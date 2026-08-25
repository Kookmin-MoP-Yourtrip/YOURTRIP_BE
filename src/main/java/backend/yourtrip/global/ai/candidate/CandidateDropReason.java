package backend.yourtrip.global.ai.candidate;

/**
 * 후보 소스가 응답을 후보로 바꾸지 못하고 <b>버린</b> 사유 (이슈 #134). 메트릭
 * {@code ai.candidate.dropped}의 {@code reason} 태그가 된다.
 *
 * <h2>왜 로그로는 부족한가</h2>
 * 이 사유들은 원래 {@code log.debug} 한 줄로만 남았는데, {@code application-prod.yml}이
 * {@code backend.yourtrip: INFO}라 <b>운영에서는 아예 출력되지 않는다.</b> 즉 "네이버가 우리에게
 * 쓸 수 없는 후보를 얼마나 건네는가"를 운영에서 확인할 방법이 없었다.
 *
 * <p>같은 형태의 실수가 이미 있었다 — {@code RoutedDay.droppedPlaces}는 값이 있는데 아무도 읽지
 * 않아, 권역 밖 식당이 하루를 무너뜨린 사고를 <b>사람이 덤프를 읽고서야</b> 알았다. 그래서 여기서는
 * 로그를 남기되 메트릭을 함께 올린다.
 *
 * <h2>{@code CandidateOutcome}과 다른 질문에 답한다</h2>
 * {@code CandidateOutcome}은 <b>호출 하나의 결말</b>(물어봤더니 있더라/없더라/못 물었다)이고,
 * 이쪽은 <b>받은 응답 안에서 몇 건이 왜 탈락했는가</b>다. 후보가 5건 왔는데 전부 탈락하면
 * {@code outcome}은 {@code EMPTY}가 되는데, 그것만 보면 "네이버에 그 지역 데이터가 얇다"로
 * 읽힌다. 두 지표를 함께 봐야 "데이터가 없는 것"과 "우리가 버린 것"이 갈린다.
 */
public enum CandidateDropReason {

    /**
     * 좌표가 없다. 풀에 넣어도 {@code RouteOptimizer}에 들어갈 수 없어 나중에 탈락할 뿐이므로
     * 거르는 책임이 소스에 있다({@code PlaceCandidate}의 계약).
     */
    NO_COORDINATES,

    /**
     * 슬롯 힌트로 물었는데 다른 업종이 왔다 (ROADMAP 5-3). 풀에 넣으면 Curator 입력 토큰만 먹고,
     * 골라지면 "카페 자리에 주유소"가 된다. <b>매핑에 없는 분류는 통과시키므로</b> 이 값은
     * "매핑이 아는데 어긋난" 건수만 센다.
     */
    CATEGORY_MISMATCH,

    /**
     * 권역 앵커에서 {@link SeedDistanceLimit#MAX_ANCHOR_DISTANCE_KM}km 넘게 떨어졌다 (이슈 #134).
     *
     * <p><b>이 값이 오르면 {@code ai.candidate.retrieval}의 {@code empty}도 함께 오른다</b> — 먼
     * 후보 5건으로 채워지던 슬롯이 빈 슬롯이 되기 때문이다. 그 상승은 <b>악화가 아니라 개선</b>이고,
     * 두 지표의 대응 관계를 모르면 정반대로 읽힌다.
     *
     * <p>앵커 좌표를 못 얻어 거리를 잴 수 없었던 후보는 <b>여기 오지 않는다</b> — 모르는 것을
     * 이탈로 판정하지 않는 것이 {@code SeedDistanceLimit}의 계약이다.
     */
    OUT_OF_REGION
}
