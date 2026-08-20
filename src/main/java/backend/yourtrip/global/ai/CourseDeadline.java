package backend.yourtrip.global.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * AI 코스 생성 요청 하나의 <b>남은 시간 예산</b> (ROADMAP 5-5).
 *
 * <p>파이프라인의 각 스테이지는 이 객체를 인자로 받아 자기 fan-out에
 * {@code allOf(...).get(remainingMs(), MILLISECONDS)} 형태로 상한을 건다. <b>데드라인이 없으면
 * {@code CallerRunsPolicy}는 "느린 성공"이 아니라 "무한정 느린 성공"이 된다</b> — 큐가 차면 요청
 * 스레드가 장소 API I/O를 직접 수행해 사실상 순차 실행으로 퇴화하기 때문이다(설계의 병렬화·지연 예산).
 *
 * <p><b>왜 {@link System#nanoTime()}인가.</b> 벽시계({@code System.currentTimeMillis})는 NTP 보정으로
 * 뒤로 점프할 수 있고, 그러면 이미 지난 예산이 되살아나거나 멀쩡한 요청이 만료로 판정된다.
 * {@code nanoTime}은 단조 증가라 경과 시간 측정에는 이쪽이 옳다. 비교를 뺄셈으로 하는 것도
 * 같은 이유다 — {@code nanoTime}의 절대값은 의미가 없고 오버플로할 수 있어,
 * {@code a - b < 0} 형태로만 비교해야 한다(javadoc이 지정한 관용구).
 *
 * <p><b>만료됐을 때 무엇을 할지는 이 타입이 정하지 않는다.</b> 스테이지마다 다르다 — 후보 공급은
 * 모인 만큼만 반환하고, {@code PlaceUrlEnricher}는 통째로 건너뛰며, 요청 전체를 실패시킬지
 * ({@code AI_COURSE_TIMEOUT})는 7단계 오케스트레이터가 판단한다.
 */
public record CourseDeadline(long deadlineNanos) {

    /**
     * {@link #unbounded()}의 예산. 파이프라인 지연 예산(p95 17~24초)보다 두 자릿수 위라 판정에
     * 영향을 주지 않으면서, {@code Long.MAX_VALUE}처럼 뺄셈에서 오버플로하지도 않는다.
     */
    private static final Duration EFFECTIVELY_UNBOUNDED = Duration.ofHours(1);

    /** 지금부터 {@code budget}만큼의 예산. 파이프라인 진입점에서 요청당 한 번 호출한다. */
    public static CourseDeadline startingNow(Duration budget) {
        Objects.requireNonNull(budget, "budget은 필수다 — 예산 없는 데드라인은 데드라인이 아니다");
        if (budget.isNegative()) {
            throw new IllegalArgumentException("budget은 음수일 수 없다: " + budget);
        }
        return new CourseDeadline(System.nanoTime() + budget.toNanos());
    }

    /**
     * 사실상 무제한. <b>단위 테스트와 프로브처럼 스테이지를 단독으로 부르는 경로용</b>이고,
     * 운영 경로에서는 쓰지 않는다.
     */
    public static CourseDeadline unbounded() {
        return startingNow(EFFECTIVELY_UNBOUNDED);
    }

    /**
     * 남은 밀리초. <b>음수를 돌려주지 않는다</b> — {@code Future.get(-1, MILLISECONDS)}는 음수를
     * 그냥 "이미 만료"로 다루지만, 호출부마다 clamp를 반복하게 두면 언젠가 한 곳이 빠진다.
     *
     * <p>1ms 미만은 0으로 잘린다. 그 시점엔 새 I/O를 시작해봐야 어차피 못 끝내므로 의도된 동작이다.
     */
    public long remainingMs() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos <= 0 ? 0L : TimeUnit.NANOSECONDS.toMillis(remainingNanos);
    }

    /** 예산이 소진됐는가. 새 외부 호출을 시작하기 전에 확인한다. */
    public boolean expired() {
        return deadlineNanos - System.nanoTime() <= 0;
    }
}
