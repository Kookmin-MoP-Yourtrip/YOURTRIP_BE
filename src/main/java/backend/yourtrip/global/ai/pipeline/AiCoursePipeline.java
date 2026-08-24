package backend.yourtrip.global.ai.pipeline;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.agent.CuratorAgent;
import backend.yourtrip.global.ai.agent.DefaultPlannerPlans;
import backend.yourtrip.global.ai.agent.PlannerAgent;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateRetrievalStage;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.config.AiCourseProperties;
import backend.yourtrip.global.ai.exception.LlmException;
import backend.yourtrip.global.ai.grounding.GroundedDay;
import backend.yourtrip.global.ai.grounding.GroundedPlace;
import backend.yourtrip.global.ai.grounding.GroundedSlot;
import backend.yourtrip.global.ai.grounding.GroundingStage;
import backend.yourtrip.global.ai.grounding.PlaceUrlEnricher;
import backend.yourtrip.global.ai.route.RouteOptimizer;
import backend.yourtrip.global.ai.route.RoutePlace;
import backend.yourtrip.global.ai.route.RouteRequest;
import backend.yourtrip.global.ai.route.RoutedDay;
import backend.yourtrip.global.ai.route.RoutedPlace;
import backend.yourtrip.global.ai.route.TravelMode;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.AiCourseErrorCode;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 여섯 조각을 하나의 요청으로 잇는다 (ROADMAP 7-1).
 *
 * <pre>
 *   Planner -&gt; CandidateRetrieval -&gt; Curator -&gt; Grounding -&gt; RouteOptimizer -&gt; PlaceUrlEnricher
 * </pre>
 *
 * <p>LLM 호출은 {@code 1 + days}회다. {@code PlaceSignalStage}·{@code CriticAgent}·
 * {@code CandidateRefiner}는 V1 범위 밖이라 여기 없다.
 *
 * <h2>이 클래스만 갖는 책임 셋</h2>
 * 스테이지들은 전부 fail-open이라 <b>어디에서도 사용자 대면 실패가 나지 않는다.</b> 그래서
 * 아래 셋은 조립 지점인 여기에서만 성립한다.
 *
 * <ol>
 *   <li><b>실패 판정</b> — {@code CourseDeadline}과 {@code LlmException}의 javadoc이 모두
 *       "무엇을 할지는 7단계가 정한다"고 명시적으로 미뤄둔 것이다 (7-4)</li>
 *   <li><b>스테이지를 건너뛰는 degrade</b> — Planner 실패 시 기본 플랜, Curator가 비운 자리를
 *       후보 목록으로 채우기. 둘 다 <b>두 스테이지를 동시에 보는 지점</b>에서만 가능하다 (7-3)</li>
 *   <li><b>배치가 확정되는 시점</b> — {@code ai.candidate.adopted}가 5-8에서 이리로 옮겨온 이유다.
 *       "최종 코스에 채택됐다"는 순서가 정해진 뒤에만 알 수 있다 (7-5)</li>
 * </ol>
 *
 * <h2>degrade, don't fail</h2>
 * 후보 공급 전면 실패는 빈 풀로 진행하고(초안 구조로 완전 degrade), day 하나가 통째로 비어도
 * 통과시킨다. <b>hard fail은 전 day 장소 0개 하나뿐이다</b> — 좌표 없는 코스는 지도·동선이라는
 * 핵심 가치를 잃는데, <b>지금 코드가 {@code 0.0/0.0}으로 저장해 성공을 위장하는 것이 정확히
 * 그 실수다</b>.
 *
 * <p><b>스레드 안전.</b> 상태를 갖지 않는다 — 요청별 값은 전부 지역 변수이고
 * {@code CourseDeadline}도 진입점에서 요청당 하나 만든다.
 */
@Slf4j
@Component
public class AiCoursePipeline {

    private final PlannerAgent plannerAgent;
    private final CandidateRetrievalStage candidateRetrievalStage;
    private final CuratorAgent curatorAgent;
    private final GroundingStage groundingStage;
    private final RouteOptimizer routeOptimizer;
    private final PlaceUrlEnricher placeUrlEnricher;
    private final AiCourseMetrics metrics;
    private final Duration budget;

