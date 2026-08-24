package backend.yourtrip.global.ai.pipeline;

/**
 * {@code ai.course.pipeline.duration}의 {@code stage} 태그 (ROADMAP 7-5).
 *
 * <p><b>이 지표의 용도는 성능 튜닝이 아니라 하나의 결정이다</b> — 동기 API 계약을 유지할지
 * 202 Accepted + 폴링으로 전환할지. 설계는 그 판단을 "먼저 완성해 실측하고, p95가 목표를 넘는
 * 것을 데이터로 확인한 뒤"로 미뤄뒀고, 그 데이터가 이 시계열이다.
 *
 * <p><b>단계를 나눠 재는 이유.</b> 합계만 보면 느려졌다는 것은 알아도 어디를 고쳐야 할지 모른다.
 * 설계의 단계별 예산(Planner 2.5~4.0s / 후보 공급 0.5~0.9s / Curator 3.0~6.0s / 그라운딩
 * 0.2~0.4s / 최적화 &lt;10ms / URL 보강 0.2~0.4s)과 같은 축으로 잘라야 추정치와 실측을 대조할 수 있다.
 *
 * <p>상수 집합이 컴파일 타임에 고정이라 <b>기동 시점 0 등록의 대상</b>이다. {@code ai.llm.call}이
 * 유일하게 0 등록에서 빠지는 것은 {@code agent} 태그가 설정에서 오기 때문이고, 여기는 해당이 없다.
 */
public enum PipelineStage {

    /** 컨셉·제목·day별 권역 설계 (LLM 1회). */
    PLANNER,

    /** 네이버 시더 + TourAPI 후보 공급. */
    CANDIDATE_RETRIEVAL,

    /** day별 후보 선별 (LLM {@code days}회, 병렬). */
    CURATOR,

    /** {@code SUGGESTED} 실존 확인 + 전 day 중복 제거. */
    GROUNDING,

    /** 완전탐색 동선·시간 배치. 외부 호출이 없어 유일하게 밀리초 미만이 정상인 단계다. */
    ROUTE,

    /** URL이 빈 장소에만 카카오 1회. */
    URL_ENRICH
}
