package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum MailErrorCode implements ErrorCode {

    MAIL_SEND_FAILED("이메일 전송에 실패했습니다. 잠시 후 다시 시도해주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_MAIL_ADDRESS("잘못된 이메일 주소입니다.", HttpStatus.BAD_REQUEST),
    // 메일 전용 풀이 가득 찼다 — 사용자 잘못이 아니라 서버 처리 용량 문제라 429가 아니라 503이다(#221).
    MAIL_BUSY("인증 메일 요청이 몰려 있습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String message;
    private final HttpStatus status;
}