package backend.yourtrip.global.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 코스 삭제 시 자식 이미지가 많아도 API 응답 시간이 늘어나지 않도록, S3 정리처럼 외부 I/O가
 * 섞인 후처리를 요청 스레드에서 분리하기 위한 전용 스레드풀. DB 삭제(cascade)는 자식 row 수가
 * 적어(코스 1건당 최대 수백 건 수준) 이 규모에서는 동기로 유지해도 무방하지만, 이미지마다
 * 개별 네트워크 호출이 필요한 S3 삭제는 이미지 개수에 비례해 응답이 늘어질 수 있어 분리한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "courseImageCleanupExecutor")
    public ThreadPoolTaskExecutor courseImageCleanupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // S3 삭제는 네트워크 I/O 대기가 대부분이라 CPU 코어 수보다 넉넉하게 잡아도 이득이 있다.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        // 큐가 가득 차면 호출 스레드가 직접 실행 — 응답 자체를 막지는 않되(이미 커밋 이후 시점),
        // 무한정 쌓이는 것보다 자연스럽게 degrade하는 편이 안전하다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("course-image-cleanup-");
        executor.initialize();
        return executor;
    }
}
