package backend.yourtrip.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.mycourse.dto.request.AICourseCreateRequest;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 입장 제한 × 최대 여행 일수가 {@code aiAgentExecutor} 스레드 안에 들어가는지 고정한다 (#178).
 *
 * <p><b>왜 이 곱인가.</b> 요청 하나가 실행기에 동시에 올리는 작업은 Curator 의 day 수가 최대다
 * (Planner 는 Curator 보다 먼저 끝난다). 입장 제한을 통과한 요청이 모두 최대 일수여도 작업이
 * 스레드 수를 넘지 않으면 큐가 쌓이지 않고, 큐가 차야 발동하는 {@code CallerRunsPolicy}(요청
 * 스레드가 LLM 을 직접 실행 — 서비스 전체 장애의 원인)도 받아들인 요청만으로는 발동할 수 없다.
 *
 * <p>셋 중 하나만 바꾸면(슬롯을 줄이거나, 입장 상한을 올리거나, 일수 상한을 늘리면) 이 테스트가
 * 깨져 나머지를 함께 보라고 알린다. 값은 운영과 같은 {@code application.yml}에서 읽는다 — 프로필
 * 파일들은 이 두 키를 덮어쓰지 않는다.
 *
 * <p>예산이 지나 포기된 요청의 작업(좀비)은 입장 자리를 돌려준 뒤에도 실행기에 남을 수 있어 이
 * 불변식 밖이다. 그 몫은 큐(50)가 받는다.
 */
class AiExecutorCapacityInvariantTest {

    @Test
    @DisplayName("입장 상한 × 최대 여행 일수는 aiAgentExecutor 스레드 수를 넘지 않는다")
    void admittedRequestsFitInAgentExecutorThreads() throws IOException {
        Binder binder = applicationYmlBinder();
        int maxConcurrentRequests = binder.bind("ai.course.admission.max-concurrent-requests",
            Integer.class).get();
        int maxConcurrentCalls = binder.bind("llm.max-concurrent-calls", Integer.class).get();
        int executorThreads = AsyncConfig.agentExecutorFor(maxConcurrentCalls).getMaxPoolSize();

        int worstCaseTasks = maxConcurrentRequests * AICourseCreateRequest.MAX_TRIP_DAYS;

        assertThat(worstCaseTasks)
            .as("입장 %d × 최대 %d일 = Curator 작업 %d개가 실행기 스레드 %d개(슬롯 %d × %d)를 넘는다",
                maxConcurrentRequests, AICourseCreateRequest.MAX_TRIP_DAYS, worstCaseTasks,
                executorThreads, maxConcurrentCalls, AsyncConfig.THREADS_PER_PERMIT)
            .isLessThanOrEqualTo(executorThreads);
    }

    private static Binder applicationYmlBinder() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        // 시스템 환경변수·프로퍼티는 빼 로컬 셸 값(LLM_MAXCONCURRENTCALLS 등)에 흔들리지 않게 한다
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.forEach(environment.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(environment));
    }
}
