package backend.yourtrip.global.exception.errorCode;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

    String getMessage();

    HttpStatus getStatus();
}
