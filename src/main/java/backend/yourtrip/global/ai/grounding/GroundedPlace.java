package backend.yourtrip.global.ai.grounding;

import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.StyleTag;
import backend.yourtrip.global.ai.route.SlotType;
import java.util.Objects;

/**
 * 실존이 확인되고 좌표가 확보된 장소 — <b>RouteOptimizer에 들어갈 수 있는 상태</b> (ROADMAP 5-2).
 *
 * <p>좌표를 primitive로 두는 것이 이 타입의 계약이다. 좌표 없는 장소는 여기까지 오지 못하고
 * 그라운딩에서 탈락한다 — <b>지금 코드가 {@code 0.0/0.0}으로 저장해 성공을 위장하는 것이 정확히
 * 그 실수다</b>(로드맵 목표 4·7-4).
 *
 * @param placeUrl        카카오 플레이스 URL. <b>{@code SUGGESTED}는 검증 때 함께 승계</b>하고,
 *                        {@code SEEDED}·{@code LISTED}는 비어 있어 5-10이 채운다
 * @param matchedModifier 스타일 쿼리 유래 표식. 8-7의 삭제 로그가 이 태그로 SEO 편승 여부를 재고,
 *                        그 결과가 9단계(4층) 착수 조건이 된다
 */
public record GroundedPlace(
    String name,
    SlotType slotType,
    double latitude,
    double longitude,
    String address,
    String placeUrl,
    CandidateSourceType source,
    StyleTag matchedModifier
) {

    public GroundedPlace {
        Objects.requireNonNull(name, "name은 필수다");
        Objects.requireNonNull(slotType, "slotType은 필수다");
        Objects.requireNonNull(source, "source는 필수다 — 삭제 로그와 메트릭이 이 값으로 갈린다");
        address = address == null ? "" : address;
    }

    /** URL이 비어 5-10의 보강 대상인가. {@code SUGGESTED}는 그라운딩에서 이미 받았다. */
    public boolean needsPlaceUrl() {
        return placeUrl == null || placeUrl.isBlank();
    }

    public GroundedPlace withPlaceUrl(String url) {
        return new GroundedPlace(name, slotType, latitude, longitude, address, url, source,
            matchedModifier);
    }
}
