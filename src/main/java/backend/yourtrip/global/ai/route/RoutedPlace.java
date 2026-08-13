package backend.yourtrip.global.ai.route;

import java.time.LocalTime;

/**
 * 순서와 시각이 정해진 장소 1건.
 *
 * <p>{@link #place}를 통째로 품는다 — 호출자가 {@code id}로 자기 타입을 되찾기 위해서다.
 * 최적화기는 이름·주소·URL을 알 필요가 없으므로 그것들을 복사해 나르지 않는다.
 *
 * <p><b>{@link #stayMinutes}가 출력에 있는 이유.</b> 하루가 넘칠 때 체류시간을 0.8배로 줄이는
 * 보정이 들어가는데, 그게 적용됐는지는 <b>출력에서만 확인할 수 있다.</b> 이 필드가 없으면 "왜
 * 시각이 이렇게 촘촘하지"를 나중에 재현할 방법이 없다.
 *
 * @param place       원본 입력. 좌표와 슬롯 종류가 그대로 들어 있다
 * @param startTime   방문 시작 시각. <b>5분 단위로 올림된 표시용 값</b>이라 내부 계산값보다
 *                    최대 4분 늦을 수 있다. 항상 늦는 방향이므로 이 시각에 맞춰 움직이면
 *                    계획보다 여유가 있다
 * @param stayMinutes 이 장소에 머무는 시간(분). 축소가 적용됐다면 슬롯 기본값보다 작다
 */
public record RoutedPlace(
    RoutePlace place,
    LocalTime startTime,
    int stayMinutes
) {
}
