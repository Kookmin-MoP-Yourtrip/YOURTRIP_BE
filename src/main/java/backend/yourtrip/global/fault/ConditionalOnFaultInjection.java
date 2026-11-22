package backend.yourtrip.global.fault;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 측정 전용 결함 주입 장치는 {@code fault-injection.enabled=true}일 때만 빈이 된다 (#216).
 *
 * <p>기본값은 꺼짐이다({@code matchIfMissing = false}). 꺼져 있으면 8080 경로는 404가 아니라 공통 보안 체인에서
 * 403으로 거부된다 — 인증 면제도 장치와 함께 사라지기 때문이다({@link FaultInjectionSecurityConfig}).
 *
 * <p>설정 클래스 하나에만 조건을 걸지 않고 <b>각 클래스에 이 애노테이션을 단다.</b> {@code @RestController}·
 * {@code @Component}는 컴포넌트 스캔으로 따로 등록되므로, 설정 클래스의 조건이 거기까지 번지지 않는다. 조건 하나를
 * 빠뜨리면 그 장치만 운영에 살아남는다.
 *
 * <p>근거·시나리오: {@code docs/tasks/health-check-progress/fault-scenarios.md}
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "fault-injection.enabled", havingValue = "true")
public @interface ConditionalOnFaultInjection {
}
