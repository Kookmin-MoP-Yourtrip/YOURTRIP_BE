package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum MyCourseErrorCode implements ErrorCode {

    COURSE_NOT_FOUND("코스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    ROLE_NOT_SPECIFY("코스 편집 역할이 지정되지 않았습니다.", HttpStatus.NOT_FOUND),
    DAY_SCHEDULE_NOT_FOUND("해당 일차 일정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    PLACE_NOT_FOUND("해당 장소를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    PLACE_IMAGE_NOT_FOUND("해당 장소 이미지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND);

    private final String message;
    private final HttpStatus status;

}
