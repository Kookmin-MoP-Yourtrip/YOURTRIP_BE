package backend.yourtrip.global.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 애플리케이션의 전용 스레드풀을 한곳에 모아 둔다.
 *
 * <p>풀을 나누는 기준은 "무엇을 하는 작업인가"가 아니라 <b>포화됐을 때 무엇을 지켜야 하는가</b>다.
 * 서로 다른 성격의 작업이 한 풀을 쓰면 한쪽의 지연이 다른 쪽의 슬롯을 잠식한다(벌크헤드).
 *
 * <ul>
 *   <li>{@code courseImageCleanupExecutor} — 커밋 이후의 S3 정리 후처리</li>
 *   <li>{@code aiAgentExecutor} — AI 코스 생성의 LLM 호출 (ROADMAP 5-1)</li>
 *   <li>{@code placeGroundingExecutor} — AI 코스 생성의 장소 API 호출 (ROADMAP 5-1)</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 코스 삭제 시 자식 이미지가 많아도 API 응답 시간이 늘어나지 않도록, S3 정리처럼 외부 I/O가
     * 섞인 후처리를 요청 스레드에서 분리하기 위한 전용 스레드풀. DB 삭제(cascade)는 자식 row 수가
     * 적어(코스 1건당 최대 수백 건 수준) 이 규모에서는 동기로 유지해도 무방하지만, 이미지마다
     * 개별 네트워크 호출이 필요한 S3 삭제는 이미지 개수에 비례해 응답이 늘어질 수 있어 분리한다.
     */
    @Bean(name = "courseImageCleanupExecutor")
    public ThreadPoolTaskExecutor courseImageCleanupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // S3 삭제는 네트워크 I/O 대기가 대부분이라 CPU 코어 수보다 넉넉하게 잡아도 이득이 있다.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        // 큐가 가득 차면 호출 스레드가 직접 실행 — 응답 자체를 막지는 않되(이미 커밋 이후 시점),
        // 무한정 쌓이는 것보다 자연스럽게 degrade하는 편이 안전하다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("course-image-cleanup-");
        executor.initialize();
        return executor;
    }

    /**
     * AI 코스 생성의 <b>LLM 호출 전용</b> 풀 (Planner 1회 + Curator day별).
     *
     * <p>장소 API와 벌크헤드로 나누는 이유: LLM은 3~10초짜리 <b>소수</b>, 장소 API는 0.15~0.3초짜리
     * <b>다수</b>다. 최적 풀 크기가 다르고 포화 시 대응도 달라야 하는데, 한 풀을 공유하면 장소 API가
     * 느려질 때 그 대기가 LLM 슬롯을 잠식한다.
     *
     * <p>동시 호출의 실질 상한은 이 풀이 아니라 {@code llm.max-concurrent-calls} 세마포어가 정한다
     * (OpenAI RPM/TPM 티어 보호). 풀은 그보다 넉넉해야 세마포어 대기가 큐잉으로 바뀌지 않는다.
     */
    @Bean(name = "aiAgentExecutor")
    public ThreadPoolTaskExecutor aiAgentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        // 큐가 짧다 — LLM 호출이 큐에서 오래 기다리면 그 요청은 어차피 데드라인을 넘긴다.
        executor.setQueueCapacity(50);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("ai-agent-");
        executor.initialize();
        return executor;
    }

    /**
     * AI 코스 생성의 <b>장소 API 호출 전용</b> 풀 — 카카오·네이버·TourAPI가 <b>공유</b>한다.
     *
     * <p>provider별로 나누지 않는 이유: 셋 다 짧은 I/O이고 파이프라인에서 단계별로 순차 실행돼
     * 동시에 경합하지 않는다(후보 공급 → 그라운딩 → URL 보강). 나누면 유휴 스레드만 늘어난다.
     *
     * <p><b>{@code CallerRunsPolicy}를 쓰는 판단 근거.</b> 이 레포에는 caller-runs가 응답 경로에서
     * 역효과였던 실측 선례가 있다 — 과거 {@code cloudFrontSigningExecutor}가 요청 스레드까지 서명
     * <b>CPU</b>를 떠안으면서 커넥션 점유가 오히려 악화됐다. 여기서 가져올 교훈은 정책 이름이 아니라
     * 판단 기준이다: caller-runs가 이득인지 손해인지는 그 작업이 CPU냐 I/O냐로 갈린다. 장소 API는
     * I/O라 "거부보다 느린 성공"이 낫다. 다만 요청 스레드가 I/O를 직접 수행하면 사실상 순차 실행으로
     * 퇴화하므로, "무한정 느린 성공"이 되는 것은 파이프라인 하드 데드라인이 막는다(ROADMAP 5-5).
     */
    @Bean(name = "placeGroundingExecutor")
    public ThreadPoolTaskExecutor placeGroundingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 요청 하나가 장소 API 호출 수십 건으로 fan-out된다(후보 공급 ~30 + 검증 ~15 + URL 보강 ~15).
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("place-grounding-");
        executor.initialize();
        return executor;
    }
}
