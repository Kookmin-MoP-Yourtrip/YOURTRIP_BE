package backend.yourtrip.global.cloudfront.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import backend.yourtrip.global.cloudfront.config.CloudFrontExecutorConfig;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.CloudFrontErrorCode;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CloudFrontSigningGate의 거부·불변식 경로를 결정론적으로 검증한다(sleep 기반 타이밍 추정
 * 대신 CountDownLatch로 특정 순간의 permit·큐 상태를 강제로 만든다). abortpolicy-gate-verification.md가
 * 지적한 두 위험 — (1) AbortPolicy 단독으로는 부하 차단이 태스크 단위라 부분 응답이 상시화되는
 * 문제를 게이트가 요청 단위로 해소하는지, (2) acquire 실패 경로에서 release가 잘못 호출돼
 * permit이 조용히 인플레이션되는 버그가 없는지 — 를 이 클래스가 직접 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CloudFrontSigningGateTest {

    @Mock
    private CloudFrontService cloudFrontService;

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("permit이 남아있으면 요청한 이미지 전체가 서명되어 반환된다")
    void signAll_WithAvailablePermit_ReturnsAllSignedUrls() {
        executor = newExecutor(4, 100);
        CloudFrontSigningGate gate = newGate(executor, 4, 1000, 50, 2000);
        given(cloudFrontService.getSignedUrl(anyString()))
            .willAnswer(inv -> "signed-" + inv.getArgument(0, String.class));

        Map<String, String> result = gate.signAll(List.of("a", "b", "c"));

        assertThat(result).containsOnly(
            Map.entry("a", "signed-a"), Map.entry("b", "signed-b"), Map.entry("c", "signed-c"));
    }

    @Test
    @DisplayName("요청당 이미지 수가 상한을 넘으면 permit 획득 전에 400으로 거부된다")
    void signAll_ExceedsMaxKeysPerRequest_ThrowsBadRequest() {
        executor = newExecutor(2, 100);
        CloudFrontSigningGate gate = newGate(executor, 10, 1000, /* maxKeysPerRequest */ 2, 2000);

        assertThatThrownBy(() -> gate.signAll(List.of("a", "b", "c")))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", MyCourseErrorCode.TOO_MANY_IMAGES);
    }

    @Test
    @DisplayName("permit이 모두 점유된 동안에는 대기시간 이후 503으로 거부하고 태스크를 제출조차 하지 않으며, "
        + "점유가 풀리면 permit 수는 초기값으로 돌아온다")
    void signAll_WhenNoPermitAvailable_RejectsWithoutSubmittingTasks() throws Exception {
        executor = newExecutor(2, 100);
        CloudFrontSigningGate gate = newGate(executor, /* permits */ 1, /* acquireTimeoutMs */ 50, 50, 2000);
        Semaphore admission = admissionOf(gate);
        int initialPermits = admission.availablePermits();

        CountDownLatch permitHeld = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        given(cloudFrontService.getSignedUrl("slow")).willAnswer(inv -> {
            // 이 시점에 도달했다는 건 permit을 이미 획득해 태스크가 실행 중이라는 뜻이다.
            permitHeld.countDown();
            releaseSlow.await(2, TimeUnit.SECONDS);
            return "signed-slow";
        });

        Thread occupier = new Thread(() -> gate.signAll(List.of("slow")));
        occupier.start();
        assertThat(permitHeld.await(1, TimeUnit.SECONDS)).isTrue();

        // when & then: 유일한 permit이 점유돼 있으므로 두 번째 호출은 acquireTimeoutMs(50ms) 뒤
        // 즉시 503으로 거부된다 — 서명 태스크는 아예 제출되지 않는다(getSignedUrl("k1") 미호출로 확인).
        assertThatThrownBy(() -> gate.signAll(List.of("k1")))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", CloudFrontErrorCode.SIGNING_OVERLOADED);
        verify(cloudFrontService, never()).getSignedUrl("k1");

        releaseSlow.countDown();
        occupier.join(2000);

        // ★ permit 인플레이션 버그(acquire 실패 경로에서도 release가 호출되는 실수)를 잡는
        // 유일한 방어선. 여기서 어긋나면 게이트가 조용히 무력화된다.
        assertThat(admission.availablePermits()).isEqualTo(initialPermits);
    }

    @Test
    @DisplayName("성공 경로와 태스크 예외(fail-open) 경로 모두 permit을 정상적으로 반납한다")
    void signAll_ReleasesPermit_OnSuccessAndOnTaskException() {
        executor = newExecutor(4, 100);
        CloudFrontSigningGate gate = newGate(executor, /* permits */ 2, 1000, 50, 2000);
        Semaphore admission = admissionOf(gate);
        int initialPermits = admission.availablePermits();

        given(cloudFrontService.getSignedUrl("ok")).willReturn("signed-ok");
        gate.signAll(List.of("ok"));
        assertThat(admission.availablePermits()).isEqualTo(initialPermits);

        given(cloudFrontService.getSignedUrl("bad")).willThrow(new RuntimeException("서명 실패"));
        Map<String, String> result = gate.signAll(List.of("bad"));
        assertThat(result).isEmpty(); // fail-open — 실패한 key는 조용히 누락된다
        assertThat(admission.availablePermits()).isEqualTo(initialPermits);
    }

    @Test
    @DisplayName("executor 큐+풀이 가득 차 태스크가 거부돼도 예외가 새지 않고 부분 응답으로 열화된다")
    void signAll_WhenExecutorQueueFull_DegradesToPartialResponseWithoutThrowing() {
        // 스레드 1개, 큐 1개 — 3번째로 제출되는 태스크는 executor 레벨에서 AbortPolicy로 거부된다.
        // ThreadPoolExecutor.execute()는 새 워커 등록을 반환 전에 동기적으로 끝내므로, 한 스레드에서
        // 순차 제출하는 이 테스트는 "blocker가 워커를 점유 → queued가 큐를 채움 → overflow가 거부됨"
        // 순서가 타이밍에 의존하지 않고 항상 보장된다.
        executor = newExecutor(1, 1);
        CloudFrontSigningGate gate = newGate(executor, /* permits */ 10, 1000, 50, 2000);

        given(cloudFrontService.getSignedUrl(anyString())).willAnswer(inv -> {
            String key = inv.getArgument(0, String.class);
            if (key.equals("blocker")) {
                // 워커를 잠깐 붙잡아 뒤이은 제출이 큐/거부 경로를 타게 만드는 용도의 작업 시간
                // 시뮬레이션일 뿐, 동기화를 위한 대기가 아니다.
                TimeUnit.MILLISECONDS.sleep(150);
            }
            return "signed-" + key;
        });

        Map<String, String> result = gate.signAll(List.of("blocker", "queued", "overflow"));

        assertThat(result).containsOnly(
            Map.entry("blocker", "signed-blocker"), Map.entry("queued", "signed-queued"));
        assertThat(result).doesNotContainKey("overflow");
    }

    private ThreadPoolTaskExecutor newExecutor(int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor newExecutor = CloudFrontExecutorConfig.buildSigningExecutor(
            poolSize, queueCapacity, new ThreadPoolExecutor.AbortPolicy());
        newExecutor.initialize();
        return newExecutor;
    }

    private CloudFrontSigningGate newGate(ThreadPoolTaskExecutor executor, int permits,
        long acquireTimeoutMs, int maxKeysPerRequest, long deadlineMs) {
        return new CloudFrontSigningGate(cloudFrontService, executor, permits, acquireTimeoutMs,
            maxKeysPerRequest, deadlineMs, new SimpleMeterRegistry());
    }

    private Semaphore admissionOf(CloudFrontSigningGate gate) {
        return (Semaphore) ReflectionTestUtils.getField(gate, "admission");
    }
}
