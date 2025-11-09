package backend.yourtrip.global.exception;

import backend.yourtrip.global.exception.errorCode.ErrorCode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
}