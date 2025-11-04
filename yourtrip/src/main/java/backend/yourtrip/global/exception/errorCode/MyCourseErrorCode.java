package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum MyCourseErrorCode implements ErrorCode {

    PLACE_NOT_FOUND("장소를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    COURSE_NOT_FOUND("코스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND);

    private final String message;
    private final HttpStatus status;

}
