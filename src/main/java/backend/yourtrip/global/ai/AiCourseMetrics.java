package backend.yourtrip.global.ai;

import backend.yourtrip.global.ai.candidate.CandidateOutcome;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.GeocodeOutcome;
import backend.yourtrip.global.ai.grounding.GroundingOutcome;
import backend.yourtrip.global.ai.grounding.PlaceUrlOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * AI 코스 생성 파이프라인의 커스텀 메트릭 (ROADMAP 5-6·5-11).
 *
 * <p><b>이 저장소 최초의 커스텀 Micrometer 메트릭이라 주입 패턴을 여기서 세운다.</b> 스테이지가
 * {@link MeterRegistry}를 직접 만지지 않게 하는 이유는 두 가지다 — 태그 문자열이 여러 클래스로
 * 흩어지면 오타 하나가 조용히 새 시계열을 만들고, 스테이지 단위 테스트가 레지스트리 조립까지
 * 떠안게 된다.
 *
 * <h2>미리 등록한다</h2>
 * 발생 가능한 태그 조합을 기동 시점에 <b>0으로 등록</b>한다. Prometheus에서 <b>"시계열이 없다"와
 * "값이 0이다"는 구분되지 않기 때문</b>이다 — {@code no_result} 시계열이 사라진 것을 "환각이
 * 0이 됐다"로 읽으면 이 지표의 목적(3점 비교)이 통째로 무너진다. 조합이 40개 남짓이라 비용도 없다.
 *
 * <p>{@code ai.llm.call}만 예외로 지연 등록한다 — {@code agent} 태그가 설정
 * ({@code llm.agents})에서 오므로 조합을 미리 알 수 없다.
 */
@Component
public class AiCourseMetrics {

    /** 후보 공급이 실제로 목록을 채우는지. {@code empty}가 잦은 지역이 곧 외부 데이터도 얇은 지역이다. */
    public static final String CANDIDATE_RETRIEVAL = "ai.candidate.retrieval";

    /** {@code anchor} 지오코딩이 어느 단계에서 맞았는지. {@code fallback_*}이 잦으면 Planner의 anchor 문제다. */
    public static final String GEOCODE = "ai.geocode";

    /**
     * <b>환각률의 운영 프록시이자 이 작업의 핵심 지표.</b> {@code no_result}(순수 환각)와
     * {@code failed}(인프라)를 반드시 갈라야 한다 — 뭉치면 카카오 장애 때 환각률이 부풀어
     * 3점 비교가 오염된다.
     */
    public static final String GROUNDING_MATCH = "ai.grounding.match";

    /** URL 을 채운 비율. {@code too_far}가 많으면 동명 업소, {@code name_mismatch}면 표기 차이 문제다. */
    public static final String PLACE_URL = "ai.place.url";

    /**
     * 에이전트별 지연·실패율 (ROADMAP 5-11). 2단계에서 만든 어댑터에 붙이는 것이라 새 코드가 아니다.
     *
     * <p>{@code agent} 태그가 설정({@code llm.agents})에서 오므로 조합을 미리 알 수 없다 —
     * 유일하게 0 등록에서 빠지는 계열이다.
     */
    public static final String LLM_CALL = "ai.llm.call";

    /** 호출이 성공했다. */
    public static final String LLM_OUTCOME_SUCCESS = "success";

    /** 응답을 받지 못했다(HTTP·타임아웃·429). 재시도를 다 쓰고도 실패한 경우다. */
    public static final String LLM_OUTCOME_TRANSPORT_ERROR = "transport_error";

    /** 200 인데 응답이 잘렸다. 2-6 이 JSON 실패의 진짜 원인으로 지목한 사건이라 따로 센다. */
    public static final String LLM_OUTCOME_TRUNCATED = "truncated";

    /** 200 인데 JSON 이 깨졌거나 스키마를 벗어났다(의미 재시도까지 실패). */
    public static final String LLM_OUTCOME_RESPONSE_ERROR = "response_error";

    /** 그 밖의 예상 밖 실패. */
    public static final String LLM_OUTCOME_ERROR = "error";