    public AiCoursePipeline(PlannerAgent plannerAgent,
        CandidateRetrievalStage candidateRetrievalStage,
        CuratorAgent curatorAgent,
        GroundingStage groundingStage,
        RouteOptimizer routeOptimizer,
        PlaceUrlEnricher placeUrlEnricher,
        AiCourseMetrics metrics,
        AiCourseProperties properties) {
        this.plannerAgent = plannerAgent;
        this.candidateRetrievalStage = candidateRetrievalStage;
        this.curatorAgent = curatorAgent;
        this.groundingStage = groundingStage;
        this.routeOptimizer = routeOptimizer;
        this.placeUrlEnricher = placeUrlEnricher;
        this.metrics = metrics;
        this.budget = Duration.ofMillis(properties.budgetMs());
    }

    /**
     * 코스 초안 하나를 만든다.
     *
     * @throws BusinessException {@code AI_GROUNDING_FAILED}(전 day 장소 0개) 또는
     *                           {@code AI_COURSE_TIMEOUT}(그 원인이 예산 소진일 때)
     */
    public AiCourseDraft generate(CourseBrief brief) {
        // 요청당 한 번. 이후 모든 스테이지가 이 하나를 나눠 쓴다 - 스테이지마다 새로 만들면
        // 각자 예산을 다 쓸 수 있게 되어 전체 상한이 사라진다.
        CourseDeadline deadline = CourseDeadline.startingNow(budget);

        PlannerPlan plan = timed(PipelineStage.PLANNER, () -> planOrDefault(brief, deadline));

        CandidatePool pool = timed(PipelineStage.CANDIDATE_RETRIEVAL,
            () -> candidateRetrievalStage.retrieve(brief.location(), plan, brief.keywords(),
                deadline));

        List<CuratedDay> curated = timed(PipelineStage.CURATOR,
            () -> curateWithFallback(plan, pool, brief.keywords(), deadline));

        List<GroundedDay> grounded = timed(PipelineStage.GROUNDING,
            () -> groundingStage.ground(brief.location(), curated, pool, deadline));

        requireAnyPlace(grounded, deadline);

        Routed routed = timed(PipelineStage.ROUTE, () -> route(plan, grounded, brief.travelMode()));

        return assemble(plan, routed, brief.location(), deadline);
    }

    // Curator - 비운 자리는 코드가 채운다 (7-3)

    /**
     * Curator 결과의 빈 자리를 후보 목록으로 채우고, <b>누가 채웠는지를 집계로 남긴다</b>.
     *
     * <p>집계를 여기서 기록하는 이유는 {@code DeterministicCuration}이 순수 함수이기 때문이다 —
     * 6-7의 {@code CuratedChoiceValidator}가 강등 집계를 값으로 돌려주고 {@code CuratorAgent}가
     * 기록한 것과 같은 구조다.
     *
     * <p><b>이 집계가 없으면 "LLM 큐레이션이 꺼진 상태"가 관측되지 않는다.</b> 폴백은 응답을
     * 200으로 유지하므로 에러율에도 지연에도 잡히지 않고, 8-6 환각률 측정에서는 오히려 값이
     * 좋아 보인다 — 폴백이 채운 장소는 실존이 확인된 후보라 환각률이 구조적으로 0에 가깝다.
     */
    private List<CuratedDay> curateWithFallback(PlannerPlan plan, CandidatePool pool,
        List<KeywordType> keywords, CourseDeadline deadline) {
        DeterministicCuration.Filled filled = DeterministicCuration.fill(
            curatorAgent.curate(plan, pool, keywords, deadline), pool);
        filled.slotCounts().forEach(metrics::curationSlot);
        return filled.days();
    }

    // Planner - 실패해도 코스는 나온다 (7-3)

    /**
     * Planner가 실패하면 결정론적 기본 플랜으로 간다.
     *
     * <p>재호출하지 않는 이유는 <b>이 폴백이 발동한 상황이 곧 LLM이 느리거나 죽은 상황</b>이기
     * 때문이다. 어댑터가 이미 전송·의미 두 계층의 재시도를 소진하고 올린 예외라, 여기서 한 번 더
     * 부르면 남은 예산만 태운다.
     */
    private PlannerPlan planOrDefault(CourseBrief brief, CourseDeadline deadline) {
        try {
            return plannerAgent.plan(brief.location(), brief.days(), brief.keywords(), deadline);
        } catch (LlmException e) {
            log.warn("Planner 가 실패해 결정론적 기본 플랜으로 진행한다 (location={}, days={}): {}",
                brief.location(), brief.days(), e.getMessage());
            return DefaultPlannerPlans.of(brief.location(), brief.days());
        }
    }

    // 7-4. hard fail 은 여기 한 곳뿐이다

