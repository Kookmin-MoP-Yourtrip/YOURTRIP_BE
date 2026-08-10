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
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // CPU-bound 작업이라 코어 수 이상으로 늘려도 이득이 없다 (특히 t3.micro 같은 환경에서는 vCPU 수만큼으로 충분).
        int poolSize = signingPoolSize > 0 ? signingPoolSize : Runtime.getRuntime().availableProcessors();

        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        // 큐 크기 (bounded). 실측 후 조정 여지가 있음.
        executor.setQueueCapacity(100);

        // TASK-PRESIGN-CALLERRUNS-HYPOTHESIS: CallerRunsPolicy가 실제로 몇 번 발동했는지
        // 표준 구현은 노출하지 않으므로, 위임 전에 카운터를 증가시키는 래퍼를 둔다.
        // Prometheus 노출명: cloudfront_signing_caller_runs_total
        Counter callerRunsCounter = Counter.builder("cloudfront.signing.caller.runs")
            .description("cloudFrontSigningExecutor 큐 오버플로우로 CallerRunsPolicy가 발동해 "
                + "제출 스레드(Tomcat 요청 스레드일 가능성)가 서명을 직접 실행한 횟수")
            .register(meterRegistry);

        RejectedExecutionHandler delegate = new ThreadPoolExecutor.CallerRunsPolicy();
        executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) -> {
            callerRunsCounter.increment();
            delegate.rejectedExecution(runnable, threadPoolExecutor);
        });
        executor.setThreadNamePrefix("cloudfront-signing-");

        executor.initialize();

        // executor_active, executor_queued, executor_pool_size 등을
        // name="cloudFrontSigningExecutor" 태그로 /actuator/prometheus에 노출.
        // monitoredSigningExecutor 필드에 먼저 대입해 강한 참조를 확보한 뒤 등록한다.
        this.monitoredSigningExecutor = executor.getThreadPoolExecutor();
        ExecutorServiceMetrics.monitor(meterRegistry, this.monitoredSigningExecutor, "cloudFrontSigningExecutor");

        return executor;
    }
}