    /** 네이버 지역검색 시더 (전 슬롯의 인기 축). */
    public static final String SOURCE_NAVER_LOCAL = "naver_local";

    /** 한국관광공사 TourAPI (관광 슬롯의 커버리지·분류 축). */
    public static final String SOURCE_TOUR_API = "tour_api";

    private final MeterRegistry registry;

    public AiCourseMetrics(MeterRegistry registry) {
        this.registry = registry;
        registerZeroSeries();
    }

    /**
     * <b>{@code @PostConstruct}가 아니라 생성자에서 부른다.</b> 0 등록은 이 클래스가 스스로 지키는
     * 계약이지 컨테이너가 얹어 주는 부가 기능이 아니다 — 라이프사이클에 걸어 두면 스프링 없이
     * 조립하는 단위 테스트에서만 조용히 빠져, "테스트는 통과하는데 운영 대시보드에는 시계열이
     * 없는" 어긋남이 생긴다. 하는 일이 인메모리 등록뿐이라 생성자에서 해도 안전하다.
     */
    private void registerZeroSeries() {
        for (String source : new String[]{SOURCE_NAVER_LOCAL, SOURCE_TOUR_API}) {
            for (CandidateOutcome outcome : CandidateOutcome.values()) {
                retrievalCounter(source, outcome);
            }
        }
        for (GeocodeOutcome outcome : GeocodeOutcome.values()) {
            geocodeCounter(outcome);
        }
        for (GroundingOutcome outcome : GroundingOutcome.values()) {
            for (CandidateSourceType source : CandidateSourceType.values()) {
                groundingCounter(outcome, source);
            }
        }
        for (PlaceUrlOutcome outcome : PlaceUrlOutcome.values()) {
            placeUrlCounter(outcome);
        }
    }

    public void candidateRetrieval(String source, CandidateOutcome outcome) {
        retrievalCounter(source, outcome).increment();
    }

    public void geocode(GeocodeOutcome outcome) {
        geocodeCounter(outcome).increment();
    }

    public void groundingMatch(GroundingOutcome outcome, CandidateSourceType source, int count) {
        if (count > 0) {
            groundingCounter(outcome, source).increment(count);
        }
    }

    public void placeUrl(PlaceUrlOutcome outcome, int count) {
        if (count > 0) {
            placeUrlCounter(outcome).increment(count);
        }
    }

    /**
     * 에이전트 호출 하나의 지연과 결말 (ROADMAP 5-11).
     *
     * <p>지연을 <b>결말별로</b> 나눠 재는 것이 요지다 — 성공과 실패를 한 분포에 섞으면
     * "타임아웃이 늘어 p95가 나빠진 것"과 "모델이 느려진 것"을 구분할 수 없다.
     */
    public void llmCall(String agent, String provider, String outcome, long durationNanos) {
        Timer.builder(LLM_CALL)
            .tag("agent", agent)
            .tag("provider", provider)
            .tag("outcome", outcome)
            .register(registry)
            .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private Counter retrievalCounter(String source, CandidateOutcome outcome) {
        return Counter.builder(CANDIDATE_RETRIEVAL)
            .tag("source", source)
            .tag("result", tag(outcome.name()))
            .register(registry);
    }

    private Counter geocodeCounter(GeocodeOutcome outcome) {
        return Counter.builder(GEOCODE)
            .tag("result", tag(outcome.name()))
            .register(registry);
    }

    private Counter groundingCounter(GroundingOutcome outcome, CandidateSourceType source) {
        return Counter.builder(GROUNDING_MATCH)
            .tag("result", tag(outcome.name()))
            .tag("source", tag(source.name()))
            .register(registry);
    }

    private Counter placeUrlCounter(PlaceUrlOutcome outcome) {
        return Counter.builder(PLACE_URL)
            .tag("result", tag(outcome.name()))
            .register(registry);
    }

    /** enum 이름을 태그 표기로. {@code NAME_MISMATCH → name_mismatch} */
    private static String tag(String enumName) {
        return enumName.toLowerCase(Locale.ROOT);
    }
}
