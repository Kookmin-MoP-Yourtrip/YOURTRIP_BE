package backend.yourtrip.global.ai.candidate;

/**
 * 후보 소스 호출 하나의 결말 — 메트릭 {@code ai.candidate.retrieval{result=...}}의 태그다 (ROADMAP 5-8).
 *
 * <p><b>{@link #SKIPPED}는 로드맵에 없던 것을 더했다.</b> 로드맵은 {@code hit|empty|failed} 셋만
 * 적었지만, 그러면 <b>"물어봤는데 없더라"와 "물어보지 못했다"가 한 칸에 뭉친다.</b> 지오코딩이
 * 실패해 TourAPI를 아예 부르지 못한 day가 {@code empty}로 기록되면, 이 지표가 답해야 할 질문
 * ("{@code empty} 비율이 높은 지역이 곧 외부 데이터도 얇은 지역인가")이 오염된다. 4-8이 지오코딩
 * 결과에 {@code NO_RESULT}를 더한 것과 같은 판단이다.
 */
public enum CandidateOutcome {

    /** 후보를 하나 이상 확보했다. */
    HIT,

    /** 호출은 성공했는데 결과가 0건이다. 그 지역·슬롯에 외부 데이터가 얇다는 신호. */
    EMPTY,

    /** 호출 자체가 실패했다(HTTP·타임아웃·쿼터). 인프라 문제이지 데이터 문제가 아니다. */
    FAILED,

    /** 부를 조건이 아니어서 호출하지 않았다 — 관광 슬롯이 아니거나 anchor 좌표를 못 얻었다. */
    SKIPPED
}
