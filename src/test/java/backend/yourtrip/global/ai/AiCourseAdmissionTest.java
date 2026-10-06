package backend.yourtrip.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import backend.yourtrip.global.ai.config.AiAdmissionProperties;
import backend.yourtrip.global.exception.RetryLaterException;
import backend.yourtrip.global.exception.errorCode.AiCourseErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 입장 게이트의 계약 (#192) — 상한까지 받고, 넘치면 작업을 실행하지 않고 거절하며, 어떻게 끝나든
 * 자리를 돌려준다.
 *
 * <p>동시성을 스레드로 재현하지 않고 <b>작업 안에서 다시 입장을 시도</b>해 "자리를 쥔 채 다음 요청이
 * 온다"를 만든다. 결과가 스케줄링에 좌우되지 않아 매번 같다.
 */
class AiCourseAdmissionTest {

    private static final int RETRY_AFTER_SECONDS = 5;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private AiCourseAdmission admissionOf(int maxConcurrentRequests) {
        return new AiCourseAdmission(
            new AiAdmissionProperties(maxConcurrentRequests, RETRY_AFTER_SECONDS),
            new AiCourseMetrics(registry));
    }

    @Test
    @DisplayName("자리가 있으면 작업을 실행하고 그 결과를 돌려준다")
    void admit_WithinLimit_RunsWork() {
        AiCourseAdmission admission = admissionOf(1);

        assertThat(admission.admit(() -> "코스")).isEqualTo("코스");
        assertThat(count(AiCourseMetrics.ADMISSION_ADMITTED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("상한이 차면 기다리지 않고 AI_COURSE_BUSY 와 Retry-After 로 거절하며, 작업은 실행하지 않는다")
    void admit_WhenFull_RejectsWithoutRunningWork() {
        AiCourseAdmission admission = admissionOf(2);
        AtomicBoolean thirdRan = new AtomicBoolean(false);

        admission.admit(() -> admission.admit(() -> {
            // 두 자리를 모두 쥔 상태에서 세 번째 요청이 온다
            assertThatThrownBy(() -> admission.admit(() -> {
                thirdRan.set(true);
                return null;
            }))
                .isInstanceOfSatisfying(RetryLaterException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(AiCourseErrorCode.AI_COURSE_BUSY);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(RETRY_AFTER_SECONDS);
                });
            return null;
        }));

        assertThat(thirdRan).isFalse();
        assertThat(count(AiCourseMetrics.ADMISSION_ADMITTED)).isEqualTo(2.0);
        assertThat(count(AiCourseMetrics.ADMISSION_REJECTED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("작업이 예외로 끝나도 자리를 돌려준다 — 해제가 새면 상한만큼 쌓인 뒤 전면 429가 된다")
    void admit_WorkThrows_ReleasesSlot() {
        AiCourseAdmission admission = admissionOf(1);

        assertThatThrownBy(() -> admission.admit(() -> {
            throw new IllegalStateException("파이프라인 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(admission.admit(() -> "다음 요청")).isEqualTo("다음 요청");
        assertThat(inUse()).isZero();
    }

    @Test
    @DisplayName("점유 게이지는 작업 중에만 올라가고 끝나면 0으로 돌아온다")
    void admit_GaugeTracksInUse() {
        AiCourseAdmission admission = admissionOf(3);

        double during = admission.admit(this::inUse);

        assertThat(during).isEqualTo(1.0);
        assertThat(inUse()).isZero();
    }

    @Test
    @DisplayName("기동 직후 두 판정 결과의 시계열이 0으로 존재한다 — '없음'과 '0'을 가르기 위해서다")
    void metrics_RegisterZeroSeriesAtStartup() {
        admissionOf(1);

        assertThat(count(AiCourseMetrics.ADMISSION_ADMITTED)).isZero();
        assertThat(count(AiCourseMetrics.ADMISSION_REJECTED)).isZero();
    }

    private double count(String result) {
        return registry.get(AiCourseMetrics.ADMISSION).tag("result", result).counter().count();
    }

    private double inUse() {
        return registry.get(AiCourseMetrics.ADMISSION_IN_USE).gauge().value();
    }
}
