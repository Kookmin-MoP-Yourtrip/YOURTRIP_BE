package backend.yourtrip.global.mail;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 인증 메일 발송의 커스텀 메트릭 (이슈 #221).
 *
 * <p><b>두 지표를 나눈 이유</b>: {@link #ATTEMPT}는 SMTP 시도 <b>한 번</b>, {@link #MAIL}은 메일
 * <b>한 통</b>의 최종 결말이다. 재시도가 있어서 둘은 1:1이 아니다 — 시도 4번 끝에 성공한 메일은
 * 시도 지표에 실패 3 + 성공 1, 메일 지표에 {@code sent} 1로 남는다. 한쪽만 보면 "시도 실패율이
 * 올랐다"가 사용자에게 닿은 실패인지, 재시도가 흡수한 실패인지 가를 수 없다.
 *
 * <p><b>발송을 비동기로 넘기면(설계 3-1) 실패가 응답으로 드러나지 않으므로, 이 지표가 실패를 보는
 * 유일한 창이 된다.</b> 그래서 비동기화보다 먼저 넣고, 지금 구조에서 기준선을 잰다.
 *
 * <p>태그 조합은 {@code AiCourseMetrics}와 같은 이유로 기동 시점에 0으로 미리 등록한다 —
 * Prometheus에서 "시계열이 없다"와 "값이 0이다"는 구분되지 않는다.
 */
@Component
public class MailMetrics {

    /** SMTP 시도 한 번의 지연과 결말. {@code mailSender.send()} 호출 구간만 잰다. */
    public static final String ATTEMPT = "mail.send.attempt";

    /** 메일 한 통의 최종 결말. */
    public static final String MAIL = "mail.send";

    /**
     * 히스토그램 버킷의 범위. 아래쪽은 가짜 SMTP 서버의 로컬 왕복, 위쪽은 지연 주입 실험의 상한을
     * 덮는다. 현재는 SMTP 타임아웃이 없어 이보다 긴 시도도 가능한데, 그런 값은 {@code +Inf} 버킷으로
     * 모여도 "비정상적으로 길다"는 판단에는 지장이 없다.
     */
    private static final Duration LATENCY_MIN = Duration.ofMillis(10);
    private static final Duration LATENCY_MAX = Duration.ofSeconds(60);

    public enum AttemptOutcome {
        SUCCESS, FAILURE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum MailOutcome {
        /** SMTP 서버가 메일을 접수했다. 도착을 뜻하지는 않는다(스팸·바운스는 알 수 없다). */
        SENT,
        /** 보내지 못하고 끝났다 — 재시도를 모두 썼거나, 재시도 대상이 아닌 실패였다. */
        GAVE_UP,
        /** 메일 전용 풀이 가득 차 받지 않았다(503). SMTP를 한 번도 시도하지 않는다. */
        REJECTED,
        /** 재시도 직전에 인증번호가 재발송으로 바뀌었거나 만료돼 보내지 않고 버렸다. */
        STALE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public MailMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (AttemptOutcome outcome : AttemptOutcome.values()) {
            attemptTimer(outcome);
        }
        for (MailOutcome outcome : MailOutcome.values()) {
            mailCounter(outcome);
        }
    }

    public void attempt(AttemptOutcome outcome, long durationNanos) {
        attemptTimer(outcome).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void mail(MailOutcome outcome) {
        mailCounter(outcome).increment();
    }

    // 같은 이름·태그로 다시 register하면 기존 미터를 돌려주므로, 호출마다 빌더를 써도 시계열은 하나다.
    private Timer attemptTimer(AttemptOutcome outcome) {
        return Timer.builder(ATTEMPT)
            .tag("outcome", outcome.tag())
            .publishPercentileHistogram()
            .minimumExpectedValue(LATENCY_MIN)
            .maximumExpectedValue(LATENCY_MAX)
            .register(registry);
    }

    private Counter mailCounter(MailOutcome outcome) {
        return Counter.builder(MAIL)
            .tag("outcome", outcome.tag())
            .register(registry);
    }
}
