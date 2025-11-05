package backend.yourtrip.global.exception;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException e) {
        log.warn("BusinessException 발생: {}", e.getErrorCode());
        return ResponseEntity
            .status(e.getErrorCode().getStatus())
            .body(Map.of(
                "timestamp", LocalDateTime.now(),
                "code", e.getErrorCode().getStatus().name(),
                "message", e.getMessage()
            ));
    }
}