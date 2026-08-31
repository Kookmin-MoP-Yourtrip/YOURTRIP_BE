package backend.yourtrip.global.ai.agent;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.LlmCall;
import backend.yourtrip.global.ai.LlmClient;
import backend.yourtrip.global.ai.agent.CuratedChoiceValidator.CurationOutcome;
import backend.yourtrip.global.ai.agent.dto.CuratorResponse;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.pipeline.CuratedDay;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.prompt.KeywordRenderer;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.prompt.PromptTemplate;
import backend.yourtrip.global.ai.prompt.ResponseSchema;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 후보 목록에서 자리마다 3개를 선호 순서로 고르는 에이전트 (ROADMAP 6-4).
 *
 * <h2>역할은 "회상"이 아니라 "선별"이다</h2>
 * 기존 단일 호출은 LLM에게 <b>장소를 기억해 내라</b>고 시켰고, 그래서 무인지 지역에서 이름을
 * 지어냈다. 여기서는 실존이 확인된 목록을 주고 <b>고르게</b> 한다 — "무엇이 테마에 맞는가"는
 * 목록이 대신할 수 없는 판단이라 LLM에 남기고, "무엇이 인기 있고 어디 있는가"는 데이터가 맡는다.
 *
 * <h2>day별 병렬 — 라운드를 가르지 않는다</h2>
 * day 수만큼 호출을 <b>전부 던지고 한 번만 기다린다</b>(5-8이 후보 공급에서 세운 패턴). 실질
 * 동시성 상한은 이 풀이 아니라 어댑터의 {@code llm.max-concurrent-calls} 세마포어가 정한다.
 * Curator가 다른 day를 모르는 것은 제약이 아니라 <b>병렬 실행의 대가로 받아들인 것</b>이고,
 * 그래서 전 day 중복 제거는 프롬프트가 아니라 그라운딩의 몫이다.
 *
 * <h2>실패는 그 day만 비운다</h2>
 * Planner와 달리 예외를 올리지 않는다. day 하나가 429를 맞았다고 코스 전체를 죽이면
 * "degrade, don't fail"의 정반대이고, <b>빈 자리를 결정론적으로 채우는 것은 7-3이 이미 맡기로
 * 한 일</b>이다. 실패 사실은 {@code ai.llm.call{outcome}}이 이미 센다.
 */
@Component
@Slf4j
public class CuratorAgent {

    /** {@code llm.agents}의 키이자 메트릭 태그. */
    public static final String AGENT_NAME = "curator";

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;
    private final AiCourseMetrics metrics;
    private final Executor aiAgentExecutor;

    public CuratorAgent(LlmClient llmClient, PromptLoader promptLoader, AiCourseMetrics metrics,
        @Qualifier("aiAgentExecutor") Executor aiAgentExecutor) {
        this.llmClient = llmClient;
        this.promptLoader = promptLoader;
        this.metrics = metrics;
        this.aiAgentExecutor = aiAgentExecutor;
    }

    /**
     * @return Planner의 day 순서와 <b>같은 길이·같은 순서</b>. 실패한 day도 빈 자리로 자리를 지킨다 —
     *         길이가 달라지면 뒤 단계가 day를 위치로 맞추지 못한다
     */
    public List<CuratedDay> curate(PlannerPlan plan, CandidatePool pool,
        List<KeywordType> keywords, CourseDeadline deadline) {
        List<PlannerDayPlan> days = plan == null ? List.of() : plan.days();
        if (days.isEmpty()) {
            return List.of();
        }
        CandidatePool candidatePool = pool == null ? CandidatePool.empty() : pool;

        if (deadline.expired()) {
            log.warn("Curator 진입 전에 예산이 소진됐다 — {}일 전부를 빈 자리로 넘긴다", days.size());
            return emptyDays(days, candidatePool);
        }

        String concept = plan.concept();
        String renderedKeywords = KeywordRenderer.render(keywords);
        List<CompletableFuture<CuratorResponse>> futures = days.stream()
            .map(day -> submit(day, candidatePool, concept, renderedKeywords))
            .toList();
        awaitAll(futures, deadline);

        return collect(days, candidatePool, futures);
    }

    // ── ① 호출 조립 ──────────────────────────────────────────────────────────

