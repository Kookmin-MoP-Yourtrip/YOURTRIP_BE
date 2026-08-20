package backend.yourtrip.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link CourseDeadline} 단위 테스트 (ROADMAP 5-5).
 *
 * <p><b>시간에 의존하는 타입이라 테스트도 시간에 의존하기 쉽다.</b> 그래서 "정확히 몇 ms 남았나"를
 * 단언하지 않고 <b>불변식</b>만 단언한다 — 예산 0은 즉시 만료, 남은 시간은 음수가 되지 않는다,
 * 예산보다 크지 않다. 이렇게 두면 CI가 느린 날에도 흔들리지 않는다.
 */
class CourseDeadlineTest {

    @Nested
    @DisplayName("예산 생성")
    class Creation {

        @Test
        @DisplayName("예산이 null이면 거부한다 — 예산 없는 데드라인은 데드라인이 아니다")
        void rejectsNullBudget() {
            assertThatThrownBy(() -> CourseDeadline.startingNow(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("음수 예산은 거부한다 — 만료 상태를 만들려면 Duration.ZERO 를 쓴다")
        void rejectsNegativeBudget() {
            assertThatThrownBy(() -> CourseDeadline.startingNow(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("음수");
        }
    }

    @Nested
    @DisplayName("남은 시간")
    class Remaining {

        @Test
        @DisplayName("예산 안이면 남은 시간이 0보다 크고 예산을 넘지 않는다")
        void withinBudget() {
            CourseDeadline deadline = CourseDeadline.startingNow(Duration.ofSeconds(30));

            assertThat(deadline.remainingMs()).isPositive().isLessThanOrEqualTo(30_000L);
            assertThat(deadline.expired()).isFalse();
        }

        @Test
        @DisplayName("예산이 0이면 즉시 만료이고 남은 시간도 0이다")
        void zeroBudgetExpiresImmediately() {
            CourseDeadline deadline = CourseDeadline.startingNow(Duration.ZERO);

            assertThat(deadline.expired()).isTrue();
            assertThat(deadline.remainingMs()).isZero();
        }

        @Test
        @DisplayName("만료된 뒤에도 남은 시간은 음수가 아니라 0이다")
        void neverNegative() {
            // Future.get(음수) 는 "이미 만료"로 다뤄지지만, clamp 를 호출부마다 반복하게 두면
            // 언젠가 한 곳이 빠진다. 그래서 이 타입이 책임진다.
            CourseDeadline longExpired = new CourseDeadline(System.nanoTime() - Duration.ofHours(1).toNanos());

            assertThat(longExpired.remainingMs()).isZero();
            assertThat(longExpired.expired()).isTrue();
        }

        @Test
        @DisplayName("1ms 미만은 0으로 잘린다 — 새 외부 호출을 시작해봐야 못 끝낸다")
        void subMillisecondTruncatesToZero() {
            CourseDeadline deadline = new CourseDeadline(System.nanoTime() + 500_000L); // 0.5ms

            assertThat(deadline.remainingMs()).isZero();
            // 남은 시간은 0이지만 아직 "만료"는 아니다 — 둘은 다른 질문이다.
            assertThat(deadline.expired()).isFalse();
        }
    }

    @Nested
    @DisplayName("unbounded")
    class Unbounded {

        @Test
        @DisplayName("사실상 무제한이지만 유한하다 — 뺄셈 오버플로가 없다")
        void isLargeButFinite() {
            CourseDeadline deadline = CourseDeadline.unbounded();

            assertThat(deadline.expired()).isFalse();
            // 파이프라인 지연 예산(p95 17~24초)보다 두 자릿수 위.
            assertThat(deadline.remainingMs()).isGreaterThan(Duration.ofMinutes(30).toMillis());
            assertThat(deadline.remainingMs()).isLessThan(Long.MAX_VALUE);
        }
    }
}
