package backend.yourtrip.global.ai.grounding;

/**
 * 후보 하나의 그라운딩 결말 — 메트릭 {@code ai.grounding.match{result=...}}의 태그다 (ROADMAP 5-6).
 *
 * <p><b>이 enum이 환각률의 운영 프록시다.</b> 결과가 환각의 <b>종류</b>를 가른다는 것이 요지이고,
 * 특히 {@link #NO_RESULT}와 {@link #FAILED}를 뭉치면 카카오 장애가 나는 날 환각률이 부풀어
 * 3점 비교가 오염된다 — 하나는 모델의 문제이고 하나는 인프라의 문제다.
 */
public enum GroundingOutcome {

    /** 실존이 확인됐고 좌표를 확보했다. */
    HIT,

    /**
     * 비슷한 게 있으나 이름이 맞지 않는다 — <b>세탁 위험 구간</b>. 하한선 없는 점수 매칭이 환각을
     * 실존 장소로 바꿔 놓던 바로 그 자리이고, 1-2의 이름 게이트가 여기서 발동한다.
     */
    NAME_MISMATCH,

    /** 카카오에 아무것도 없다 — <b>순수 환각</b>(지어낸 이름). */
    NO_RESULT,

    /**
     * 검색은 맞췄는데 응답에 쓸 만한 좌표가 없다.
     *
     * <p><b>로드맵에 없던 값을 더했다.</b> 넷 중 어디에도 정직하게 들어가지 않아서다 —
     * 모델이 지어낸 것도 아니고(HIT에 가깝다) 카카오가 죽은 것도 아니다(FAILED가 아니다).
     * {@code NO_RESULT}에 뭉치면 환각률 프록시가 그만큼 부풀고, {@code HIT}에 넣으면 좌표 없이
     * 탈락한 장소가 성공으로 집계된다. 4-8의 {@code NO_RESULT}, 5-8의 {@code SKIPPED}와 같은 판단이다.
     */
    NO_COORDINATE,

    /** 호출 자체가 실패했다(HTTP·타임아웃·쿼터). <b>인프라 문제이지 환각이 아니다.</b> */
    FAILED
}
