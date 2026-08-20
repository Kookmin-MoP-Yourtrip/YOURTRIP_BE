package backend.yourtrip.global.ai.candidate;

/**
 * 장소 후보의 출처 — <b>"좌표·주소를 어디서 승계했는가"</b>를 뜻한다 (ROADMAP 5-8).
 *
 * <p>그라운딩의 역할이 "카카오 검색"이 아니라 "실존 확인 + 좌표 확보"로 재정의되면서, 이 값이
 * 곧 <b>그 후보를 검증하는 방법</b>이 된다 — 앞의 둘은 코드가 응답을 승계해 호출이 0회이고,
 * {@code SUGGESTED}만 카카오를 부른다(설계의 "SEEDED는 네이버 응답을 그대로 그라운딩 데이터로 쓴다").
 *
 * <p>메트릭 {@code ai.grounding.match{source=...}}의 태그이기도 하다.
 */
public enum CandidateSourceType {

    /** 네이버 지역검색 시더 유래. 좌표·주소·카테고리를 네이버 응답에서 승계한다. */
    SEEDED,

    /**
     * 한국관광공사 TourAPI 유래. 좌표·주소·{@code cat3}를 TourAPI 응답에서 승계한다.
     *
     * <p><b>시더와 병합된 후보도 여기 속한다</b> — 병합 시 좌표·명칭은 정비된 TourAPI 값을 쓰기
     * 때문이다. "시드에도 들었는가"는 이 값이 아니라 {@code seedRank}가 말한다.
     */
    LISTED,

    /**
     * Curator의 파라메트릭 제안 — <b>후보 목록에 없던 장소</b>. 로컬 검색 랭킹 밖의 전국구 유명
     * 장소를 회수하는 통로이고, 그만큼 환각 위험이 여기에 몰려 있어 카카오 검증이 필수다.
     */
    SUGGESTED
}
