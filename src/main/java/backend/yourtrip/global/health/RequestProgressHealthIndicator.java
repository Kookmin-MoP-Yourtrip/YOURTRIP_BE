package backend.yourtrip.global.health;

import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * liveness 그룹의 {@code requestProgress} — 과부하와 고장을 "완료가 이어지는가"로 구분한다 (#216).
 *
 * <h2>왜 필요한가</h2>
 * 바깥에서 보면 과부하와 고장은 똑같이 "응답이 늦다"다. ALB가 8080에 헬스체크를 보내면 <b>그 요청 하나</b>가 줄을
 * 통과했는지에 판정이 걸려, 일하고 있는 서버도 줄이 길면 unhealthy가 된다(빠른 API 고부하 측정 네 회차 모두). 안에서
 * 보면 둘은 다르다 — 과부하는 느리지만 끝나는 요청이 계속 있고, 고장은 끝나는 요청이 없다. 이 판정은 <b>누구의 요청이든</b>
 * 끝났는지를 본다.
 *
 * <h2>규칙</h2>
 * <ol>
 *   <li>마지막 완료가 {@code stallThreshold} 이내면 UP. 8080에 요청을 보내지 않는다 — 과부하에서 줄에 설 일이 없다.</li>
 *   <li>아니면 8080을 직접 확인한다. 완료 기록이 끊긴 이유가 "요청이 없어서"인지 "처리를 못 해서"인지는 기록만으로
 *       알 수 없기 때문이다. 요청이 없을 때는 줄도 없으므로 직접 확인이 정확하다.</li>
 *   <li>확인이 실패해도 <b>그사이 완료 기록이 갱신됐으면</b> UP. 한가하다고 판단한 직후 요청이 몰려 확인이 줄 뒤에 밀린
 *       경우다 — 다른 요청이 끝났다는 것 자체가 일이 진행된다는 증거다.</li>
 * </ol>
 *
 * <h2>처리 중 요청 수로 분기하지 않는 이유</h2>
 * "처리 중이면 기록만 본다"로 하면, 다른 트래픽 없이 긴 요청 하나(느린 회선의 대용량 업로드 등)만 처리 중인 한가한 서버가
 * DOWN으로 교체된다. 이 규칙에서는 그 경우 나머지 워커가 직접 확인을 처리하므로 UP이고, 워커가 전부 묶였을 때만 DOWN이다.
 *
 * <h2>이 판정이 잡지 않는 것</h2>
 * JVM 전체 정지(GC thrashing, {@code kill -STOP})는 관리 포트도 응답하지 못해 ALB 타임아웃이 잡는다. OOM은
 * {@code -XX:+ExitOnOutOfMemoryError}로 프로세스가 종료돼 systemd가 다시 띄운다. 의존성(DB·Redis) 장애는 일부러 보지 않는다.
 *
 * <p>근거·대안 비교·판정 기준: {@code docs/tasks/health-check-progress/README.md}
 */
public class RequestProgressHealthIndicator implements HealthIndicator {

    private final ProgressTracker tracker;
    private final MainPortProbe probe;
    private final Duration stallThreshold;

    public RequestProgressHealthIndicator(ProgressTracker tracker, MainPortProbe probe,
        Duration stallThreshold) {
        this.tracker = tracker;
        this.probe = probe;
        this.stallThreshold = stallThreshold;
    }

    @Override
    public Health health() {
        // 기준값을 한 번만 읽고, 최근 완료 검사와 "직접 확인 중에 완료가 있었는가" 비교에 같은 값을 쓴다. 검사 뒤에 다시
        // 읽으면 그 사이의 완료가 기준값에 흡수돼, 방금 완료가 있었는데도 직접 확인이 실패하면 DOWN이 된다.
        long before = tracker.lastCompletedAt();
        Duration since = tracker.since(before);
        if (since.compareTo(stallThreshold) <= 0) {
            return Health.up().withDetail("check", "recent-completion")
                .withDetail("msSinceLastCompletion", since.toMillis()).build();
        }

        if (probe.ping()) {
            return Health.up().withDetail("check", "probe-ok")
                .withDetail("msSinceLastCompletion", since.toMillis()).build();
        }
        if (tracker.lastCompletedAt() != before) {
            return Health.up().withDetail("check", "completed-during-probe")
                .withDetail("msSinceLastCompletion", since.toMillis()).build();
        }
        return Health.down().withDetail("check", "probe-failed")
            .withDetail("msSinceLastCompletion", since.toMillis()).build();
    }
}
