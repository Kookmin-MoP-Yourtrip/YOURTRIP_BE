package backend.yourtrip.domain.mycourse.dto.ai;

import java.time.LocalTime;

/**
 * 카카오 장소 검증까지 끝난 장소 1건. <b>트랜잭션 밖에서 조립되는 중간 표현이다.</b>
 *
 * <p>이 타입이 필요한 이유는 트랜잭션 경계 때문이다. 기존 구조는 Place를 먼저 저장한 뒤
 * 카카오를 조회해 {@code place.updateKakaoPlace(...)}로 더티체킹에 맡겼는데, 그러면
 * 카카오 호출이 트랜잭션 안에 있어야만 한다. 외부 I/O를 트랜잭션 밖으로 빼려면
 * "결과를 다 모은 뒤 완성된 엔티티로 한 번에 저장"하는 순서로 뒤집어야 하고,
 * 그 사이에 결과를 담아둘 그릇이 이것이다.
 *
 * <p>좌표가 {@code null}이면 카카오 매칭에 실패했다는 뜻이다 — 검증되지 않은 좌표를
 * 0.0/0.0으로 저장해 성공을 위장하지 않는다.
 */
public record ResolvedPlace(
    String placeName,
    LocalTime startTime,
    Double latitude,
    Double longitude,
    String placeUrl,
    String placeLocation
) {

    /** 카카오 매칭에 실패한 장소. AI가 준 이름과 시간만 남기고 좌표는 비운다. */
    public static ResolvedPlace unverified(String placeName, LocalTime startTime) {
        return new ResolvedPlace(placeName, startTime, null, null, null, null);
    }
}
