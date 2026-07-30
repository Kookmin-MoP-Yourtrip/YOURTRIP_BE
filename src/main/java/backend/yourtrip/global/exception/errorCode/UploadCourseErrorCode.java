package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum UploadCourseErrorCode implements ErrorCode {

    UPLOAD_COURSE_NOT_FOUND("업로드 코스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    INVALID_SORT_TYPE("올바르지 않는 정렬 기준입니다.", HttpStatus.BAD_REQUEST),
    COURSE_ALREADY_UPLOAD("이미 업로드된 코스입니다.", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;
}
