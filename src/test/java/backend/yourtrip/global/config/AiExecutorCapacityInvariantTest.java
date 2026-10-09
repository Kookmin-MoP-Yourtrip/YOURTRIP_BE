package backend.yourtrip.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.mycourse.dto.request.AICourseCreateRequest;
import backend.yourtrip.global.ai.AiCourseAdmission;
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
 * AI 입장 총량(LLM 작업 자리)과 다른 설정 사이의 부등식을 고정한다 (#178, #200).
 *
 * <p>입장은 요청마다 자리 {@code 1 + 일수}개를 떼고, 자리는 그 자리를 묶은 LLM 작업이 끝날 때 돌아온다
 * ({@code LlmWorkLease}). 그래서 총량은 "지금 실행기에 올라가 있는 AI 작업 수의 상한"이 된다 — 예산이 지나
 * 버려졌지만 아직 도는 호출까지 포함해서다. 이 값이 맞물려야 하는 곳이 셋이다.
 * <ol>
 *   <li><b>총량 ≤ {@code aiAgentExecutor} 스레드</b> — 큐가 쌓이지 않으니 큐가 차야 발동하는
 *       {@code CallerRunsPolicy}(요청 스레드가 LLM 을 직접 실행 — 서비스 전체 장애의 원인)가 발동할 수 없다.
 *       요청 수로 세던 때(#178)는 "입장 4 × 최대 5일"로 따졌고 좀비 작업은 이 부등식 밖이었다</li>
 *   <li><b>가장 긴 요청의 자리 ≤ 총량</b> — 넘으면 그 요청은 영원히 입장하지 못하는데 응답은 재시도하라는 429 다.
 *       일수 상한 검증이 400 으로 먼저 막아야 한다</li>
 *   <li><b>요청 수 상한 &lt; Tomcat 워커</b> — 작업 자리는 LLM 호출이 끝나면 돌아오지만 요청은 그 뒤 그라운딩·경로
 *       동안에도 워커를 쥔다. 그래서 동시 요청 수는 자리에서 유도되지 않고 요청 전체 수명 동안 쥐는 별도 상한
 *       ({@code max-requests})이 묶는다. AI 요청이 워커를 모두 쥐면 다른 API 가 막힌다(벌크헤드)</li>
 *   <li><b>요청 수 상한 ≥ 총량 ÷ 가장 짧은 요청의 자리</b> — 요청 수 상한이 작업 자리보다 먼저 걸리면 슬롯이 남아도
 *       1일 요청을 돌려보내 #200 의 목적(짧은 요청으로 남는 슬롯을 채운다)이 무너진다</li>
 * </ol>
 *
 * <p>값은 운영과 같은 {@code application.yml}(입장·슬롯)과 {@code application-prod.yml}(Tomcat)에서 읽는다 —
 * 다른 프로필 파일들은 이 키들을 덮어쓰지 않는다. 하나만 바꾸면 이 테스트가 깨져 나머지를 함께 보라고 알린다.
 */
class AiExecutorCapacityInvariantTest {

    @Test
    @DisplayName("입장 총량은 aiAgentExecutor 스레드 수를 넘지 않는다")
    void workUnitsFitInAgentExecutorThreads() throws IOException {
        Binder binder = binderOf("application.yml");
        int maxWorkUnits = maxWorkUnits(binder);
        int maxConcurrentCalls = binder.bind("llm.max-concurrent-calls", Integer.class).get();
        int executorThreads = AsyncConfig.agentExecutorFor(maxConcurrentCalls).getMaxPoolSize();

        assertThat(maxWorkUnits)
            .as("입장 총량 %d 가 실행기 스레드 %d개(슬롯 %d × %d)를 넘는다",
                maxWorkUnits, executorThreads, maxConcurrentCalls, AsyncConfig.THREADS_PER_PERMIT)
            .isLessThanOrEqualTo(executorThreads);
    }

    @Test
    @DisplayName("가장 긴 일정의 요청도 총량 안에 들어간다 — 못 들어가면 영원히 429 다")
    void longestRequestFitsInWorkUnits() throws IOException {
        int maxWorkUnits = maxWorkUnits(binderOf("application.yml"));
        int longest = AiCourseAdmission.unitsFor(AICourseCreateRequest.MAX_TRIP_DAYS);

        assertThat(longest)
            .as("최대 %d일 요청의 자리 %d 가 입장 총량 %d 를 넘는다",
                AICourseCreateRequest.MAX_TRIP_DAYS, longest, maxWorkUnits)
            .isLessThanOrEqualTo(maxWorkUnits);
    }

    @Test
    @DisplayName("요청 수 상한은 운영 Tomcat 워커 수보다 적다 — 후속 단계가 멈춰도 AI 요청이 워커를 다 쥐지 못한다")
    void requestCapBelowTomcatWorkers() throws IOException {
        int maxRequests = maxRequests(binderOf("application.yml"));
        int tomcatWorkers = binderOf("application-prod.yml")
            .bind("server.tomcat.threads.max", Integer.class).get();

        assertThat(maxRequests)
            .as("AI 요청 %d건이 Tomcat 워커 %d개를 다 쥘 수 있다", maxRequests, tomcatWorkers)
            .isLessThan(tomcatWorkers);
    }

    @Test
    @DisplayName("요청 수 상한은 작업 자리보다 먼저 걸리지 않는다 — 1일 요청만 와도 총량을 다 쓸 수 있다")
    void requestCapDoesNotUndercutWorkUnits() throws IOException {
        Binder binder = binderOf("application.yml");
        int maxRequests = maxRequests(binder);
        int byUnits = maxWorkUnits(binder) / AiCourseAdmission.unitsFor(1);

        assertThat(maxRequests)
            .as("요청 수 상한 %d 가 자리로 받을 수 있는 1일 요청 %d건보다 작다", maxRequests, byUnits)
            .isGreaterThanOrEqualTo(byUnits);
    }

    private static int maxRequests(Binder binder) {
        return binder.bind("ai.course.admission.max-requests", Integer.class).get();
    }

    private static int maxWorkUnits(Binder binder) {
        return binder.bind("ai.course.admission.max-work-units", Integer.class).get();
    }

    private static Binder binderOf(String resource) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load(resource, new ClassPathResource(resource));
        StandardEnvironment environment = new StandardEnvironment();
        // 시스템 환경변수·프로퍼티는 빼 로컬 셸 값(LLM_MAXCONCURRENTCALLS 등)에 흔들리지 않게 한다
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.forEach(environment.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(environment));
    }
}
