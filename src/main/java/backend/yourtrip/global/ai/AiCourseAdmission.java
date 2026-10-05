package backend.yourtrip.global.ai;

import backend.yourtrip.global.ai.config.AiAdmissionProperties;
import backend.yourtrip.global.exception.RetryLaterException;
import backend.yourtrip.global.exception.errorCode.AiCourseErrorCode;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * AI 코스 생성 요청의 서버당 동시 입장 제한 (#192).
 *
 * <h2>왜 LLM 슬롯과 별도로 두는가</h2>
 * 슬롯({@code llm.max-concurrent-calls})은 <b>호출</b> 단위라 넘친 호출을 기다리게 할 뿐 요청을
 * 거절하지 않는다. 그래서 동시 요청이 슬롯 공급을 넘으면 모두가 줄을 서고, 모두가 예산을 다 쓴 뒤
 * 폴백으로 품질이 떨어진 코스를 받는다(동시 8명 요청의 50%). 이 게이트는 <b>요청</b> 단위로
 * 받아들일 수를 묶는다 — 받아들인 요청은 슬롯을 실제로 쓸 수 있고, 넘친 요청은 35초를 기다리는
 * 대신 바로 재시도 안내를 받는다. 덤으로 AI 요청이 쥐는 Tomcat 워커도 이 수로 묶인다(벌크헤드).
 *
 * <h2>기다리지 않는다</h2>
 * {@link Semaphore#tryAcquire()}를 대기 없이 부른다. 대기는 {@code CourseDeadline}이 시작되기 전에
 * 일어나 응답 최대 시간을 "대기 + 예산"으로 늘리고, 포화 중에는 요청 하나가 자리를 20~35초 쥐어
 * 몇 초 기다려도 자리가 잘 나지 않는다. 재시도 간격은 {@code Retry-After}로 클라이언트에 맡긴다.
 *
 * <h2>알려진 한계</h2>
 * 단위가 요청 수라서, 일수가 긴 요청 하나가 LLM 작업을 {@code 1 + days}개 만드는 경우는 막지 못한다.
 * 그 경로는 일수 상한(#178)이 막는다.
 *
 * <p>근거: {@code docs/tasks/llm-performance/steps/STEP-admission-limit.md}
 */
@Component
public class AiCourseAdmission {

    private final Semaphore gate;
    private final int retryAfterSeconds;
    private final AiCourseMetrics metrics;

    public AiCourseAdmission(AiAdmissionProperties properties, AiCourseMetrics metrics) {
        this.gate = new Semaphore(properties.maxConcurrentRequests());
        this.retryAfterSeconds = properties.retryAfterSeconds();
        this.metrics = metrics;
        metrics.bindAdmissionGauge(gate, properties.maxConcurrentRequests());
    }

    /**
     * 자리가 있으면 {@code work}를 실행하고, 끝나면(예외로 끝나도) 자리를 돌려준다.
     *
     * <p>획득과 해제를 호출자에게 나눠 맡기지 않고 이 메서드 하나에 가둔 이유는, 해제를 빠뜨리면
     * 자리가 영구히 줄어드는데 그 결함이 <b>상한 횟수만큼 쌓인 뒤에야 전면 429로</b> 드러나기 때문이다.
     *
     * @throws RetryLaterException {@code AI_COURSE_BUSY}(429). {@code work}는 실행되지 않았다
     */
    public <T> T admit(Supplier<T> work) {
        if (!gate.tryAcquire()) {
            metrics.admission(AiCourseMetrics.ADMISSION_REJECTED);
            throw new RetryLaterException(AiCourseErrorCode.AI_COURSE_BUSY, retryAfterSeconds);
        }
        metrics.admission(AiCourseMetrics.ADMISSION_ADMITTED);
        try {
            return work.get();
        } finally {
            gate.release();
        }
    }
}
