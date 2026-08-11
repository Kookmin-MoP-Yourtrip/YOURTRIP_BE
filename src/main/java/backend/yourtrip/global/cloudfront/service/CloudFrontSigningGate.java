package backend.yourtrip.global.cloudfront.service;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.CloudFrontErrorCode;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * mycourse 상세조회의 CloudFront 서명을 요청 단위로 입장 제어하는 게이트.
 *
 * abortpolicy-gate-verification.md의 실측대로, AbortPolicy 전환만으로는 부하 차단이 태스크
 * 단위(이미지 1장)로 일어난다 — 큐+풀이 가득 찬 순간에 걸린 요청은 이미지 10장 중 일부만
 * 서명되고 나머지는 조용히 누락된 "부분 응답"이 되며, 이게 status 200으로 나가 지표에도
 * 잡히지 않는다(scripts/k6/detail-ramping.js의 체크는 status만 본다). 이 게이트는 세마포어로
 * 부하 차단을 요청 단위로 옮겨, 통과한 요청은 이미지가 전부 붙은 완전한 응답을 받고 나머지는
 * 명확한 503을 받게 한다.
 *
 * 이 빈만 cloudFrontSigningExecutor를 참조한다 — 서명 스레드 자신이 이 게이트를 재진입할
 * 경로가 구조적으로 생기지 않게 하기 위해서다(서명 태스크는 permit을 요구하지 않으므로
 * 데드락은 없지만, 그 안전성이 "게이트를 거치는 코드가 서명 스레드에서 실행되지 않는다"는
 * 관습이 아니라 구조로 보장되게 한다).
 */
@Slf4j
@Component
public class CloudFrontSigningGate {

    private final CloudFrontService cloudFrontService;
    private final ThreadPoolTaskExecutor executor;
    private final Semaphore admission;
    private final long acquireTimeoutMs;
    private final long deadlineMs;
    private final int maxKeysPerRequest;

    private final Counter gateRejectedCounter;
    private final Counter submitRejectedCounter;
    private final Counter deadlineExceededCounter;
    private final Timer gateWaitTimer;

    public CloudFrontSigningGate(
        CloudFrontService cloudFrontService,
        @Qualifier("cloudFrontSigningExecutor") ThreadPoolTaskExecutor executor,
        @Value("${cloudfront.signing.permits:30}") int permits,
        @Value("${cloudfront.signing.acquire-timeout-ms:100}") long acquireTimeoutMs,
        @Value("${cloudfront.signing.max-keys-per-request:50}") int maxKeysPerRequest,
        @Value("${cloudfront.signing.deadline-ms:2000}") long deadlineMs,
        MeterRegistry meterRegistry) {
        this.cloudFrontService = cloudFrontService;
        this.executor = executor;
        // permits = (풀 크기 x 지연예산) / (요청당 이미지 수 x 서명 1회 비용). permits는
        // 처리량이 아니라 지연 예산을 정한다(처리량 상한은 풀 크기만의 함수라 permits가 소거된다).
        this.admission = new Semaphore(permits);
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.deadlineMs = deadlineMs;
        this.maxKeysPerRequest = maxKeysPerRequest;

        this.gateRejectedCounter = Counter.builder("cloudfront.signing.gate.rejected")
            .description("세마포어 permit을 획득하지 못해 게이트에서 거부된 요청 수 — 정상적인 배압")
            .register(meterRegistry);
        this.submitRejectedCounter = Counter.builder("cloudfront.signing.gate.submit.rejected")
            .description("게이트를 통과했지만 executor 제출 단계에서 거부된 태스크 수 — "
                + "0이 아니면 게이트 사이징(permits/queue)이 잘못됐다는 신호")
            .register(meterRegistry);
        this.deadlineExceededCounter = Counter.builder("cloudfront.signing.gate.deadline.exceeded")
            .description("서명 데드라인을 초과한 요청 수 — permit 영구 누수 방지 안전망, "
                + "정상 부하에서는 발동하면 안 된다")
            .register(meterRegistry);
        this.gateWaitTimer = Timer.builder("cloudfront.signing.gate.wait")
            .description("permit 획득까지 대기한 시간 분포")
            .register(meterRegistry);
        Gauge.builder("cloudfront.signing.gate.permits.available", admission, Semaphore::availablePermits)
            .description("게이트가 현재 보유한 가용 permit 수 — 시간에 따라 증가 추세면 "
                + "permit 인플레이션 버그(acquire 실패 경로에서 release가 호출됨)를 의심한다")
            .register(meterRegistry);
    }

