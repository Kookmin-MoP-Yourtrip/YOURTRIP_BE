package backend.yourtrip.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.exception.errorCode.AiCourseErrorCode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@link RetryLaterException}이 429 + {@code Retry-After}로 나가고, 본문은 다른 비즈니스 예외와 같은
 * 형식을 유지하는지 확인한다 (#192). 응답 형식이 곧 FE 와의 계약이다.
 */
class GlobalExceptionHandlerRetryLaterTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("RetryLaterException 은 상태·본문은 BusinessException 과 같게, Retry-After 헤더만 더해 나간다")
    void retryLater_AddsRetryAfterHeader() {
        RetryLaterException e = new RetryLaterException(AiCourseErrorCode.AI_COURSE_BUSY, 5);

        ResponseEntity<Map<String, Object>> response = handler.handleRetryLaterException(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody())
            .containsEntry("code", "AI_COURSE_BUSY")
            .containsEntry("message", AiCourseErrorCode.AI_COURSE_BUSY.getMessage())
            .containsKey("timestamp");
    }
}
