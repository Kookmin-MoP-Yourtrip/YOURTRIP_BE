package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum UserErrorCode implements ErrorCode {

    EMAIL_ALREADY_EXIST("이미 가입된 이메일입니다.", HttpStatus.BAD_REQUEST),
    EMAIL_NOT_FOUND("존재하지 않는 이메일입니다.", HttpStatus.BAD_REQUEST),
    EMAIL_NOT_VERIFIED("이메일 인증이 완료되지 않았습니다.", HttpStatus.BAD_REQUEST),
    // 코드 불일치·만료·미발급을 통합한다. Redis TTL로 만료된 키는 사라져 "만료"와 "미발급"을
    // 구분할 수 없고, 사용자 안내도 "재발송"으로 동일하며, 만료 여부를 노출하지 않는 쪽이 보안상 낫다.
    INVALID_VERIFICATION_CODE("인증번호가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    // 인증 저장소(Redis) 장애 시의 fail-closed 응답. 인증코드는 Redis가 유일한 원본이라
    // 폴백이 불가능하므로, 사용자 잘못(400)이 아닌 서버 사정(503)으로 정직하게 실패한다.
    VERIFICATION_SERVICE_UNAVAILABLE("이메일 인증을 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.",
        HttpStatus.SERVICE_UNAVAILABLE),
    NOT_MATCH_PASSWORD("비밀번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST_FIELD("요청 필드가 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_REFRESH_TOKEN("유효하지 않은 리프레시 토큰입니다.", HttpStatus.BAD_REQUEST),
    USER_NOT_FOUND("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    NOT_MATCH_REFRESH_TOKEN("리프레시 토큰이 일치하지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;
}
