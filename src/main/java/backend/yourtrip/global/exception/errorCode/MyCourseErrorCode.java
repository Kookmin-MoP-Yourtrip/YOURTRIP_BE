package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum MyCourseErrorCode implements ErrorCode {

    COURSE_NOT_FOUND("코스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    DAY_SCHEDULE_NOT_FOUND("해당 일차 일정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    PLACE_NOT_FOUND("해당 장소를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    PLACE_IMAGE_NOT_FOUND("해당 장소 이미지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    CANNOT_FORK_OWNED_COURSE("자신이 업로드한 코스는 포크할 수 없습니다.", HttpStatus.BAD_REQUEST),
    NOT_OWNED_COURSE("해당 코스에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN);

    // KAKAO_API_FAILED 는 지웠다 — 유일한 생성 지점이던 KakaoLocalClient.findBestPlace 가
    // 사라져 발화할 경로가 없어졌다. 발화하지 않는 상수를 두면 이 enum 이 동작의 기록이 아니라
    // 설계 의도의 기록이 된다(ROADMAP 7-2 가 AiCourseErrorCode 를 둘로 줄일 때와 같은 판단).
    // 카카오 호출 실패는 이제 PlaceLookup.Failed 로 값이 되어, 부르는 쪽이 각자 흡수한다.

    private final String message;
    private final HttpStatus status;

}
