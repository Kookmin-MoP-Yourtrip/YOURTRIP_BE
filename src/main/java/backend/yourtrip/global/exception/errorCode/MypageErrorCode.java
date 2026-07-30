package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum MypageErrorCode implements ErrorCode {

    // 프로필 이미지
    PROFILE_IMAGE_UPLOAD_FAILED("프로필 이미지 업로드에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_PROFILE_IMAGE("업로드할 프로필 이미지가 필요합니다.", HttpStatus.BAD_REQUEST),

    // 닉네임
    INVALID_NICKNAME("닉네임 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    NICKNAME_DUPLICATED("이미 사용 중인 닉네임입니다.", HttpStatus.BAD_REQUEST),

    // 비밀번호
    PASSWORD_INCORRECT("기존 비밀번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
    NEW_PASSWORD_INVALID("새 비밀번호 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),

    // 탈퇴
    ALREADY_DELETED_USER("이미 탈퇴된 사용자입니다.", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;
}