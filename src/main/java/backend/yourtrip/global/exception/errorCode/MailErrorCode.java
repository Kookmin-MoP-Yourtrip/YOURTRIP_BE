package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum MailErrorCode implements ErrorCode {

    MAIL_SEND_FAILED("이메일 전송에 실패했습니다. 잠시 후 다시 시도해주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_MAIL_ADDRESS("잘못된 이메일 주소입니다.", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;
}