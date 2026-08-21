package backend.yourtrip.global.ai.agent;

/**
 * Curator 의 선택이 {@code SUGGESTED}로 강등된 사유 (ROADMAP 6-7). 메트릭
 * {@code ai.candidate.demoted}의 {@code reason} 태그가 된다.
 *
 * <h2>왜 버리지 않고 강등하는가</h2>
 * 목록 참조가 어긋났다는 것은 <b>"목록에서 골랐다는 주장이 틀렸다"</b>는 뜻이지 "그런 장소가
 * 없다"는 뜻이 아니다. 이름은 실존할 수 있으므로 카카오 검증 경로로 보내고, 거기서 이름 게이트를
 * 통과하지 못하면 그때 탈락한다. 버리면 실존하는 장소를 이유 없이 잃는다.
 *
 * <h2>여기 없는 것들</h2>
 * {@code slotIndex}가 범위 밖이거나 중복이거나 상호명이 비어 있는 경우는 <b>강등이 아니라
 * 폐기</b>다 — 어느 자리의 선택인지 모르거나 검색어조차 없어서 카카오로 보낼 수도 없다. 그것까지
 * 이 메트릭에 섞으면 "얼마나 자주 위조가 일어나는가"라는 질문에 다른 사건이 섞여 답이 흐려진다.
 * 그 셋은 로그로 남긴다.
 */
public enum DemotionReason {

    /**
     * 응답의 {@code slotType}이 Planner 가 정한 그 자리의 종류와 다르다.
     *
     * <p>자리 종류가 다르면 <b>{@code listIndex}가 가리키는 목록도 다르다</b> — 후보 풀은
     * {@code (day, slotType)}으로 조회되기 때문이다. 그래서 인덱스를 신뢰할 수 없고, 그 자리의
     * 선택을 전부 강등한다. 자리 자체는 Planner 의 종류로 유지한다.
     */
    SLOT_MISMATCH,

    /** {@code source}가 {@code SEEDED}·{@code LISTED}·{@code SUGGESTED} 중 어느 것도 아니다. */
    UNKNOWN_SOURCE,

    /** 목록에서 골랐다는데 {@code listIndex}가 비었거나 목록 범위를 벗어났다. */
    INDEX_OUT_OF_RANGE,

    /**
     * {@code listIndex}는 유효한데 그 자리의 상호명과 {@code placeName}이 다르다.
     *
     * <p><b>이것이 6-7 이 막으려는 위조의 전형이다</b> — 목록에 있는 번호를 적어 "골랐다"고
     * 주장하면서 실제로는 목록에 없는 이름을 내놓는 경우다. 인덱스만 검사하면 통과한다.
     */
    NAME_MISMATCH
}
