package backend.yourtrip.global.ai.grounding;

/**
 * 슬롯이 장소를 <b>하나도</b> 채우지 못한 사유 — 메트릭 {@code ai.slot.vacant{reason=...}}의
 * 태그다 (이슈 #149).
 *
 * <h2>사유를 갈라야 하는 이유는 처방이 다르기 때문이다</h2>
 * 뭉치면 "빈 슬롯이 5개다"까지만 알고 <b>무엇을 고쳐야 그 슬롯이 채워지는지</b>는 모른다. 8단계
 * 병합 검증의 5건은 실제로 중복 4건 · 그라운딩 1건으로 갈렸고, 둘은 손대야 할 곳이 아예 다르다 —
 * 한쪽은 후보 공급이고 다른 쪽은 검증 로직이다.
 *
 * <h2>네 값이 슬롯을 완전히 나눈다</h2>
 * 빈 슬롯 시점에 각 후보는 반드시 아래 중 하나로 끝난다.
 *
 * <table>
 *   <tr><th>후보의 최후</th><th>어디로 세나</th></tr>
 *   <tr><td>{@code place}가 없다({@code NAME_MISMATCH}·{@code NO_RESULT}·{@code NO_COORDINATE}·{@code FAILED})</td>
 *       <td>{@code groundingFailures}</td></tr>
 *   <tr><td>{@code HIT}인데 전 day 중복이라 버려졌다</td><td>{@code duplicates}</td></tr>
 *   <tr><td>{@code CATEGORY_MISMATCH}로 보류됐다가 구제도 중복으로 실패했다</td><td>{@code duplicates}</td></tr>
 * </table>
 *
 * 살아남은 후보는 슬롯을 채우므로 이 판정에 오지 않는다. 그래서 <b>두 값이 모두 0이면 곧 후보가
 * 하나도 없었다는 뜻</b>이고, {@link #NO_CANDIDATE}가 else 가지로 정확히 정의된다.
 *
 * <h2>{@code ai.curation.slot}과 교차 검증된다</h2>
 * 빈 {@code choices}가 만들어지는 경로는 {@code DeterministicCuration}의 {@code UNFILLED} 하나뿐이라
 * <b>{@code ai.slot.vacant{reason=no_candidate}} == {@code ai.curation.slot{result=unfilled}}}가 항상
 * 성립한다.</b> 어긋나면 둘 중 하나가 버그다.
 *
 * <p>두 지표가 겹치는데도 이 값을 두는 이유는 <b>축이 다르기 때문</b>이다. 저쪽에는 슬롯 타입이
 * 없어 "저녁이 얼마나 자주 빠지는가"에 답하지 못하고, 무엇보다 이 값을 빼면 빈 슬롯 합계가 실제
 * 증발 수와 맞지 않는다.
 */
public enum SlotVacancyReason {

    /**
     * 살아남을 수 있었던 후보가 <b>전부 전 day 중복</b>이었다.
     *
     * <p>고칠 곳은 <b>후보 공급</b>이다 — 후보 풀을 넓히거나 day 별 질의를 갈라야 한다. 후보 풀이
     * 좁은 소도시에서 day 마다 같은 곳이 상위에 뜨면 앞 day 가 선점하고 뒤 day 가 굶는다(영주
     * day1 만 저녁이 남고 day2·3 이 잃었다).
     */
    DUPLICATE,

    /**
     * 후보가 <b>전부 카카오 검증에서 죽었다</b>(이름 불일치·무결과·좌표 없음·호출 실패).
     *
     * <p>고칠 곳은 <b>검증 로직</b>이다. 후보를 늘려도 안 고쳐진다 — 실제로 제주 day3 의 MEAL
     * 슬롯이 후보 3개를 각각 다른 사유로 잃었다.
     */
    GROUNDING,

    /**
     * 중복과 검증 실패가 <b>섞여</b> 죽었다.
     *
     * <p><b>어느 쪽도 단독 원인이 아니다.</b> 한쪽에 귀속시키면 "그 처방을 넣었는데 왜 안
     * 줄어드는가"라는 오답이 나오므로 따로 센다.
     */
    MIXED,

    /**
     * Curator 도 폴백도 <b>후보를 하나도 주지 못했다</b>.
     *
     * <p>고칠 곳은 <b>후보 공급 소스</b>(네이버·TourAPI)다. 위 셋과 달리 그라운딩은 아무 일도
     * 하지 않았다 — 검증할 것 자체가 없었다.
     */
    NO_CANDIDATE;

    /**
     * 슬롯 하나의 사유를 정한다.
     *
     * <p><b>구제가 중복으로 실패한 경우는 {@link #DUPLICATE}다.</b> {@code rescue}는 첫 성공에서
     * 멈추므로 빈손으로 돌아왔다는 것은 보류 후보 <b>전원이 앞 day 와 겹쳤다</b>는 뜻이고, 업종
     * 불일치는 이슈 #147 의 구제로 이미 무력화된 사유다 — 그 슬롯을 채우려면 후보 풀을 넓혀야지
     * 업종 제약을 건드릴 일이 아니다. 그래서 호출자가 그 건수를 {@code duplicates}에 더해 넘긴다.
     *
     * <p>판정을 스테이지가 아니라 여기에 두는 것은 {@code assemble}이 이미 길고, 진리표만 따로
     * 단언할 수 있어야 하기 때문이다.
     */
    static SlotVacancyReason of(int duplicates, int groundingFailures) {
        if (duplicates > 0 && groundingFailures > 0) {
            return MIXED;
        }
        if (duplicates > 0) {
            return DUPLICATE;
        }
        if (groundingFailures > 0) {
            return GROUNDING;
        }
        return NO_CANDIDATE;
    }
}
