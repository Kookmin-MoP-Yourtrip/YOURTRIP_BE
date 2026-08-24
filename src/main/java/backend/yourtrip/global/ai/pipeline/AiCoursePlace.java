package backend.yourtrip.global.ai.pipeline;

import backend.yourtrip.global.ai.grounding.GroundedPlace;
import java.time.LocalTime;

/**
 * 좌표·시각·체류시간이 모두 확정된 장소 — 파이프라인이 내놓는 최소 단위.
 *
 * <p><b>{@link GroundedPlace}를 감싸는 형태인 것</b>은 {@code RoutedPlace}가
 * {@code RoutePlace}를 감싸는 것과 같은 이유다. 이름·주소·URL·{@code source}·
 * {@code matchedModifier}를 새 record에 다시 옮겨 적으면 필드가 늘 때마다 두 곳을 고쳐야 하고,
 * 옮겨 적는 과정에서 값이 어긋날 자리가 생긴다.
 *
 * @param startTime   방문 시각. 표시용 5분 올림까지 끝난 값이다
 * @param stayMinutes 체류시간. 하루가 넘쳐 축소됐다면 기본값보다 짧다
 */
public record AiCoursePlace(
    GroundedPlace place,
    LocalTime startTime,
    int stayMinutes
) {}
