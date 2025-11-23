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
    PLACE_IMAGE_NOT_FOUND("해당 장소 이미지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    CANNOT_FORK_OWNED_COURSE("자신이 업로드한 코스는 포크할 수 없습니다.", HttpStatus.BAD_REQUEST),
    NOT_OWNED_COURSE("해당 코스에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN),
    JSON_TRANSFORMATION_FAILED("AI 코스 작성 중 JSON 변환에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String message;
    private final HttpStatus status;

}
