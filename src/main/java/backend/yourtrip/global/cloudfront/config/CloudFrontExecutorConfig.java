package backend.yourtrip.global.cloudfront.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class CloudFrontExecutorConfig {

    @Value("${cloudfront.signing-pool-size:0}")
    private int signingPoolSize;

    @Bean(name = "cloudFrontSigningExecutor")
    public ThreadPoolTaskExecutor cloudFrontSigningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // CPU-bound 작업이라 코어 수 이상으로 늘려도 이득이 없다 (특히 t3.micro 같은 환경에서는 vCPU 수만큼으로 충분).
        int poolSize = signingPoolSize > 0 ? signingPoolSize : Runtime.getRuntime().availableProcessors();
        
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        // 큐 크기 (bounded). 실측 후 조정 여지가 있음.
        executor.setQueueCapacity(100);
        // 큐가 가득 차면 제출 스레드가 직접 실행해 자연스럽게 degrade.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("cloudfront-signing-");
        
        executor.initialize();
        return executor;
    }
}
