package backend.yourtrip.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import backend.yourtrip.domain.mycourse.dto.request.AICourseCreateRequest;
import backend.yourtrip.global.ai.config.AiAdmissionProperties;
import backend.yourtrip.global.exception.RetryLaterException;
import backend.yourtrip.global.exception.errorCode.AiCourseErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 입장 게이트의 계약 (#192, 작업 자리 단위는 #200) — 요청마다 자리 {@code 1 + 일수}개를 떼고, 모자라면 작업을
 * 실행하지 않고 거절하며, 자리는 그 자리를 묶은 LLM 작업이 끝날 때(묶이지 않은 몫은 요청이 끝날 때) 돌아간다.
 *
 * <p>동시성을 스레드로 재현하지 않는다. "자리를 쥔 채 다음 요청이 온다"는 <b>작업 안에서 다시 입장을 시도</b>해
 * 만들고, "기다림을 끊은 뒤에도 도는 LLM 호출"은 <b>직접 완료시키는 {@link CompletableFuture}</b>로 만든다.
 * 결과가 스케줄링에 좌우되지 않아 매번 같다.
 */
class AiCourseAdmissionTest {

    private static final int RETRY_AFTER_SECONDS = 5;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private AiCourseAdmission admissionOf(int maxWorkUnits) {
        return new AiCourseAdmission(
            new AiAdmissionProperties(maxWorkUnits, RETRY_AFTER_SECONDS),
            new AiCourseMetrics(registry));
    }

    @Test
    @DisplayName("요청 하나는 Planner 1 + day 마다 Curator 1 만큼의 자리를 뗀다")
    void unitsFor_IsOnePlusDays() {
        assertThat(AiCourseAdmission.unitsFor(1)).isEqualTo(2);
        assertThat(AiCourseAdmission.unitsFor(5)).isEqualTo(6);
    }

    @Test
    @DisplayName("자리가 있으면 작업을 실행하고 그 결과를 돌려준다")
    void admit_WithinCapacity_RunsWork() {
        AiCourseAdmission admission = admissionOf(4);

        assertThat(admission.<String>admit(3, lease -> "코스")).isEqualTo("코스");
        assertThat(count(AiCourseMetrics.ADMISSION_ADMITTED, 3)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("자리가 모자라면 기다리지 않고 AI_COURSE_BUSY 와 Retry-After 로 거절하며, 작업은 실행하지 않는다")
    void admit_WhenShort_RejectsWithoutRunningWork() {
        AiCourseAdmission admission = admissionOf(6);
        AtomicBoolean secondRan = new AtomicBoolean(false);

        // 3일 요청(4자리)이 쥔 동안 남은 2자리로는 2일 요청(3자리)이 들어오지 못한다
        admission.admit(3, held -> {
            assertThatThrownBy(() -> admission.admit(2, lease -> {
                secondRan.set(true);
                return null;
            }))
                .isInstanceOfSatisfying(RetryLaterException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(AiCourseErrorCode.AI_COURSE_BUSY);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(RETRY_AFTER_SECONDS);
                });
            return null;
        });

        assertThat(secondRan).isFalse();
        assertThat(count(AiCourseMetrics.ADMISSION_REJECTED, 2)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("같은 남은 자리라도 짧은 요청은 들어가고 긴 요청은 거절된다 — 거절이 요청 수가 아니라 자리 수로 갈린다")
    void admit_RemainingUnits_AdmitShortRejectLong() {
        AiCourseAdmission admission = admissionOf(6);

        admission.admit(3, held -> {
            // 남은 자리 2: 1일(2자리)은 들어가고, 그 안에서 다시 온 1일은 0자리라 거절된다
            assertThat(admission.<String>admit(1, lease -> "당일치기")).isEqualTo("당일치기");
            assertThatThrownBy(() -> admission.admit(2, lease -> null))
                .isInstanceOf(RetryLaterException.class);
            return null;
        });

        assertThat(count(AiCourseMetrics.ADMISSION_ADMITTED, 1)).isEqualTo(1.0);
        assertThat(count(AiCourseMetrics.ADMISSION_REJECTED, 2)).isEqualTo(1.0);
        assertThat(inUse()).isZero();
    }

    @Test
    @DisplayName("작업이 예외로 끝나도 자리를 돌려준다 — 해제가 새면 총량만큼 쌓인 뒤 전면 429가 된다")
    void admit_WorkThrows_ReleasesUnits() {
        AiCourseAdmission admission = admissionOf(6);

        assertThatThrownBy(() -> admission.admit(5, lease -> {
            throw new IllegalStateException("파이프라인 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(admission.<String>admit(5, lease -> "다음 요청")).isEqualTo("다음 요청");
        assertThat(inUse()).isZero();
    }

    @Test
    @DisplayName("점유 게이지는 작업 중 떼어 간 자리 수만큼 올라가고 끝나면 0으로 돌아온다")
    void admit_GaugeTracksUnitsInUse() {
        AiCourseAdmission admission = admissionOf(16);

        double during = admission.admit(3, lease -> inUse());

        assertThat(during).isEqualTo(4.0);
        assertThat(inUse()).isZero();
    }

    @Test
    @DisplayName("요청이 끝나도 아직 도는 LLM 작업의 자리는 그 작업이 끝날 때 돌아간다 — 버려진 호출도 슬롯을 쓰기 때문이다")
    void admit_TrackedTaskOutlivesRequest_HoldsUnitUntilDone() {
        AiCourseAdmission admission = admissionOf(16);
        CompletableFuture<String> abandonedCall = new CompletableFuture<>();
        CompletableFuture<String> finishedCall = CompletableFuture.completedFuture("planner");

        // 3일 요청(4자리): Planner 는 끝났고, Curator 하나는 예산이 지나 기다림을 끊은 채 아직 돈다
        admission.admit(3, lease -> {
            lease.track(finishedCall);
            lease.track(abandonedCall);
            return null;
        });

        // 끝난 Planner 1 + 올리지 않은 2 는 돌아왔고, 도는 Curator 1 만 남는다
        assertThat(inUse()).isEqualTo(1.0);

        abandonedCall.complete("늦게 끝난 Curator");
        assertThat(inUse()).isZero();
    }

    @Test
    @DisplayName("작업이 실패로 끝나도 그 자리는 돌아간다")
    void track_FailedTask_ReleasesUnit() {
        AiCourseAdmission admission = admissionOf(16);
        CompletableFuture<String> call = new CompletableFuture<>();

        admission.admit(1, lease -> lease.track(call));
        assertThat(inUse()).isEqualTo(1.0);

        call.completeExceptionally(new IllegalStateException("LLM 실패"));
        assertThat(inUse()).isZero();
    }

    @Test
    @DisplayName("예약보다 많은 작업이 올라오면 넘친 작업은 자리 없이 돌고, 자리는 예약한 만큼만 돌아간다")
    void track_MoreTasksThanReserved_DoesNotOverRelease() {
        AiCourseAdmission admission = admissionOf(16);
        CompletableFuture<String> first = new CompletableFuture<>();
        CompletableFuture<String> second = new CompletableFuture<>();
        CompletableFuture<String> third = new CompletableFuture<>();

        // 1일 요청은 2자리인데 작업이 3개 올라온다
        admission.admit(1, lease -> {
            lease.track(first);
            lease.track(second);
            return lease.track(third);
        });
        assertThat(inUse()).isEqualTo(2.0);

        first.complete("a");
        second.complete("b");
        third.complete("c");

        // 게이트가 총량을 넘겨 부풀지 않는다 — 다음 요청이 총량 16 을 정확히 다시 뗄 수 있다
        assertThat(inUse()).isZero();
        assertThat(admission.<String>admit(15, lease -> "총량만큼")).isEqualTo("총량만큼");
        assertThatThrownBy(() -> admission.admit(15, lease -> admission.admit(1, inner -> null)))
            .isInstanceOf(RetryLaterException.class);
    }

    @Test
    @DisplayName("추적하지 않는 임대는 어떤 게이트도 건드리지 않는다 — 입장 제한 없이 에이전트를 부르는 테스트 경로용")
    void untracked_IsNoOp() {
        CompletableFuture<String> call = new CompletableFuture<>();

        assertThat(LlmWorkLease.untracked().track(call)).isSameAs(call);
        call.complete("결과");
    }

    @Test
    @DisplayName("기동 직후 두 판정 결과 × 허용 일수 전부의 시계열이 0으로 존재한다 — '없음'과 '0'을 가르기 위해서다")
    void metrics_RegisterZeroSeriesAtStartup() {
        admissionOf(16);

        for (int days = 1; days <= AICourseCreateRequest.MAX_TRIP_DAYS; days++) {
            assertThat(count(AiCourseMetrics.ADMISSION_ADMITTED, days)).isZero();
            assertThat(count(AiCourseMetrics.ADMISSION_REJECTED, days)).isZero();
        }
    }

    private double count(String result, int days) {
        return registry.get(AiCourseMetrics.ADMISSION)
            .tag("result", result)
            .tag("days", String.valueOf(days))
            .counter().count();
    }

    private double inUse() {
        return registry.get(AiCourseMetrics.ADMISSION_IN_USE).gauge().value();
    }
}
