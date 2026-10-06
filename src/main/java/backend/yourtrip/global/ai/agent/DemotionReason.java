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
 * {@code slotIndex}가 범위 밖이거나 중복이거나, 번호가 범위 밖인데 상호명이 없는 경우는 <b>강등이
 * 아니라 폐기</b>다 — 어느 자리의 선택인지 모르거나 검색어조차 없어서 카카오로 보낼 수도 없다. 그것까지
 * 이 메트릭에 섞으면 "얼마나 자주 위조가 일어나는가"라는 질문에 다른 사건이 섞여 답이 흐려진다.
 * 그 셋은 로그로 남긴다.
 */
public enum DemotionReason {

    /**
     * 목록 번호가 범위를 벗어났는데 상호명이 함께 와서 {@code SUGGESTED}로 살렸다.
     *
     * <p>#194 전에는 셋이 더 있었다 — {@code SLOT_MISMATCH}(응답의 자리 종류가 Planner 와 다름),
     * {@code UNKNOWN_SOURCE}(출처 값이 목록 밖), {@code NAME_MISMATCH}(번호와 상호명이 다른 항목).
     * 셋 다 응답의 {@code slotType}·{@code source}·목록 선택의 {@code placeName}이 있어야 생기는
     * 사유라, 그 필드를 응답에서 빼면서 <b>원리적으로 발생할 수 없게 돼</b> 지웠다. 실측 빈도는
     * 고정 입력 재생 486선택 중 {@code NAME_MISMATCH} 1건, 나머지 0건이었다(STEP-curator-output).
     */
    INDEX_OUT_OF_RANGE
}
