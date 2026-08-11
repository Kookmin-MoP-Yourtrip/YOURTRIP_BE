package backend.yourtrip.global.cloudfront.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class CloudFrontExecutorConfig {

    @Value("${cloudfront.signing-pool-size:0}")
    private int signingPoolSize;

    // 산정 근거와 "코스당 서명 1회 전환 이후 과잉이 됐지만 통제된 비교를 위해 유지한다"는
    // 판단은 application.yml의 cloudfront.signing 절에 모아뒀다(주석 3중 중복을 없앴다).
    @Value("${cloudfront.signing.queue-capacity:640}")
    private int queueCapacity;

    private final MeterRegistry meterRegistry;

    // ExecutorServiceMetrics의 게이지는 대상 executor를 WeakReference로만 들고 있어서,
    // 지역변수로만 두면 GC 이후 /actuator/prometheus에 NaN이 찍히는 걸 실측으로 확인했다
    // (Micrometer의 알려진 함정). 싱글턴 빈 필드에 강한 참조로 잡아둬 생명주기를 앱과 맞춘다.
    private ThreadPoolExecutor monitoredSigningExecutor;

    public CloudFrontExecutorConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Bean(name = "cloudFrontSigningExecutor")
    public ThreadPoolTaskExecutor cloudFrontSigningExecutor() {
        // CPU-bound 작업이라 코어 수 이상으로 늘려도 이득이 없다 (특히 t3.micro/small 같은 환경에서는 vCPU 수만큼으로 충분).
        int poolSize = signingPoolSize > 0 ? signingPoolSize : Runtime.getRuntime().availableProcessors();

        // callerruns-verification.md: CallerRunsPolicy는 큐+풀이 가득 차면 제출 스레드
        // (Tomcat 요청 스레드)가 서명을 직접 실행하게 만들어, VU200 부하에서 초당 약 1,450회
        // 발동하며 CPU 격리를 정확히 반대 방향으로 무너뜨렸다(실측: CPU 98.28%, http-nio 스레드의
        // 80.69%가 crypto 프레임을 물었음). AbortPolicy로 전환해 제출 스레드가 서명을 대신
        // 떠맡지 않고 즉시 예외를 받게 한다 — 이 executor 앞에는 CloudFrontSigningGate의
        // 세마포어가 요청 단위로 부하를 먼저 차단하므로, 여기서의 거부는 사실상 발생하지 않는
        // 안전망이다(발생하면 게이트 사이징이 잘못됐다는 신호).
        Counter rejectedCounter = Counter.builder("cloudfront.signing.rejected")
            .description("cloudFrontSigningExecutor 큐+풀 오버플로우로 AbortPolicy가 발동해 "
                + "태스크가 거부된 횟수. CloudFrontSigningGate가 제대로 사이징됐다면 0에 가까워야 한다.")
            .register(meterRegistry);

        RejectedExecutionHandler delegate = new ThreadPoolExecutor.AbortPolicy();
        ThreadPoolTaskExecutor executor = buildSigningExecutor(poolSize, queueCapacity,
            (runnable, threadPoolExecutor) -> {
                rejectedCounter.increment();
                delegate.rejectedExecution(runnable, threadPoolExecutor);
            });

        executor.initialize();

        // executor_active, executor_queued, executor_pool_size 등을
        // name="cloudFrontSigningExecutor" 태그로 /actuator/prometheus에 노출.
        // monitoredSigningExecutor 필드에 먼저 대입해 강한 참조를 확보한 뒤 등록한다.
        this.monitoredSigningExecutor = executor.getThreadPoolExecutor();
        ExecutorServiceMetrics.monitor(meterRegistry, this.monitoredSigningExecutor, "cloudFrontSigningExecutor");

        return executor;
    }

    /**
     * executor 생성 로직을 정적 팩터리로 분리해, 테스트가 프로덕션과 동일한 풀 설정
     * (셧다운 대기 옵션 포함)으로 executor를 구성하게 한다. 거부 정책만 파라미터로 받아
     * 프로덕션(카운팅 AbortPolicy)과 테스트(순정 정책)가 나머지 설정을 공유한다.
     */
    public static ThreadPoolTaskExecutor buildSigningExecutor(int poolSize, int queueCapacity,
        RejectedExecutionHandler rejectedExecutionHandler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(rejectedExecutionHandler);
        executor.setThreadNamePrefix("cloudfront-signing-");

        // CallerRunsPolicy 시절엔 executor가 shutdown된 뒤 제출된 태스크를 조용히 버렸다
        // (ThreadPoolExecutor.CallerRunsPolicy는 isShutdown()이면 아무 것도 안 하고 리턴) — 그
        // future를 join()하던 Tomcat 스레드가 JVM 종료까지 영구 대기하는 버그가 있었다.
        // AbortPolicy는 이 경로에서 즉시 예외를 던지므로 자동 해소되지만, graceful shutdown
        // 자체도 명시적으로 설정해 배포 중 in-flight 서명 요청이 안전하게 끝나게 한다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);

        return executor;
    }
}
