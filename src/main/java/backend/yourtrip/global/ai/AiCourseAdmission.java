package backend.yourtrip.global.ai;

import backend.yourtrip.global.ai.config.AiAdmissionProperties;
import backend.yourtrip.global.exception.RetryLaterException;
import backend.yourtrip.global.exception.errorCode.AiCourseErrorCode;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * AI 코스 생성 요청의 서버당 입장 제한 — <b>LLM 작업 자리</b> 단위 (#192, #200).
 *
 * <h2>왜 LLM 슬롯과 별도로 두는가</h2>
 * 슬롯({@code llm.max-concurrent-calls})은 <b>호출</b> 단위라 넘친 호출을 기다리게 할 뿐 요청을
 * 거절하지 않는다. 그래서 동시 요청이 슬롯 공급을 넘으면 모두가 줄을 서고, 모두가 예산을 다 쓴 뒤
 * 폴백으로 품질이 떨어진 코스를 받는다(동시 8명 요청의 50%). 이 게이트는 <b>요청의 입구</b>에서
 * 받아들일 양을 묶는다 — 받아들인 요청은 슬롯을 실제로 쓸 수 있고, 넘친 요청은 35초를 기다리는
 * 대신 바로 재시도 안내를 받는다. 덤으로 AI 요청이 쥐는 Tomcat 워커도 이 양으로 묶인다(벌크헤드).
 *
 * <h2>왜 요청 수가 아니라 작업 자리로 세는가 (#200)</h2>
 * 요청 하나가 만드는 LLM 호출은 {@code 1 + 일수}개(Planner 1 + day 마다 Curator 1)다. 요청 수로 세면
 * 같은 상한 4가 1일 요청에는 호출 8개, 5일 요청에는 24개를 뜻한다. 실측(같은 시간대, 동시 4명):
 * 5일 요청은 받아들인 4건 중 1건이 예산을 다 썼고(6/24) 1일 요청은 슬롯 5개가 다 찬 순간이 없었다.
 * 요청 하나가 쓰는 슬롯 시간은 일수와 무관하게 <b>호출 하나당 약 6초</b>로 거의 일정해(5.4~6.9초),
 * 호출 수가 슬롯 부하의 좋은 척도다. 그래서 요청마다 자리 {@code 1 + 일수}개를 한꺼번에 떼고,
 * 총량은 실행기 크기가 아니라 <b>예산 안에 슬롯이 처리하는 호출 수</b>로 정한다(값의 근거는
 * {@code application.yml} 주석).
 *
 * <h2>요청 수 상한을 따로 두는 이유</h2>
 * 작업 자리는 LLM 호출이 끝나면 돌아오지만, 요청은 그 뒤 그라운딩(장소 API)·경로·URL 보강 동안에도 Tomcat
 * 워커를 쥔다. 자리만 세면 후속 단계가 느려질 때 자리가 빈 틈으로 새 요청이 계속 들어와 워커가 쌓인다 — 비교
 * 실험 C(#201)에서 장소 API 대기로 워커 32개가 모두 찬 것과 같은 경로다. 그래서 <b>요청 전체 수명</b> 동안 쥐는
 * 요청 자리를 함께 둔다. 값(8)은 작업 자리만으로 받을 수 있는 최대 요청 수(16 ÷ 1일 요청 2자리)와 같아, 평소에는
 * 작업 자리보다 먼저 걸리지 않고 후속 단계가 멈출 때만 막는다.
 *
 * <h2>기다리지 않는다</h2>
 * {@link Semaphore#tryAcquire(int)}를 대기 없이 부른다. 대기는 {@code CourseDeadline}이 시작되기 전에
 * 일어나 응답 최대 시간을 "대기 + 예산"으로 늘리고, 기다리는 동안 요청 스레드를 쥔다. 재시도 간격은
 * {@code Retry-After}로 클라이언트에 맡긴다. 기다리지 않으므로 긴 일정 요청이 "자리가 모이길 기다리다
 * 영영 못 들어가는" 굶주림은 없다 — 도착할 때마다 그 순간의 빈자리로 독립적으로 판정된다. 대신 자리를
 * 많이 쓰는 요청이 더 자주 거절되고, 이는 쓰는 만큼 막히는 의도된 비용이다. 일수별 거절률은
 * {@code days} 태그로 관측한다.
 *
 * <p>근거: {@code docs/tasks/llm-performance/steps/STEP-admission-limit.md},
 * {@code docs/tasks/ai-admission-day-weight/README.md}
 */
@Component
public class AiCourseAdmission {

    private final Semaphore gate;
    private final Semaphore requests;
    private final int retryAfterSeconds;
    private final AiCourseMetrics metrics;

    public AiCourseAdmission(AiAdmissionProperties properties, AiCourseMetrics metrics) {
        this.gate = new Semaphore(properties.maxWorkUnits());
        this.requests = new Semaphore(properties.maxRequests());
        this.retryAfterSeconds = properties.retryAfterSeconds();
        this.metrics = metrics;
        metrics.bindAdmissionGauge(gate, properties.maxWorkUnits());
        metrics.bindAdmissionRequestGauge(requests, properties.maxRequests());
    }

    /** {@code days}일 요청 하나가 떼는 자리 수 — Planner 1 + day 마다 Curator 1. */
    public static int unitsFor(int days) {
        return 1 + days;
    }

    /**
     * 요청 자리 1개와 작업 자리 {@link #unitsFor(int) 1 + days}개가 모두 있으면 떼어 {@code work}에 임대로 넘기고,
     * 끝나면(예외로 끝나도) 요청 자리와 작업에 묶이지 않은 작업 자리를 돌려준다. 작업에 묶인 자리는 그 작업이 끝날 때
     * 돌아간다({@link LlmWorkLease}). 둘 중 하나라도 모자라면 이미 뗀 쪽을 돌려주고 거절한다.
     *
     * <p>획득과 해제를 호출자에게 나눠 맡기지 않고 이 메서드 하나에 가둔 이유는, 해제를 빠뜨리면
     * 자리가 영구히 줄어드는데 그 결함이 <b>총량만큼 쌓인 뒤에야 전면 429로</b> 드러나기 때문이다.
     *
     * @throws RetryLaterException {@code AI_COURSE_BUSY}(429). {@code work}는 실행되지 않았다
     */
    public <T> T admit(int days, Function<LlmWorkLease, T> work) {
        int units = unitsFor(days);
        if (!requests.tryAcquire()) {
            throw reject(days);
        }
        if (!gate.tryAcquire(units)) {
            requests.release();
            throw reject(days);
        }
        metrics.admission(AiCourseMetrics.ADMISSION_ADMITTED, days);
        LlmWorkLease lease = new LlmWorkLease(gate, units);
        try {
            return work.apply(lease);
        } finally {
            lease.close();
            requests.release();
        }
    }

    private RetryLaterException reject(int days) {
        metrics.admission(AiCourseMetrics.ADMISSION_REJECTED, days);
        return new RetryLaterException(AiCourseErrorCode.AI_COURSE_BUSY, retryAfterSeconds);
    }
}
