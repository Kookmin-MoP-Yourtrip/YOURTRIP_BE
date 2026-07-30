package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum FeedErrorCode implements ErrorCode{

    FEED_NOT_FOUND("피드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    FEED_UPDATE_NOT_AUTHORIZED("피드를 수정할 권한이 없습니다.", HttpStatus.FORBIDDEN),
    FEED_DELETE_NOT_AUTHORIZED("피드를 삭제할 권한이 없습니다.", HttpStatus.FORBIDDEN),
    FEED_ALREADY_DELETED("이미 삭제된 피드입니다.", HttpStatus.BAD_REQUEST),
    INVALID_FEED_REQUEST("잘못된 피드 요청입니다.", HttpStatus.BAD_REQUEST),
    FEED_TITLE_REQUIRED("피드 제목은 필수입니다.", HttpStatus.BAD_REQUEST),
    FEED_CONTENT_REQUIRED("피드 내용은 필수입니다.", HttpStatus.BAD_REQUEST),
    UPLOAD_COURSE_NOT_FOUND( "업로드 코스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    UPLOAD_COURSE_FORBIDDEN("업로드 코스를 참조할 권한이 없습니다.", HttpStatus.FORBIDDEN),
    MEDIA_FILES_REQUIRED("최소 1개 이상의 미디어 파일을 업로드해야 합니다.", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;
}
