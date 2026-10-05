package backend.yourtrip.global.naver;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 네이버 지역검색 호출을 <b>서버 단위로 고르게</b> 흘려보내는 속도 제한기 (이슈 #185).
 *
 * <h2>왜 필요한가</h2>
 * NAVER API HUB 는 API 키당 초당 50건을 넘으면 429({@code errorCode 420})를 준다. 평균 처리량은
 * 문제가 아니다 — 동시 8명이 계속 요청해도 초당 약 8건이다. 문제는 <b>순간 몰림</b>이다. 요청 하나가
 * 약 31건을 1~2초 안에 던지고, 여러 요청이 같은 순간 후보 공급에 들어오면 그 합이 한도를 넘는다.
 * 지금까지는 좁은 LLM 슬롯이 요청들을 우연히 시간차로 흩어 이것을 가려 왔다 — 슬롯을 4 → 5로 넓히자
 * 동시 5명 네이버 실패가 1.0% → 14.4%로 뛰고 식사·카페 슬롯이 비었다(STEP-4-4). <b>외부 한도는
 * 그 한도를 가진 클라이언트가 스스로 지킨다.</b>
 *
 * <h2>알고리즘 — GCRA(토큰 버킷과 동치)</h2>
 * "다음 호출이 이론상 나가도 되는 시각"({@code theoreticalArrival}) 하나만 원자적으로 들고 있다.
 * 호출마다 그 시각을 {@code interval}만큼 미루고, 지금보다 앞서 있는 만큼만 기다린다. 쉬고 있던
 * 동안은 최대 {@code burst}건을 즉시 내보낸다 — 단일 요청이 버스트 안에서 끝나야 평소 지연이
 * 늘지 않기 때문이다. 임의의 1초 창에 나가는 호출은 최대 {@code burst + permitsPerSecond}건이다.
 *
 * <p>잠금 없이 CAS 로 예약한다. 예약과 대기를 분리해, 여러 스레드가 동시에 와도 <b>각자 다른 시각을
 * 예약하고 그만큼만 잔다.</b>
 *
 * <h2>대기 상한</h2>
 * 기다려야 할 시간이 {@code maxWait}를 넘으면 <b>예약하지 않고</b> 거절한다. 이 호출은 grounding 풀
 * 스레드에서 돌고, 그 풀은 카카오 검증·TourAPI 와 공유된다 — 무한정 자면 다른 작업이 줄을 선다.
 * 거절은 429 를 맞은 것과 같은 결과(그 질의의 후보가 빠진다)라 호출부의 fail-open 경로를 그대로 탄다.
 *
 * <p><b>여러 서버의 합은 보장하지 않는다.</b> 운영 2대가 키 하나를 공유하므로 엄밀하게는 분산 제한기가
 * 필요하지만, 두 서버의 버스트가 같은 1초에 겹치는 일은 드물고 그 대가(Redis 왕복)가 호출 하나보다
 * 비싸다. 서버당 상한을 한도의 절반 근처로 두는 것으로 대신한다.
 */
public final class NaverRateLimiter {

    /** 테스트에서 실제 대기를 건너뛰기 위한 이음매({@code LlmRetryExecutor.Sleeper}와 같은 목적). */
    @FunctionalInterface
    public interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    /** 제한하지 않는 인스턴스. Spring 컨텍스트 없이 클라이언트를 조립하는 프로브·스텁 테스트용이다. */
    private static final NaverRateLimiter UNLIMITED = new NaverRateLimiter();

    private final long intervalNanos;
    private final long burstToleranceNanos;
    private final long maxWaitNanos;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final boolean unlimited;
    private final AtomicLong theoreticalArrival;

    private NaverRateLimiter() {
        this.intervalNanos = 0;
        this.burstToleranceNanos = 0;
        this.maxWaitNanos = 0;
        this.clock = System::nanoTime;
        this.sleeper = TimeUnit.NANOSECONDS::sleep;
        this.unlimited = true;
        this.theoreticalArrival = new AtomicLong();
    }

    /**
     * @param permitsPerSecond 지속 속도. 운영 2대가 키 하나(초당 50)를 나눠 쓰므로 그 절반 근처로 둔다
     * @param burst            쉬던 뒤 즉시 내보낼 수 있는 건수(1 이상)
     * @param maxWaitMillis    이보다 오래 기다려야 하면 거절한다
     */
    public NaverRateLimiter(double permitsPerSecond, int burst, long maxWaitMillis) {
        this(permitsPerSecond, burst, maxWaitMillis, System::nanoTime,
            TimeUnit.NANOSECONDS::sleep);
    }

    NaverRateLimiter(double permitsPerSecond, int burst, long maxWaitMillis, LongSupplier clock,
        Sleeper sleeper) {
        if (permitsPerSecond <= 0 || burst < 1 || maxWaitMillis < 0) {
            throw new IllegalArgumentException(
                "permitsPerSecond > 0, burst >= 1, maxWaitMillis >= 0 이어야 한다");
        }
        this.intervalNanos = (long) (TimeUnit.SECONDS.toNanos(1) / permitsPerSecond);
        // 버스트 b 건이 즉시 나가려면 이론 시각이 지금보다 (b-1)·interval 앞서 있어도 통과시켜야 한다.
        this.burstToleranceNanos = (burst - 1) * intervalNanos;
        this.maxWaitNanos = TimeUnit.MILLISECONDS.toNanos(maxWaitMillis);
        this.clock = clock;
        this.sleeper = sleeper;
        this.unlimited = false;
        this.theoreticalArrival = new AtomicLong(clock.getAsLong());
    }

    public static NaverRateLimiter unlimited() {
        return UNLIMITED;
    }

    /**
     * 호출 한 건의 차례를 얻는다. 필요하면 그 차례까지 잔다.
     *
     * @return 기다린 시간(ns). <b>거절이면 -1</b>이고 이때는 아무것도 예약하지 않는다
     * @throws InterruptedException 대기 중 인터럽트(요청 마감 등). 예약은 이미 소비된 상태다
     */
    public long acquire() throws InterruptedException {
        if (unlimited) {
            return 0;
        }
        while (true) {
            long now = clock.getAsLong();
            long current = theoreticalArrival.get();
            long arrival = Math.max(current, now);
            long waitNanos = Math.max(0, arrival - burstToleranceNanos - now);
            if (waitNanos > maxWaitNanos) {
                return -1;
            }
            if (theoreticalArrival.compareAndSet(current, arrival + intervalNanos)) {
                if (waitNanos > 0) {
                    sleeper.sleepNanos(waitNanos);
                }
                return waitNanos;
            }
        }
    }
}
