package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum UserErrorCode implements ErrorCode {

    EMAIL_ALREADY_EXIST("이미 가입된 이메일입니다.", HttpStatus.BAD_REQUEST),
    EMAIL_NOT_FOUND("존재하지 않는 이메일입니다.", HttpStatus.BAD_REQUEST),
    NOT_MATCH_PASSWORD("비밀번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_REFRESH_TOKEN("유효하지 않은 리프레시 토큰입니다.", HttpStatus.BAD_REQUEST),
    USER_NOT_FOUND("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    NOT_MATCH_REFRESH_TOKEN("리프레시 토큰이 일치하지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;
}
