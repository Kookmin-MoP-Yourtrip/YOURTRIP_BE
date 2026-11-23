package backend.yourtrip.global.mail;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 인증 메일 재시도 간격 (이슈 #221, 설계 README 3-4절).
 *
 * <p>개선 전에는 실패하면 <b>대기 없이</b> 곧바로 다시 보냈다. SMTP 순간 장애라면 네 번 모두 같은 장애에 부딪힌다.
 * 1초 → 2초 → 4초로 벌려, 몇 초 안에 풀리는 장애를 흡수한다. 재시도 횟수는 간격의 개수(기본 3)다.
 *
 * <p><b>대기 중에도 메일 스레드를 쥔다.</b> 실패마다 스케줄러에 다음 시도를 예약하고 스레드를 반납하는 방법도 있지만,
 * 그러면 예약된 재시도가 메일 풀의 큐 상한(50)을 우회해 장애 시 작업이 상한 없이 쌓인다. 쥐는 스레드는 메일 전용이라
 * 다른 API에 피해가 없고, 장애 때 스레드를 실제로 오래 쥐는 것은 이 간격(합 7초)보다 SMTP 타임아웃 대기(시도당 최대 10초)다.
 *
 * <p>간격은 {@code mail.retry.backoff-ms}로 바꿀 수 있다 — 운영값을 바꾸려는 것이 아니라, 컨텍스트를 띄우는 테스트가
 * 재시도마다 7초씩 기다리지 않게 하려는 손잡이다.
 */
@Component
public class MailRetryBackoff {

    private final List<Duration> delays;

    public MailRetryBackoff(@Value("${mail.retry.backoff-ms:1000,2000,4000}") List<Long> delaysMs) {
        this.delays = delaysMs.stream().map(Duration::ofMillis).toList();
    }

    /** 첫 시도 뒤에 허용하는 재시도 횟수. */
    public int maxRetries() {
        return delays.size();
    }

    /** {@code retry}번째 재시도(1부터) 전에 기다린다. 종료 중 인터럽트되면 예외로 알린다. */
    public void beforeRetry(int retry) throws InterruptedException {
        sleep(delays.get(retry - 1));
    }

    // 테스트가 실제로 기다리지 않고 간격만 기록하도록 덮어쓴다.
    protected void sleep(Duration delay) throws InterruptedException {
        Thread.sleep(delay.toMillis());
    }
}
