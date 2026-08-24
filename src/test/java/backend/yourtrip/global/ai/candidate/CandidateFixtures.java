package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.Set;

/**
 * 후보 관련 테스트가 공유하는 픽스처 (ROADMAP 5-8).
 *
 * <p>좌표는 <b>4-7 실호출로 받은 실제 값</b>을 쓴다({@code CandidateMatcherTest}가 세운 관례) —
 * 지어낸 좌표로 300m 임계값을 시험하면 "무엇이 합쳐지고 무엇이 안 합쳐지는지"가 테스트 안에서만
 * 성립하는 이야기가 된다.
 */
final class CandidateFixtures {

    // 경주, contentTypeId=12 실호출 값
    static final double CHEONMACHONG_LAT = 35.8386877792;   // 천마총(대릉원)
    static final double CHEONMACHONG_LON = 129.2104983997;
    static final double NAEMUL_LAT = 35.8362047083;         // 내물왕릉 — 천마총에서 288m
    static final double NAEMUL_LON = 129.2095707739;
    static final double CHEOMSEONGDAE_LAT = 35.8347222;     // 첨성대 — 천마총에서 500m 이상
    static final double CHEOMSEONGDAE_LON = 129.2192222;

    private CandidateFixtures() {
    }

    static PlaceCandidate seeded(String name, int seedRank, double latitude, double longitude) {
        return new PlaceCandidate(CandidateSourceType.SEEDED, name, "경북 경주시 " + name + "로 1",
            latitude, longitude, SlotType.ATTRACTION, Set.of(), seedRank, null, null, "관광,명소>유적지");
    }

    static PlaceCandidate listed(String name, double latitude, double longitude,
        Double distanceKm, Set<StyleTag> styleTags) {
        return new PlaceCandidate(CandidateSourceType.LISTED, name, "경북 경주시 " + name + "로 1",
            latitude, longitude, SlotType.ATTRACTION, styleTags, null, null, distanceKm, "A02010700");
    }

    /**
     * 지명 단계만 바꿔 복사한다 (이슈 #113).
     *
     * <p>위 픽스처들은 위임 생성자를 타 {@link SeedScope#AREA}가 되므로, 캐스케이드에서 온 후보를
     * 흉내 내려면 이 helper 가 필요하다.
     */
    static PlaceCandidate withScope(PlaceCandidate candidate, SeedScope scope) {
        return new PlaceCandidate(candidate.source(), candidate.name(), candidate.address(),
            candidate.latitude(), candidate.longitude(), candidate.slotType(),
            candidate.styleTags(), candidate.seedRank(), scope, candidate.matchedModifier(),
            candidate.distanceKm(), candidate.rawCategory());
    }

    static PlaceCandidate cafe(String name, String address, Integer seedRank, StyleTag modifier) {
        return new PlaceCandidate(CandidateSourceType.SEEDED, name, address,
            CHEONMACHONG_LAT, CHEONMACHONG_LON, SlotType.CAFE, Set.of(), seedRank, modifier, null,
            "음식점>카페,디저트");
    }
}