    /**
     * s3Key -> signedUrl. 서명에 실패했거나 permit/큐 과부하로 거부된 key는 반환 맵에 없다
     * (fail-open 부분 응답). 중복 key는 자연스럽게 dedup된다.
     */
    public Map<String, String> signAll(Collection<String> s3Keys) {
        if (s3Keys.isEmpty()) {
            return Map.of();
        }
        if (s3Keys.size() > maxKeysPerRequest) {
            throw new BusinessException(MyCourseErrorCode.TOO_MANY_IMAGES);
        }

        long waitStartNanos = System.nanoTime();
        boolean acquired;
        try {
            acquired = admission.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CloudFrontErrorCode.SIGNING_OVERLOADED);
        } finally {
            gateWaitTimer.record(System.nanoTime() - waitStartNanos, TimeUnit.NANOSECONDS);
        }

        // acquire 판정은 반드시 try 블록 밖에서 끝낸다. Semaphore.release()는 소유권을
        // 검사하지 않으므로, 이 판정을 try 안에 두고 실패 시에도 finally에서 release하면
        // permit이 거부될 때마다 1개씩 늘어나 게이트가 조용히(예외도 로그도 없이) 무력화된다.
        if (!acquired) {
            gateRejectedCounter.increment();
            throw new BusinessException(CloudFrontErrorCode.SIGNING_OVERLOADED);
        }

        try {
            return submitAndJoin(s3Keys);
        } finally {
            admission.release();
        }
    }

    private Map<String, String> submitAndJoin(Collection<String> s3Keys) {
        Map<String, CompletableFuture<String>> futures = new LinkedHashMap<>();
        s3Keys.forEach(key -> futures.computeIfAbsent(key, this::submit));

        // 태스크별 orTimeout()은 쓰지 않는다 — future를 실패시킬 뿐 실행 중인 서명 연산 자체를
        // 취소하지 못해(CompletableFuture.cancel(true)는 인터럽트를 무시) CPU 포화 상황에서
        // 대기자만 쳐내고 작업은 남겨 오히려 동시 부하를 늘린다. 대신 요청당 타이머 1개로
        // permit 영구 누수만 방지한다 — 정상 부하에서는 발동하지 않아야 하는 안전망이다.
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                .get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            deadlineExceededCounter.increment();
            log.error("서명 데드라인 {}ms 초과 — 서명 태스크가 정지했을 가능성이 있다", deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // submit()의 exceptionally가 개별 future의 예외를 이미 null로 흡수하므로
            // allOf가 예외로 완료되는 경우는 없다.
        }

        Map<String, String> signedUrls = new LinkedHashMap<>();
        futures.forEach((key, future) -> {
            String signedUrl = future.getNow(null);
            if (signedUrl != null) {
                signedUrls.put(key, signedUrl);
            }
        });
        return signedUrls;
    }

    private CompletableFuture<String> submit(String key) {
        try {
            return CompletableFuture
                .supplyAsync(() -> cloudFrontService.getSignedUrl(key), executor)
                .exceptionally(ex -> {
                    log.warn("CloudFront Signed URL 발급 실패 (key={})", key, unwrapCause(ex));
                    return null;
                });
        } catch (RejectedExecutionException e) {
            // CompletableFuture.supplyAsync(f, executor)는 executor.execute()를 동기
            // 호출하므로, 거부 예외는 이 호출 스택에서 즉시 터진다 — future 객체 자체가
            // 반환되지 않아 뒤에 체이닝한 .exceptionally()로는 못 잡는다. 게이트가 제대로
            // 사이징됐다면 이 경로는 도달 불가능한 안전망이어야 한다.
            submitRejectedCounter.increment();
            return CompletableFuture.completedFuture(null);
        }
    }

    private Throwable unwrapCause(Throwable ex) {
        return (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
    }
}