    /**
     * 호출 하나를 던지되 <b>실패를 예외가 아니라 {@code null}로 바꾼다.</b>
     *
     * <p><b>수확이 깨지는 것을 막기 위해서가 아니다</b> — {@code allOf(...)}는 자식 하나가 실패해도
     * <b>나머지가 전부 끝날 때까지 기다린 뒤</b> 예외로 완료된다(실측: 300ms 짜리 느린 future 와
     * 이미 실패한 future 를 함께 넣으면 309ms 에 끝났다). 그러니 예외를 그대로 둬도 다른 day 가
     * 잘려 나가지는 않는다.
     *
     * <p>실패를 값으로 바꾸는 이유는 <b>day 실패가 이 에이전트에서는 정상 사건이기 때문</b>이다.
     * 예외로 남겨 두면 ① {@code awaitAll}의 {@code ExecutionException} 분기가 매번 타서 예상된
     * 사건이 "예상 밖 오류"로 기록되고 ② 그 로그에는 <b>어느 day 가 실패했는지가 없다</b>
     * ({@code allOf}는 실패 하나만 전달한다). 여기서 흡수하면 day 번호를 남길 수 있고, 위쪽 분기는
     * 진짜 예상 밖 결함 전용으로 남는다.
     *
     * <p>5-8 의 후보 공급이 실패를 값으로 돌려주는 것({@code Found}/{@code Empty}/{@code Failed})과
     * 같은 성질을 LLM 포트 위에 만들어 주는 셈이다.
     */
    private CompletableFuture<CuratorResponse> submit(PlannerDayPlan day, CandidatePool pool,
        String concept, String keywords) {
        return llmClient.generateAsync(buildCall(day, pool, concept, keywords), aiAgentExecutor)
            .handle((response, error) -> {
                if (error != null) {
                    log.warn("day {} 의 Curator 호출이 실패했다 — 그 day 를 비운다: {}",
                        day.day(), error.toString());
                    return null;
                }
                return response;
            });
    }

    private LlmCall<CuratorResponse> buildCall(PlannerDayPlan day, CandidatePool pool,
        String concept, String keywords) {
        return new LlmCall<>(
            AGENT_NAME,
            promptLoader.render(PromptTemplate.CURATOR_SYSTEM, Map.of()),
            promptLoader.render(PromptTemplate.CURATOR_USER, Map.of(
                "day", String.valueOf(day.day()),
                "area", day.area(),
                "theme", day.theme() == null ? concept : day.theme(),
                "concept", concept,
                "keywords", keywords,
                "slots", CandidateListRenderer.renderSlots(day),
                "candidates", CandidateListRenderer.renderCandidates(day, pool))),
            CuratorResponse.class,
            promptLoader.schema(ResponseSchema.CURATOR));
    }

    // ── ② 수확 — 끝난 것만 쓰고, 못 끝난 day는 비운다 ────────────────────────

    private List<CuratedDay> collect(List<PlannerDayPlan> days, CandidatePool pool,
        List<CompletableFuture<CuratorResponse>> futures) {
        Map<DemotionReason, Integer> demotions = new EnumMap<>(DemotionReason.class);
        List<CuratedDay> curated = new ArrayList<>(days.size());

        for (int index = 0; index < days.size(); index++) {
            PlannerDayPlan day = days.get(index);
            CuratorResponse response = responseOf(futures.get(index), day.day());
            // 응답이 없으면 null 을 넘긴다 — 검증기가 "자리는 있고 선택은 없는" day 를 만들어 준다.
            // 실패 경로를 위해 따로 분기를 두지 않는 것이 요점이다.
            CurationOutcome outcome = CuratedChoiceValidator.validate(day, pool, response);
            outcome.demotions().forEach((reason, count) -> demotions.merge(reason, count, Integer::sum));
            curated.add(outcome.day());
        }

        demotions.forEach(metrics::candidateDemoted);
        if (!demotions.isEmpty()) {
            log.warn("Curator 선택 중 {}건을 SUGGESTED 로 강등했다: {}",
                demotions.values().stream().mapToInt(Integer::intValue).sum(), demotions);
        }
        return curated;
    }

    /**
     * 아직 끝나지 않은 호출은 {@code null}. 실패는 {@link #submit}에서 이미 {@code null}이 됐으므로
     * 여기서 볼 것은 <b>예산에 잘렸는가</b> 하나뿐이다.
     */
    private static CuratorResponse responseOf(CompletableFuture<CuratorResponse> future, int day) {
        if (!future.isDone() || future.isCancelled()) {
            log.warn("day {} 의 Curator 응답이 예산 안에 오지 않았다 — 그 day 를 비운다", day);
            return null;
        }
        return future.join();
    }

    /** Planner 의 자리만 있고 선택은 없는 day 들. 예산 소진·전면 실패의 결말이다. */
    private static List<CuratedDay> emptyDays(List<PlannerDayPlan> days, CandidatePool pool) {
        return days.stream()
            .map(day -> CuratedChoiceValidator.validate(day, pool, null).day())
            .toList();
    }

    private static void awaitAll(List<CompletableFuture<CuratorResponse>> futures,
        CourseDeadline deadline) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(deadline.remainingMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Curator 가 예산 안에 끝나지 않았다 — 끝난 day 만 쓴다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Curator 대기가 인터럽트됐다 — 끝난 day 만 쓴다");
        } catch (ExecutionException e) {
            // submit 이 실패를 null 로 바꾸므로 여기 오는 것은 예상 밖 결함이다. 그래도
            // 나머지 day 는 살린다.
            log.warn("Curator 대기 중 예상 밖 오류가 났다 — 끝난 day 만 쓴다: {}", e.getCause());
        }
    }
}
