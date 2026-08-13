package backend.yourtrip.domain.mycourse.dto.ai;

import java.util.List;

/**
 * 카카오 검증이 끝난 하루치 일정. {@link ResolvedPlace}와 같은 이유로 존재하는
 * 트랜잭션 밖 중간 표현이다.
 */
public record ResolvedDay(
    int day,
    List<ResolvedPlace> places
) {

}
