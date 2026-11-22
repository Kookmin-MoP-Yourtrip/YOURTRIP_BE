package backend.yourtrip.global.health;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 진행 여부 판정({@link RequestProgressHealthIndicator})의 두 값 (#216).
 *
 * @param stallThreshold 8080에서 마지막으로 워커가 요청을 마친 뒤 이 시간이 지나면 판정 근거가 없다고 보고
 *                       8080을 직접 확인한다. <b>이 값을 넘었다고 DOWN이 되지는 않는다</b> — 직접 확인이 실패해야
 *                       DOWN이다. 그래서 가장 긴 정상 요청보다 길 필요가 없고 "얼마나 자주 직접 확인할지"만 정한다
 * @param probeTimeout   직접 확인의 연결·응답 상한. ALB 헬스체크 타임아웃(5초)보다 충분히 짧아야, 확인이 실패했을 때
 *                       ALB가 기다리다 포기하기 전에 503을 돌려줄 수 있다
 */
@Validated
@ConfigurationProperties(prefix = "health.progress")
public record HealthProgressProperties(

    @NotNull
    Duration stallThreshold,

    @NotNull
    Duration probeTimeout
) {}