    /**
     * 전 day의 장소가 0개일 때만 실패시킨다.
     *
     * <p><b>두 코드가 같은 지점에서 갈리는 이유.</b> 사용자에게 도달하는 결과("코스를 못 만들었다")는
     * 같지만 원인이 다르다 — "시간이 모자랐다"와 "바깥 세상에 데이터가 없었다"를 뭉치면 운영에서
     * 어느 쪽을 고쳐야 할지 알 수 없다.
     *
     * <p><b>만료를 앞에서 따로 검사하지 않는 이유</b>는 스테이지들이 이미 만료를 각자 degrade로
     * 흡수하기 때문이다. 예산이 다 됐어도 장소가 남아 있으면 그 코스는 유효하다 — <b>만료가
     * 실제로 해가 된 경우에만</b> 판정하는 것이 정확하다.
     */
    private static void requireAnyPlace(List<GroundedDay> grounded, CourseDeadline deadline) {
        boolean anyPlace = grounded.stream()
            .flatMap(day -> day.slots().stream())
            .anyMatch(slot -> !slot.isEmpty());
        if (anyPlace) {
            return;
        }
        if (deadline.expired()) {
            log.error("전 day 의 장소가 0개이고 예산도 소진됐다 - 504 로 올린다");
            throw new BusinessException(AiCourseErrorCode.AI_COURSE_TIMEOUT);
        }
        log.error("전 day 의 장소가 0개다 - 좌표 소스 셋이 동시에 실패한 경우다. 503 으로 올린다");
        throw new BusinessException(AiCourseErrorCode.AI_GROUNDING_FAILED);
    }

    // RouteOptimizer - 슬롯당 1순위만 배치한다

    /**
     * 라우팅 결과와, 합성 id로 원래 장소를 되찾기 위한 색인.
     *
     * <p>{@code RoutePlace}는 이름·좌표만 나르고 주소·URL·{@code source}를 버리므로, 배치가
     * 끝난 뒤 {@link GroundedPlace}로 되돌릴 길이 필요하다. {@code GroundedPlace}에 안정 식별자가
     * 없어 <b>파이프라인이 만들어 주는 수밖에 없다.</b>
     */
    private record Routed(List<RoutedDay> days, Map<String, GroundedPlace> byId) {}

    private Routed route(PlannerPlan plan, List<GroundedDay> grounded, TravelMode travelMode) {
        Map<String, GroundedPlace> byId = new HashMap<>();
        List<RoutedDay> routedDays = new ArrayList<>(grounded.size());

        for (GroundedDay day : grounded) {
            List<RoutePlace> places = new ArrayList<>(day.slots().size());
            for (int slotIndex = 0; slotIndex < day.slots().size(); slotIndex++) {
                GroundedSlot slot = day.slots().get(slotIndex);
                // 슬롯당 1순위만 배치한다. 후보 3개는 1순위가 그라운딩에서 탈락했을 때
                // 2·3순위가 올라오기 위한 것이고, 그 승격은 GroundingStage 에서 이미 끝났다.
                Optional<GroundedPlace> preferred = slot.preferred();
                if (preferred.isEmpty()) {
                    continue;
                }
                GroundedPlace place = preferred.get();
                String id = "d%ds%d".formatted(day.day(), slotIndex);
                byId.put(id, place);
                places.add(new RoutePlace(id, place.name(), place.slotType(),
                    place.latitude(), place.longitude()));
            }
            // dayEndTime 은 넘기지 않는다 - Planner 가 내지 않는 값이고(6-2), 이른 종료를 주면
            // 3-5 의 축소 -> 드롭이 살아나 장소가 조용히 사라진다.
            routedDays.add(routeOptimizer.optimize(new RouteRequest(day.day(), places,
                dayStartTimeOf(plan, day.day()), null, travelMode)));
        }
        return new Routed(routedDays, byId);
    }

    /**
     * {@code null}이면 {@code RouteRequest}가 기본값(09:30)으로 채운다.
     *
     * <p>{@code map} 을 {@code findFirst} 뒤에 두는 것이 중요하다 — {@code dayStartTime}은
     * 실제로 {@code null}일 수 있는데({@code DefaultPlannerPlans}가 비워 둔다),
     * {@code Stream.findFirst()}는 첫 원소가 {@code null}이면 NPE를 던진다.
     * {@code Optional.map}은 그 값을 빈 {@code Optional}로 받아 준다.
     */
    private static LocalTime dayStartTimeOf(PlannerPlan plan, int day) {
        return plan.days().stream()
            .filter(dayPlan -> dayPlan.day() == day)
            .findFirst()
            .map(PlannerDayPlan::dayStartTime)
            .orElse(null);
    }

