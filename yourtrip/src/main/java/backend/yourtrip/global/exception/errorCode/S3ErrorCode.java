package backend.yourtrip.global.exception.errorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum S3ErrorCode implements ErrorCode {

    EMPTY_FILE("빈 파일입니다.", HttpStatus.BAD_REQUEST),
    OVER_SIZE_FILE("허용 용량을 초과하였습니다.", HttpStatus.BAD_REQUEST),
    NOT_ALLOW_FILE_TYPE("해당 파일은 허용되지 않는 타입입니다.", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;

}
