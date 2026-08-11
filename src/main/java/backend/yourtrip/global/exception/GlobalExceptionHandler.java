package backend.yourtrip.global.exception;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.exception.errorCode.CloudFrontErrorCode;
import backend.yourtrip.global.exception.errorCode.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Counter overloadRejectionCounter;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        // CloudFrontSigningGate의 거부(SIGNING_OVERLOADED)는 부하 중 초당 수백~수천 건
        // 발생할 수 있다. 매번 WARN 동기 로깅을 하면 로깅 자체가 새 병목이 된다 — 이 저장소는
        // 이미 2464418에서 운영 환경 로깅이 실제 CPU/IO 비용임을 실측으로 확인했다. 그래서
        // 과부하성 예외는 로그 대신 카운터로만 빈도를 추적한다.
        this.overloadRejectionCounter = Counter.builder("business.exception.overload.rejected")
            .description("과부하로 거부된 BusinessException 발생 횟수 (WARN 로그를 생략한 대상)")
            .register(meterRegistry);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        boolean isOverloadRejection = errorCode == CloudFrontErrorCode.SIGNING_OVERLOADED;

        if (isOverloadRejection) {
            overloadRejectionCounter.increment();
        } else {
            log.warn("BusinessException 발생: {}", errorCode);
        }

        String code = (errorCode instanceof Enum)
            ? ((Enum<?>) errorCode).name()
            : errorCode.getClass().getSimpleName();

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(errorCode.getStatus());
        if (isOverloadRejection) {
            // 클라이언트(Android 앱)가 재시도 시점을 알 수 있도록 명시한다.
            responseBuilder.header(HttpHeaders.RETRY_AFTER, "1");
        }

        return responseBuilder
            .body(Map.of(
                "timestamp", LocalDateTime.now(),
                "code", code,
                "message", e.getMessage()
            ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
        MethodArgumentNotValidException e) {

        String fieldMessages = e.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .collect(Collectors.joining(", "));

        if (fieldMessages.isBlank()) {
            fieldMessages = "요청 값이 유효하지 않습니다.";
        }

        return ResponseEntity.badRequest()
            .body(Map.of(
                "timestamp", LocalDateTime.now(),
                "code", "INVALID_REQUEST_FIELD",
                "message", fieldMessages
            ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidEnum(
        HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();
        if (cause instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException ife
            && ife.getTargetType() == KeywordType.class) {
            return ResponseEntity.badRequest()
                .body(Map.of(
                    "timestamp", LocalDateTime.now(),
                    "code", "INVALID_REQUEST_FIELD",
                    "message", "유효하지 않은 키워드 코드가 포함되어 있습니다."
                ));
        }
        // 그 외 기본 처리
        return ResponseEntity.badRequest()
            .body(Map.of(
                "timestamp", LocalDateTime.now(),
                "code", "INVALID_REQUEST_FIELD",
                "message", "요청 값을 바인딩할 수 없습니다."
            ));
    }
}