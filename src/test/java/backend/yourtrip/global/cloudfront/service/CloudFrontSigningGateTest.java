package backend.yourtrip.global.cloudfront.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import backend.yourtrip.global.cloudfront.config.CloudFrontExecutorConfig;
import backend.yourtrip.global.cloudfront.service.CloudFrontService.CourseSignature;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.CloudFrontErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * 대신 CountDownLatch로 특정 순간의 permit 상태를 강제로 만든다).
 *
 * <p>1단계(코스당 서명 1회) 이후 검증 대상이 하나 줄고 하나가 뒤집혔다.
 * <ul>
 *   <li><b>줄어든 것</b>: "요청당 이미지 수 상한(maxKeysPerRequest)" — 요청 하나가 이미지
 *       수만큼 태스크를 fan-out하던 구조가 사라져 상한 자체를 없앴다.
 *   <li><b>뒤집힌 것</b>: executor 큐 포화 시 동작. 예전에는 "예외 없이 부분 응답으로 열화"가
 *       기대 동작이었지만, 서명이 1건인 지금 같은 정책은 "이미지 0장짜리 200"을 만든다.
 *       그래서 fail-closed(503)로 바꿨고 이 테스트가 그 판단을 고정한다.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CloudFrontSigningGateTest {

    private static final Long COURSE_ID = 42L;
    private static final CourseSignature SIGNATURE =
        new CourseSignature("d111111abcdef8.cloudfront.net", "Policy=p&Signature=s&Key-Pair-Id=k");

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
    @DisplayName("permit이 남아있으면 코스 스코프 서명을 그대로 반환한다")
    void signCourseScope_WithAvailablePermit_ReturnsSignature() {
        executor = newExecutor(4, 100);
        CloudFrontSigningGate gate = newGate(executor, 4, 1000, 2000);
        given(cloudFrontService.signCourseScope(COURSE_ID)).willReturn(SIGNATURE);

        assertThat(gate.signCourseScope(COURSE_ID)).isEqualTo(SIGNATURE);
    }

    @Test
    @DisplayName("permit이 모두 점유된 동안에는 대기시간 이후 503으로 거부하고 태스크를 제출조차 하지 않으며, "
        + "점유가 풀리면 permit 수는 초기값으로 돌아온다")
    void signCourseScope_WhenNoPermitAvailable_RejectsWithoutSubmittingTask() throws Exception {
        executor = newExecutor(2, 100);
        CloudFrontSigningGate gate = newGate(executor, /* permits */ 1, /* acquireTimeoutMs */ 50, 2000);
        Semaphore admission = admissionOf(gate);
        int initialPermits = admission.availablePermits();

        CountDownLatch permitHeld = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        given(cloudFrontService.signCourseScope(1L)).willAnswer(inv -> {
            // 이 시점에 도달했다는 건 permit을 이미 획득해 태스크가 실행 중이라는 뜻이다.
            permitHeld.countDown();
            releaseSlow.await(2, TimeUnit.SECONDS);
            return SIGNATURE;
        });

        Thread occupier = new Thread(() -> gate.signCourseScope(1L));
        occupier.start();
        assertThat(permitHeld.await(1, TimeUnit.SECONDS)).isTrue();

        // when & then: 유일한 permit이 점유돼 있으므로 두 번째 호출은 acquireTimeoutMs(50ms) 뒤
        // 즉시 503으로 거부된다 — 서명 태스크는 아예 제출되지 않는다(2L 미호출로 확인).
        assertThatThrownBy(() -> gate.signCourseScope(2L))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", CloudFrontErrorCode.SIGNING_OVERLOADED);
        verify(cloudFrontService, never()).signCourseScope(2L);

        releaseSlow.countDown();
        occupier.join(2000);

        // ★ permit 인플레이션 버그(acquire 실패 경로에서도 release가 호출되는 실수)를 잡는
        // 유일한 방어선. 여기서 어긋나면 게이트가 조용히 무력화된다.
        assertThat(admission.availablePermits()).isEqualTo(initialPermits);
    }

    @Test
    @DisplayName("성공 경로와 서명 예외 경로 모두 permit을 정상적으로 반납한다")
    void signCourseScope_ReleasesPermit_OnSuccessAndOnException() {
        executor = newExecutor(4, 100);
        CloudFrontSigningGate gate = newGate(executor, /* permits */ 2, 1000, 2000);
        Semaphore admission = admissionOf(gate);
        int initialPermits = admission.availablePermits();

        given(cloudFrontService.signCourseScope(1L)).willReturn(SIGNATURE);
        gate.signCourseScope(1L);
        assertThat(admission.availablePermits()).isEqualTo(initialPermits);

        given(cloudFrontService.signCourseScope(2L)).willThrow(new RuntimeException("서명 실패"));
        assertThatThrownBy(() -> gate.signCourseScope(2L))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", CloudFrontErrorCode.FAIL_GENERATE_SIGNED_URL);
        assertThat(admission.availablePermits()).isEqualTo(initialPermits);
    }

    @Test
    @DisplayName("executor 큐+풀이 가득 차면 부분 응답으로 열화하지 않고 503으로 거부한다")
    void signCourseScope_WhenExecutorQueueFull_FailsClosedWith503() throws Exception {
        // 스레드 1개, 큐 1개 — 3번째로 제출되는 태스크는 executor 레벨에서 AbortPolicy로 거부된다.
        executor = newExecutor(1, 1);
        CloudFrontSigningGate gate = newGate(executor, /* permits */ 10, 1000, 2000);

        CountDownLatch blockerRunning = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        given(cloudFrontService.signCourseScope(anyLong())).willAnswer(inv -> {
            blockerRunning.countDown();
            releaseBlocker.await(2, TimeUnit.SECONDS);
            return SIGNATURE;
        });

        // 1) 워커 1개를 점유시킨다
        Thread blocker = new Thread(() -> gate.signCourseScope(1L));
        blocker.start();
        assertThat(blockerRunning.await(1, TimeUnit.SECONDS)).isTrue();

        // 2) 큐(1칸)를 채운다
        Thread queued = new Thread(() -> gate.signCourseScope(2L));
        queued.start();
        awaitQueueDepth(1);

        // 3) 세 번째는 executor가 거부한다 — fail-open으로 조용히 누락되지 않고 503이 나와야 한다
        assertThatThrownBy(() -> gate.signCourseScope(3L))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", CloudFrontErrorCode.SIGNING_OVERLOADED);

        releaseBlocker.countDown();
        blocker.join(2000);
        queued.join(2000);

        assertThat(admissionOf(gate).availablePermits()).isEqualTo(10);
    }

    private void awaitQueueDepth(int expected) throws InterruptedException {
        for (int i = 0; i < 200 && executor.getThreadPoolExecutor().getQueue().size() < expected; i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(expected);
    }

    private ThreadPoolTaskExecutor newExecutor(int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor newExecutor = CloudFrontExecutorConfig.buildSigningExecutor(
            poolSize, queueCapacity, new ThreadPoolExecutor.AbortPolicy());
        newExecutor.initialize();
        return newExecutor;
    }

    private CloudFrontSigningGate newGate(ThreadPoolTaskExecutor executor, int permits,
        long acquireTimeoutMs, long deadlineMs) {
        return new CloudFrontSigningGate(cloudFrontService, executor, permits, acquireTimeoutMs,
            deadlineMs, new SimpleMeterRegistry());
    }

    private Semaphore admissionOf(CloudFrontSigningGate gate) {
        return (Semaphore) ReflectionTestUtils.getField(gate, "admission");
    }
}
