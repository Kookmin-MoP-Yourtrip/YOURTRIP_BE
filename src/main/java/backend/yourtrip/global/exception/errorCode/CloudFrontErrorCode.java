package backend.yourtrip.global.exception.errorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CloudFrontErrorCode implements ErrorCode {

    FAIL_GENERATE_SIGNED_URL("이미지 URL 생성에 실패하였습니다. 잠시 후 다시 시도해주세요", HttpStatus.SERVICE_UNAVAILABLE),
    SIGNING_OVERLOADED("이미지 URL 발급 요청이 몰려 있습니다. 잠시 후 다시 시도해주세요", HttpStatus.SERVICE_UNAVAILABLE);

    private final String message;
    private final HttpStatus status;

}
