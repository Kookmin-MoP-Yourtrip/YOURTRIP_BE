package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum UploadCourseErrorCode implements ErrorCode {

    UPLOAD_COURSE_NOT_FOUND("업로드 코스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    INVALID_SORT_TYPE("올바르지 않는 정렬 기준입니다.", HttpStatus.BAD_REQUEST),
    COURSE_ALREADY_UPLOAD("이미 업로드된 코스입니다.", HttpStatus.BAD_REQUEST),
    INVALID_THEME_TYPE("올바르지 않은 테마입니다.", HttpStatus.BAD_REQUEST),
    NOT_OWNED_UPLOAD_COURSE("본인이 업로드한 코스만 수정/삭제할 수 있습니다.", HttpStatus.FORBIDDEN);

    private final String message;
    private final HttpStatus status;
}
