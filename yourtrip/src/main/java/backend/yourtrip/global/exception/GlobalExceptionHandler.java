package backend.yourtrip.global.exception;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.exception.errorCode.ErrorCode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException e) {
        log.warn("BusinessException 발생: {}", e.getErrorCode());

        ErrorCode errorCode = e.getErrorCode();

        String code = (errorCode instanceof Enum)
            ? ((Enum<?>) errorCode).name()
            : errorCode.getClass().getSimpleName();

        return ResponseEntity
            .status(e.getErrorCode().getStatus())
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