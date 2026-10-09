package backend.yourtrip.global.ai;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 입장한 요청 하나가 쥔 <b>LLM 작업 자리</b> (#200).
 *
 * <p>{@link AiCourseAdmission}이 요청 하나에 자리 {@code 1 + 일수}개(Planner 1 + day 마다 Curator 1)를
 * 한꺼번에 떼어 이 객체에 담아 넘긴다. 에이전트가 LLM 작업을 실행기에 올릴 때 {@link #track}으로
 * 자리 하나를 그 작업에 묶고, <b>작업이 실제로 끝나는 순간</b> 그 자리를 게이트에 돌려준다.
 *
 * <h2>왜 요청이 끝날 때가 아니라 작업이 끝날 때 돌려주는가</h2>
 * <ul>
 *   <li><b>예산을 넘긴 작업도 자리를 쥔다.</b> 마감이 지나면 파이프라인은 기다림을 끊고 폴백으로 응답하지만,
 *       이미 떠난 LLM 호출은 취소되지 않고 끝까지 돌며 슬롯을 쓴다(STEP-3). 요청 종료 시점에 자리를 돌려주면
 *       그 작업이 아직 슬롯을 쥔 채 새 요청이 들어와, 게이트의 총량이 실제 LLM 작업량보다 작게 보인다.
 *       비교 실험 C(#201)에서 거절된 요청의 남은 작업이 슬롯을 계속 쓰던 것과 같은 함정이다</li>
 *   <li><b>먼저 끝난 작업의 자리는 일찍 돌아온다.</b> Planner 는 Curator 보다 먼저 끝나므로 그 자리 하나는
 *       Curator 가 도는 동안 다른 요청이 쓸 수 있다</li>
 * </ul>
 *
 * <h2>올리지 않은 작업의 몫</h2>
 * Planner 가 실패해 기본 플랜으로 가거나, 예산이 진입 전에 끝나 Curator 를 건너뛰면 예약한 자리 일부가 작업에
 * 묶이지 않는다. 그 몫은 {@link #close()}가 요청 종료 시점에 한꺼번에 돌려준다. 반대로 예약보다 많은 작업이
 * 올라오면(Planner 가 일수를 다르게 돌려주는 경우 등) 넘친 작업은 자리 없이 실행된다 — 그 작업을 막으면 이미
 * 받아들인 요청의 품질이 깨지고, 실행기 불변식은 총량과 스레드 수의 여유로 지킨다.
 */
public final class LlmWorkLease {

    private static final LlmWorkLease UNTRACKED = new LlmWorkLease(null, 0);

    /** 자리를 돌려줄 게이트. {@code null}이면 추적하지 않는다. */
    private final Semaphore gate;

    /** 아직 어떤 작업에도 묶이지 않은 자리 수. */
    private final AtomicInteger unclaimed;

    LlmWorkLease(Semaphore gate, int units) {
        this.gate = gate;
        this.unclaimed = new AtomicInteger(units);
    }

    /**
     * 자리를 추적하지 않는 임대. <b>에이전트·파이프라인을 입장 제한 없이 단독으로 부르는 테스트·벤치마크
     * 경로용</b>이고, 운영 경로({@code MyCourseServiceImpl})는 {@link AiCourseAdmission}이 만든 임대를 쓴다.
     */
    public static LlmWorkLease untracked() {
        return UNTRACKED;
    }

    /**
     * 실행기에 올린 작업 하나에 자리 하나를 묶는다. 작업이 성공하든 실패하든 끝나는 순간 자리가 돌아간다.
     *
     * <p>파생 future 가 아니라 <b>실행기에 올린 그 future</b>를 넘겨야 한다 — 결과를 가공하는
     * {@code handle}/{@code thenApply} 뒤의 future 는 원래 작업과 완료 시점이 같지만, 호출자가 기다림을 끊고
     * 버린 뒤에도 원래 작업이 도는 동안 자리를 쥐어야 한다는 의도가 코드에 드러나도록 원본에 건다.
     *
     * @return 넘겨받은 {@code task} 그대로
     */
    public <T> CompletableFuture<T> track(CompletableFuture<T> task) {
        if (gate != null && claimOne()) {
            task.whenComplete((result, error) -> gate.release());
        }
        return task;
    }

    /** 작업에 묶이지 않은 자리를 모두 돌려준다. 요청 종료 시 {@link AiCourseAdmission}이 한 번 부른다. */
    void close() {
        if (gate == null) {
            return;
        }
        int rest = unclaimed.getAndSet(0);
        if (rest > 0) {
            gate.release(rest);
        }
    }

    private boolean claimOne() {
        while (true) {
            int remaining = unclaimed.get();
            if (remaining == 0) {
                return false;
            }
            if (unclaimed.compareAndSet(remaining, remaining - 1)) {
                return true;
            }
        }
    }
}
