package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum KakaoErrorCode implements ErrorCode {

    INVALID_AUTH_CODE("유효하지 않은 인가코드입니다.", HttpStatus.BAD_REQUEST),
    TOKEN_REQUEST_FAILED("카카오 토큰 발급에 실패했습니다.", HttpStatus.BAD_GATEWAY),
    USERINFO_REQUEST_FAILED("카카오 사용자 정보 조회에 실패했습니다.", HttpStatus.BAD_GATEWAY),
    EMAIL_SCOPE_NOT_GRANTED("카카오 이메일 제공 동의가 필요합니다.", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;
}