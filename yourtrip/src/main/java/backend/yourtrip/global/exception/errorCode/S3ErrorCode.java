package backend.yourtrip.global.exception.errorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum S3ErrorCode implements ErrorCode {

    EMPTY_FILE("빈 파일입니다.", HttpStatus.BAD_REQUEST),
    OVER_SIZE_FILE("허용 용량을 초과하였습니다.", HttpStatus.BAD_REQUEST),
    NOT_ALLOW_FILE_TYPE("해당 파일은 허용되지 않는 타입입니다.", HttpStatus.BAD_REQUEST),
    FAIL_UPLOAD_FILE("파일 업로드에 실패하였습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_S3_KEY("유효하지 않은 S3 키입니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    FAIL_DELETE_FILE("파일 삭제에 실패하였습니다. 잠시 후 다시 시도해주세요", HttpStatus.SERVICE_UNAVAILABLE);

    private final String message;
    private final HttpStatus status;

}
