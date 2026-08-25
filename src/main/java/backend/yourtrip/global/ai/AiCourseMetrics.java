package backend.yourtrip.global.ai;

import backend.yourtrip.global.ai.agent.DemotionReason;
import backend.yourtrip.global.ai.candidate.CandidateOutcome;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.GeocodeOutcome;
import backend.yourtrip.global.ai.grounding.GroundingOutcome;
import backend.yourtrip.global.ai.grounding.PlaceUrlOutcome;
import backend.yourtrip.global.ai.pipeline.PipelineStage;
import backend.yourtrip.global.ai.pipeline.SlotFillOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
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
 *
 * <h2>지연은 히스토그램으로 낸다</h2>
 * 세 Timer({@code ai.course.pipeline.duration}·{@code ai.course.request.duration}·{@code ai.llm.call})는
 * {@code publishPercentileHistogram()}으로 버킷을 내보낸다. 기본값(count·sum·max)으로는 평균만
 * 나오는데, <b>11-2가 요구하는 판단 기준은 p95</b>다 — 꼬리가 긴 분포에서 평균은 "20명 중 1명이
 * 25초를 기다린다"를 가린다.
 *
 * <p><b>클라이언트 계산 백분위({@code publishPercentiles})를 쓰지 않는 이유</b>는 그쪽이 롤링
 * 윈도우(기본 2분) 위에서 계산되기 때문이다. 8-6·11-2는 30요청을 몰아 돌리고 <b>끝난 뒤에</b>
 * 분석하는 배치 측정이라, 스크레이프 시점을 놓치면 값이 감쇠해 사라진다. 버킷은 누적 카운터라
 * {@code histogram_quantile(0.95, ...)}로 측정 구간을 나중에 잘라 볼 수 있다.
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

    /**
     * <b>Curator 가 목록 참조를 위조한 빈도</b> (ROADMAP 6-7). {@code SEEDED}·{@code LISTED} 가
     * 카카오 검증을 생략하는 근거는 "목록에 있는 것은 실존이 확인됐다"인데, 이 값이 크면 그 전제가
     * 실제로 얼마나 자주 깨지는지를 말해 준다.
     *
     * <p><b>강등만 센다.</b> 자리 번호가 범위 밖이거나 상호명이 비어 있어 <b>폐기</b>된 경우는
     * 여기 오지 않는다 — 섞으면 "얼마나 자주 위조가 일어나는가" 라는 질문에 다른 사건이 섞인다.
     */
    public static final String CANDIDATE_DEMOTED = "ai.candidate.demoted";

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

    /**
     * <b>단계별 지연 분포</b> (ROADMAP 7-5). 이 값이 202 Accepted 전환 여부의 근거가 된다 —
     * 설계는 그 판단을 "먼저 완성해 실측하고, p95가 목표를 넘는 것을 데이터로 확인한 뒤"로 미뤄뒀다.
     *
     * <p><b>단계를 나눠 재는 이유.</b> 합계만 보면 느려졌다는 것은 알아도 어디를 고쳐야 할지 모른다.
     * 설계의 단계별 예산과 같은 축으로 잘라야 추정치와 실측을 대조할 수 있다.
     *
     * <p><b>{@code outcome} 태그를 두지 않는다.</b> {@link #llmCall}이 결말별로 나누는 이유는
     * "타임아웃이 늘어 p95가 나빠진 것"과 "모델이 느려진 것"을 갈라야 하기 때문인데, 스테이지는
     * 예외를 올리지 않고 degrade하므로 그 축이 사실상 상수가 된다.
     */
    public static final String PIPELINE_DURATION = "ai.course.pipeline.duration";

    /**
     * <b>요청 하나가 시작부터 끝까지 걸린 시간</b> (ROADMAP 7-5 보강).
     *
     * <p>{@link #PIPELINE_DURATION}의 단계별 값을 더해도 이 값이 되지 않는다 — 단계별 p95의 합은
     * 전체 p95가 아니다. 단계가 서로 독립이면 "모든 단계가 동시에 최악인 요청"은 실제 20명 중
     * 1명보다 드물어 합이 과대평가고, 외부 요인(OpenAI 전반 지연 등)으로 단계들이 <b>같이</b>
     * 느려지면 합에 가깝거나 넘을 수도 있다 — 어느 쪽인지는 단계별 분포만으로 알 수 없다.
     *
     * <p><b>11-2의 202 Accepted 전환 판단은 정확히 이 값(요청 전체 p95)에 걸려 있다.</b> 판단
     * 기준이 부분합이 아니라 전체 시간이므로, 태그 없는 단일 타이머로 따로 잰다.
     *
     * <p>DB 저장(30~80ms 설계 추정)·직렬화는 포함하지 않는다 — 파이프라인이 전체 시간의
     * 대부분을 차지해 202 판단에는 이 값으로 충분하고, 그 나머지까지 재려면 컨트롤러 계층에
     * 태그 없는 타이머를 하나 더 둬야 하는데 지금은 그 계층에 연결된 경로가 없다(8단계 스위치 전).
     */
    public static final String REQUEST_DURATION = "ai.course.request.duration";

    /**
     * <b>최종 코스에 실린 장소가 어디서 왔는가</b> (ROADMAP 7-5, 5-8에서 이관).
     * 5-8이 이걸 이관한 이유는 분모가 거기 없기 때문이다 — "채택됐다"는 배치가 확정된 뒤에만
     * 알 수 있고, 그 시점은 7단계에서 처음 생긴다.
     *
     * <p><b>8-7 삭제 로그의 분모다.</b> 둘이 같은 태그 축({@code source}·{@code modifier})을 써야
     * "{@code SUGGESTED} 유래 장소의 삭제율이 {@code SEEDED} 대비 높은가", "스타일 modifier 유래가
     * 기본 쿼리 유래보다 높은가"를 계산할 수 있다 — 그 둘이 9단계 착수 조건의 절반이다.
     *
     * <p>설계의 관측 표는 4축({@code source, modifier, seeded, official})이었으나 <b>2축으로 줄였다</b> —
     * 후보 공급에서 카카오가 빠지면서({@code SEEDED}는 네이버, {@code LISTED}는 TourAPI 승계)
     * {@code seeded}·{@code official}이 {@code source}가 이미 말하는 것의 재표현이 됐기 때문이다.
     */
    public static final String CANDIDATE_ADOPTED = "ai.candidate.adopted";

    /**
     * <b>슬롯의 선택이 Curator(LLM)에게서 왔는가, 폴백 코드에서 왔는가.</b>
     *
     * <p>7-3의 폴백은 Curator가 죽어도 후보 목록으로 자리를 채우므로 <b>응답은 200이고 코스는
     * 멀쩡해 보인다</b> — 바뀌는 것은 내용뿐이라 에러율·지연 어느 지표에도 잡히지 않는다.
     * 이 값이 그 상태를 드러내는 유일한 신호다.
     *
     * <p>{@link #CANDIDATE_ADOPTED}로는 가릴 수 없다. 그쪽의 {@code source}는 후보의 <b>출처</b>를
     * 말할 뿐이라, LLM이 고른 시드 후보와 코드가 채운 시드 후보가 똑같이 {@code seeded}로 찍힌다.
     *
     * <p>{@link SlotFillOutcome}의 세 값이 전체를 나누므로 <b>분모를 따로 둘 필요가 없다.</b>
     */
    public static final String CURATION_SLOT = "ai.curation.slot";

    /** 네이버 지역검색 시더 (전 슬롯의 인기 축). */
    public static final String SOURCE_NAVER_LOCAL = "naver_local";

    /** 한국관광공사 TourAPI (관광 슬롯의 커버리지·분류 축). */
    public static final String SOURCE_TOUR_API = "tour_api";

    /**
     * 지연 히스토그램의 하한. {@code ROUTE}는 3-6 벤치마크에서 3일 4.0ms라 이보다 아래는 재도 의미가 없다.
     */
    private static final Duration LATENCY_MIN = Duration.ofMillis(1);

    /**
     * 파이프라인 단계 지연의 상한. <b>요청 예산({@code ai.course.budget-ms}, 기본 30초)과 같은 값이다</b> —
     * 모든 스테이지가 그 예산 안에서 기다림을 자르므로 이보다 오래 걸리는 단계는 원리적으로 없고,
     * {@code +Inf} 버킷에 값이 잡힌다면 그 자체가 "예산을 넘겼다"는 답이다.
     */
    private static final Duration PIPELINE_LATENCY_MAX = Duration.ofSeconds(30);

    /**
     * LLM 호출 1건의 상한. 세마포어 대기({@code llm.timeout-ms})와 호출({@code llm.timeout-ms})이
     * 겹치는 최악을 20 + 20초로 보고 여유를 뒀다.
     */
    private static final Duration LLM_LATENCY_MAX = Duration.ofSeconds(45);

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
        for (DemotionReason reason : DemotionReason.values()) {
            demotedCounter(reason);
        }
        for (PipelineStage stage : PipelineStage.values()) {
            pipelineTimer(stage);
        }
        requestTimer();
        for (CandidateSourceType source : CandidateSourceType.values()) {
            for (boolean fromModifier : new boolean[]{true, false}) {
                adoptedCounter(source, fromModifier);
            }
        }
        for (SlotFillOutcome outcome : SlotFillOutcome.values()) {
            curationSlotCounter(outcome);
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
     * 강등 집계를 사유별로 올린다 (ROADMAP 6-7).
     *
     * <p>{@code CuratedChoiceValidator} 가 순수 함수로 남기 위해 집계를 값으로 돌려주므로,
     * 레지스트리를 만지는 것은 그 호출자인 {@code CuratorAgent} 다(5-6 이 세운 주입 패턴).
     */
    public void candidateDemoted(DemotionReason reason, int count) {
        if (count > 0) {
            demotedCounter(reason).increment(count);
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
            .publishPercentileHistogram()
            .minimumExpectedValue(LATENCY_MIN)
            .maximumExpectedValue(LLM_LATENCY_MAX)
            .register(registry)
            .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 스테이지 하나의 경과 시간 (ROADMAP 7-5).
     *
     * <p>실패해도 기록한다 — degrade로 끝난 스테이지도 그만큼 시간을 썼고, 그 시간이 예산을
     * 먹은 것은 성공했을 때와 다르지 않다.
     */
    public void pipelineStage(PipelineStage stage, long durationNanos) {
        pipelineTimer(stage).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 요청 하나의 시작부터 끝까지 (ROADMAP 7-5 보강). 실패해도 기록한다 — hard fail로 끝난
     * 요청도 그 시간만큼 예산을 썼다는 사실은 성공했을 때와 다르지 않다.
     */
    public void requestDuration(long durationNanos) {
        requestTimer().record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 최종 코스에 실린 장소를 출처별로 집계한다 (ROADMAP 7-5).
     *
     * @param fromModifier 스타일 modifier 쿼리에서 온 후보인가
     *                     ({@code GroundedPlace.matchedModifier != null}). 8-7 삭제 로그가
     *                     SEO 편승을 재는 축이라 여기서도 같은 이름으로 나눠 둔다
     */
    public void candidateAdopted(CandidateSourceType source, boolean fromModifier, int count) {
        if (count > 0) {
            adoptedCounter(source, fromModifier).increment(count);
        }
    }

    /**
     * 슬롯 하나가 어떻게 채워졌는지 (ROADMAP 7-3 관측).
     *
     * <p>{@code DeterministicCuration}이 순수 함수로 남기 위해 집계를 값으로 돌려주므로,
     * 레지스트리를 만지는 것은 그 호출자인 {@code AiCoursePipeline}이다(5-6이 세운 주입 패턴,
     * 6-7의 강등 집계와 같은 구조).
     */
    public void curationSlot(SlotFillOutcome outcome, int count) {
        if (count > 0) {
            curationSlotCounter(outcome).increment(count);
        }
    }

    private Counter curationSlotCounter(SlotFillOutcome outcome) {
        return Counter.builder(CURATION_SLOT)
            .tag("result", tag(outcome.name()))
            .register(registry);
    }

    private Timer pipelineTimer(PipelineStage stage) {
        return Timer.builder(PIPELINE_DURATION)
            .tag("stage", tag(stage.name()))
            .publishPercentileHistogram()
            .minimumExpectedValue(LATENCY_MIN)
            .maximumExpectedValue(PIPELINE_LATENCY_MAX)
            .register(registry);
    }

    private Timer requestTimer() {
        return Timer.builder(REQUEST_DURATION)
            .publishPercentileHistogram()
            .minimumExpectedValue(LATENCY_MIN)
            .maximumExpectedValue(PIPELINE_LATENCY_MAX)
            .register(registry);
    }

    private Counter adoptedCounter(CandidateSourceType source, boolean fromModifier) {
        return Counter.builder(CANDIDATE_ADOPTED)
            .tag("source", tag(source.name()))
            .tag("modifier", Boolean.toString(fromModifier))
            .register(registry);
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

    private Counter demotedCounter(DemotionReason reason) {
        return Counter.builder(CANDIDATE_DEMOTED)
            .tag("reason", tag(reason.name()))
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
