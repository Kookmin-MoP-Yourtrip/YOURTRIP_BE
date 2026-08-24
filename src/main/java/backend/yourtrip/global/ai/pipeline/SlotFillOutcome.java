package backend.yourtrip.global.ai.pipeline;

/**
 * 슬롯 하나의 선택이 <b>누구에게서 왔는가</b> — {@code ai.curation.slot}의 {@code result} 태그.
 *
 * <p><b>이 지표가 없으면 "LLM 큐레이션이 꺼진 상태"를 관측할 수 없다.</b> 7-3의 폴백은 Curator가
 * 실패해도 후보 목록으로 자리를 채우므로, 전 day의 Curator가 죽어도 응답은 200이고 코스는
 * 멀쩡해 보인다. 바뀌는 것은 내용뿐이다 — "컨셉에 맞게 고른 장소"가 "검색 결과 상위 3개"가 된다.
 *
 * <p>{@code ai.candidate.adopted}로는 이걸 가릴 수 없다. 그쪽의 {@code source}는 후보의 <b>출처</b>
 * (네이버냐 TourAPI냐)를 말할 뿐이라, LLM이 고른 시드 후보와 코드가 채운 시드 후보가 똑같이
 * {@code seeded}로 찍힌다.
 *
 * <h2>세 값이 전체를 나눈다</h2>
 * 슬롯 하나는 반드시 셋 중 하나다. 그래서 <b>분모를 따로 둘 필요가 없다</b> —
 * {@code fallback / (curator + fallback + unfilled)}가 곧 폴백 비율이다.
 *
 * <p>8-6 환각률 측정에서 특히 중요하다. <b>폴백으로 채운 장소는 환각률이 구조적으로 0에 가깝다</b> —
 * 네이버·TourAPI가 실제로 반환한 장소만 들어가기 때문이다. 측정 중 Curator가 조용히 죽어 있었다면
 * "파이프라인이 환각을 없앴다"가 아니라 "LLM을 안 썼다"인데, 이 값 없이는 둘을 구분할 수 없다.
 */
public enum SlotFillOutcome {

    /** Curator(LLM)가 골랐다. 정상 경로다. */
    CURATOR,

    /** Curator가 비운 자리를 후보 목록 상위 3개로 채웠다 (ROADMAP 7-3). */
    FALLBACK,

    /**
     * 비었는데 후보 목록도 없어 채우지 못했다.
     *
     * <p>이 값이 늘면 후보 공급이 죽고 있다는 뜻이고, 전 day가 이 상태가 되면 7-4의
     * {@code AI_GROUNDING_FAILED}로 이어진다 — <b>hard fail의 선행 지표</b>다.
     */
    UNFILLED
}