    // PlaceUrlEnricher - 평평하게 폈다가 인덱스로 되돌린다

    /**
     * URL을 보강하고 최종 초안으로 조립한다.
     *
     * <p>{@code PlaceUrlEnricher}가 day/슬롯 구조가 아니라 <b>평평한 리스트</b>를 받는 이유는
     * 남은 예산 안에서 부를 수 있는 만큼만 부르는 것이 그쪽의 관심사이고, 구조는 그쪽이 알 필요가
     * 없기 때문이다. 대신 <b>같은 크기·같은 순서로 돌려주는 것이 계약</b>이라 인덱스로 원자리에
     * 되돌릴 수 있다.
     */
    private AiCourseDraft assemble(PlannerPlan plan, Routed routed, String location,
        CourseDeadline deadline) {

        List<GroundedPlace> flat = new ArrayList<>();
        for (RoutedDay day : routed.days()) {
            for (RoutedPlace place : day.places()) {
                flat.add(routed.byId().get(place.place().id()));
            }
        }

        List<GroundedPlace> enriched = timed(PipelineStage.URL_ENRICH,
            () -> placeUrlEnricher.enrich(location, flat, deadline));

        if (enriched == null || enriched.size() != flat.size()) {
            // 계약 위반이다. 여기서 예외를 올리면 URL 이라는 부가 정보 때문에 코스가 통째로
            // 죽으므로, 보강 전 목록으로 되돌아간다 - placeUrl 은 절대 hard fail 이 아니다.
            log.error("URL 보강 결과의 크기가 입력과 다르다({} -> {}) - 보강 전 목록을 쓴다",
                flat.size(), enriched == null ? "null" : enriched.size());
            enriched = flat;
        }

        List<AiCourseDay> days = new ArrayList<>(routed.days().size());
        int cursor = 0;
        for (RoutedDay day : routed.days()) {
            List<AiCoursePlace> places = new ArrayList<>(day.places().size());
            for (RoutedPlace place : day.places()) {
                places.add(new AiCoursePlace(enriched.get(cursor++), place.startTime(),
                    place.stayMinutes()));
            }
            days.add(new AiCourseDay(day.day(), day.startTime(), day.endTime(), places));
        }

        recordAdopted(days);
        return new AiCourseDraft(plan.title(), plan.concept(), days);
    }

    // 7-5. 배치가 확정된 뒤에만 셀 수 있는 것

    /**
     * 최종 코스에 실린 장소를 출처별로 집계한다.
     *
     * <p>여기서만 셀 수 있는 이유는 <b>분모가 여기서 처음 생기기 때문</b>이다 — 후보 공급 시점의
     * "몇 개를 모았는가"는 채택률의 분모가 아니다. 8-7 삭제 로그가 같은 태그 축을 쓰므로 둘을
     * 나누면 출처별 삭제율이 나오고, 그 값이 9단계 착수 조건의 절반이다.
     */
    private void recordAdopted(List<AiCourseDay> days) {
        Map<CandidateSourceType, int[]> counts = new EnumMap<>(CandidateSourceType.class);
        for (AiCourseDay day : days) {
            for (AiCoursePlace place : day.places()) {
                GroundedPlace grounded = place.place();
                // [0] = 기본 쿼리 유래, [1] = 스타일 modifier 쿼리 유래
                int slot = grounded.matchedModifier() == null ? 0 : 1;
                counts.computeIfAbsent(grounded.source(), key -> new int[2])[slot]++;
            }
        }
        counts.forEach((source, byModifier) -> {
            metrics.candidateAdopted(source, false, byModifier[0]);
            metrics.candidateAdopted(source, true, byModifier[1]);
        });
    }

    /**
     * 스테이지 하나의 경과 시간을 잰다.
     *
     * <p>{@code finally}에서 기록하는 이유는 <b>실패한 스테이지도 시간을 썼기 때문</b>이다.
     * 성공했을 때만 재면 "느려서 죽은" 구간이 지표에서 사라져, 202 전환 판단이 낙관적으로 기운다.
     */
    private <T> T timed(PipelineStage stage, Supplier<T> action) {
        long startNanos = System.nanoTime();
        try {
            return action.get();
        } finally {
            metrics.pipelineStage(stage, System.nanoTime() - startNanos);
        }
    }
}
