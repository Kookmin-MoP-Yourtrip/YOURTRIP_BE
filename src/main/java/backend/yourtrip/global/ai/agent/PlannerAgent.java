package backend.yourtrip.global.ai.agent;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.LlmCall;
import backend.yourtrip.global.ai.LlmClient;
import backend.yourtrip.global.ai.agent.dto.PlannerResponse;
import backend.yourtrip.global.ai.exception.LlmException;
import backend.yourtrip.global.ai.exception.LlmTransportException;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.prompt.KeywordRenderer;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.prompt.PromptTemplate;
import backend.yourtrip.global.ai.prompt.ResponseSchema;
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
 * 코스의 골격을 정하는 에이전트 — 컨셉·제목·day별 권역과 슬롯 구성 (ROADMAP 6-2).
 *
 * <h2>장소명을 한 개도 생성하지 않는다</h2>
 * 이 에이전트의 응답 스키마에는 상호명이 들어갈 자리가 없다. 유일한 예외인 {@code anchor}도
 * 코스에 실리는 장소가 아니라 <b>권역 중심 좌표를 얻기 위한 검색 기준점</b>이라, 없는 이름을
 * 지어내도 지오코딩 캐스케이드(4-8)가 {@code area} → {@code location}으로 흘려보낸다.
 *
 * <h2>왜 Curator 앞에 별도 단계로 두는가</h2>
 * "단계를 잘게 나눠서"가 아니다. <b>Curator를 day별로 병렬 실행하려면 day별 권역이 먼저 확정돼야
 * 하고</b>, 후보 공급도 어느 권역에서 무엇을 검색할지가 정해져야 부를 수 있다. Planner는 품질
 * 장치가 아니라 <b>병렬화의 전제조건</b>이다.
 *
 * <h2>실패를 삼키지 않는다</h2>
 * 호출이 실패하면 {@link LlmException}을 그대로 올린다. 결정론적 기본 플랜
 * ({@link DefaultPlannerPlans})으로 degrade할지는 <b>파이프라인의 판단</b>이지 에이전트의 판단이
 * 아니다(7-3). 여기서 조용히 기본 플랜을 돌려주면 "LLM이 죽었다"는 사실이 지표에서 사라진다.
 *
 * <p><b>알려진 한계</b> — 데드라인으로 대기를 끊어도 <b>이미 떠난 LLM 호출 자체는 취소되지
 * 않는다.</b> 그 호출은 응답이 올 때까지 세마포어 permit을 계속 쥐고 있다. 요청 전체를 어떻게
 * 잘라낼지는 7-1이 파이프라인 데드라인과 함께 다룬다.
 */
@Component
@Slf4j
public class PlannerAgent {

    /** {@code llm.agents}의 키이자 메트릭 태그. 설정에 없는 이름을 쓰면 기동이 아니라 호출이 실패한다. */
    public static final String AGENT_NAME = "planner";

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;
    private final Executor aiAgentExecutor;

    public PlannerAgent(LlmClient llmClient, PromptLoader promptLoader,
        @Qualifier("aiAgentExecutor") Executor aiAgentExecutor) {
        this.llmClient = llmClient;
        this.promptLoader = promptLoader;
        this.aiAgentExecutor = aiAgentExecutor;
    }

    public PlannerPlan plan(String location, int days, List<KeywordType> keywords,
        CourseDeadline deadline) {
        // duration 키워드는 프롬프트에 싣지 않는다(6-5). 다만 명백한 모순은 남긴다 —
        // 잦다면 고칠 곳이 코스 생성이 아니라 키워드를 고르는 화면이기 때문이다.
        KeywordRenderer.durationConflict(keywords, days).ifPresent(log::warn);

        if (deadline.expired()) {
            throw new LlmTransportException(AGENT_NAME, 0,
                "예산이 소진돼 Planner 를 호출하지 못했다", null);
        }

        PlannerResponse response = await(buildCall(location, days, keywords), deadline);
        return PlannerPlanNormalizer.normalize(response, location, days);
    }

    /**
     * <b>고정 규칙은 system, 가변 데이터는 user.</b> 나누는 이유는 취향이 아니라 향후 컨텍스트
     * 캐싱 때문이다 — 요청마다 바뀌지 않는 부분이 어디까지인지가 자명해야 캐시 경계를 그을 수 있다.
     */
    private LlmCall<PlannerResponse> buildCall(String location, int days,
        List<KeywordType> keywords) {
        return new LlmCall<>(
            AGENT_NAME,
            promptLoader.render(PromptTemplate.PLANNER_SYSTEM, Map.of()),
            promptLoader.render(PromptTemplate.PLANNER_USER, Map.of(
                "location", location,
                "days", String.valueOf(days),
                "keywords", KeywordRenderer.render(keywords))),
            PlannerResponse.class,
            promptLoader.schema(ResponseSchema.PLANNER));
    }

    /**
     * 호출을 던지고 <b>남은 예산까지만</b> 기다린다.
     *
     * <p>단일 호출인데도 비동기로 던지는 이유는 {@code llm.timeout-ms}(호출 1건의 상한)와
     * 요청 전체 예산이 다른 값이기 때문이다. 20초짜리 호출 상한만으로는 "Planner에서 이미
     * 예산의 절반을 썼다"를 표현할 수 없다.
     */
    private PlannerResponse await(LlmCall<PlannerResponse> call, CourseDeadline deadline) {
        CompletableFuture<PlannerResponse> future = llmClient.generateAsync(call, aiAgentExecutor);
        try {
            return future.get(deadline.remainingMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new LlmTransportException(AGENT_NAME, 1,
                "Planner 응답이 예산 안에 오지 않았다", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmTransportException(AGENT_NAME, 1, "Planner 대기가 인터럽트됐다", e);
        } catch (ExecutionException e) {
            // 어댑터가 이미 우리 예외 타입으로 번역해 뒀다 — 다시 감싸면 재시도 계층 정보가 묻힌다.
            if (e.getCause() instanceof LlmException llmException) {
                throw llmException;
            }
            throw new LlmTransportException(AGENT_NAME, 1,
                "Planner 호출이 예상 밖 오류로 실패했다", e.getCause());
        }
    }
}
