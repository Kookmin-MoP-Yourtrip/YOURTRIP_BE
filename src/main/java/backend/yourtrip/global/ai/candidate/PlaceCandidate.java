package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.Objects;
import java.util.Set;

/**
 * 후보 풀의 항목 하나 (ROADMAP 5-8). <b>Curator가 여기서 고른다.</b>
 *
 * <p><b>좌표가 필수다.</b> 좌표 없는 후보는 풀에 넣어봐야 {@code RouteOptimizer}에 들어갈 수 없어
 * 나중에 탈락할 뿐이므로, 거르는 책임을 소스 쪽으로 올린다({@code RoutePlace}가 세운 것과 같은
 * 계약 — "좌표 없는 장소를 거르는 책임은 호출자에 있다").
 *
 * <p><b>{@code placeUrl} 필드는 두지 않는다.</b> 네이버·TourAPI 응답 어느 쪽에도 카카오 플레이스
 * URL이 없다 — 그래서 {@code PlaceUrlEnricher}(5-10)가 존재하고, URL은 후보가 아니라 <b>배치가
 * 확정된 장소</b>에만 붙는다.
 *
 * @param source        SEEDED 또는 LISTED. {@link CandidateSourceType#SUGGESTED}는 목록 밖이라 올 수 없다
 * @param styleTags     LISTED는 {@link TourCategoryMapper}, 시드 유래는 {@code matchedModifier}에서 온다
 * @param seedRank      네이버 {@code sort=comment} 순위(1~5). <b>null이면 시드에 들지 못한 후보</b>다
 * @param seedScope     그 순위를 낳은 <b>질의의 지명 단계</b>(이슈 #113). 순위는 질의 안에서만 의미가
 *                      있어, 이 값이 없으면 도시 전역 1위와 권역 안 1위를 구별할 수 없다.
 *                      <b>{@code seedRank}가 null이면 이 값도 null이어야 한다</b>
 * @param matchedModifier 스타일 쿼리에서 왔으면 그 태그. <b>검증된 속성이 아니라 검색이 그렇게
 *                        주장했다는 힌트</b>이고, 그 한계는 4층(V1 제외)이 메울 자리였다
 * @param distanceKm    권역 {@code anchor}로부터의 거리. anchor 좌표를 못 얻었으면 null
 * @param rawCategory   네이버 {@code category} 원문 또는 TourAPI {@code cat3}. 5-3의 슬롯 제약이 쓴다
 */
public record PlaceCandidate(
    CandidateSourceType source,
    String name,
    String address,
    double latitude,
    double longitude,
    SlotType slotType,
    Set<StyleTag> styleTags,
    Integer seedRank,
    SeedScope seedScope,
    StyleTag matchedModifier,
    Double distanceKm,
    String rawCategory
) {

    public PlaceCandidate {
        Objects.requireNonNull(source, "source는 필수다 — 그라운딩에서 어느 응답을 승계할지가 여기서 갈린다");
        if (source == CandidateSourceType.SUGGESTED) {
            throw new IllegalArgumentException(
                "SUGGESTED는 후보 목록에 존재할 수 없다 — 목록 밖에서 Curator가 내는 제안이다");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name은 필수다 — 6-7의 위조 검증이 이 값으로 대조한다");
        }
        Objects.requireNonNull(slotType, "slotType은 필수다 — 체류시간과 카테고리 제약을 결정한다");
        requireValidCoordinate(latitude, -90.0, 90.0, "위도");
        requireValidCoordinate(longitude, -180.0, 180.0, "경도");
        if (seedRank != null && seedRank < 1) {
            throw new IllegalArgumentException("seedRank는 1부터다 (시드에 없으면 null): " + seedRank);
        }
        // 시드에 못 든 후보에 단계가 붙으면 "어느 질의의 순위인가"라는 질문 자체가 성립하지 않는다.
        if (seedRank == null && seedScope != null) {
            throw new IllegalArgumentException(
                "시드에 들지 않은 후보에는 seedScope 가 없다: " + seedScope);
        }
        address = address == null ? "" : address;
        styleTags = styleTags == null ? Set.of() : Set.copyOf(styleTags);
    }

    /**
     * 지명 단계를 명시하지 않는 호출부용 위임 생성자 — <b>시드에 들었으면 {@link SeedScope#AREA}</b>다.
     *
     * <p>캐스케이드({@code NaverLocalSeedSource.Fallback})가 생기기 전에는 <b>권역 질의가 유일한
     * 단계</b>였으므로, 단계를 모르는 호출부가 뜻하는 것은 언제나 {@code AREA}다. 그래서 기본값을
     * 두는 것이 추측이 아니라 그 호출부들의 의미를 그대로 옮기는 일이 된다.
     *
     * <p><b>시더는 이 생성자를 쓰지 않는다.</b> 실제로 단계를 아는 유일한 곳이라 정본 생성자로
     * 값을 실어야 하고, 여기로 새면 {@code LOCATION} 후보가 조용히 {@code AREA}로 둔갑한다.
     */
    public PlaceCandidate(CandidateSourceType source, String name, String address, double latitude,
        double longitude, SlotType slotType, Set<StyleTag> styleTags, Integer seedRank,
        StyleTag matchedModifier, Double distanceKm, String rawCategory) {
        this(source, name, address, latitude, longitude, slotType, styleTags, seedRank,
            seedRank == null ? null : SeedScope.AREA, matchedModifier, distanceKm, rawCategory);
    }

    private static void requireValidCoordinate(double value, double min, double max, String label) {
        if (Double.isNaN(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                "%s 가 유효하지 않다: %s. 좌표 없는 후보를 거르는 책임은 소스에 있다".formatted(label, value));
        }
    }

    /**
     * 같은 후보를 다른 슬롯의 목록에 올린다.
     *
     * <p>TourAPI는 <b>슬롯이 아니라 좌표·{@code contentTypeId} 단위로</b> 조회된다(쿼터가 그렇게
     * 요구한다 — 슬롯마다 부르면 코스당 호출이 설계 예산의 네 배가 된다). 그래서 한 번 받은
     * 관광지 목록을 그 day의 관광 슬롯 여럿에 나눠 실을 때 이 복사가 필요하다.
     */
    public PlaceCandidate withSlotType(SlotType target) {
        return target == slotType ? this : new PlaceCandidate(source, name, address,
            latitude, longitude, target, styleTags, seedRank, seedScope, matchedModifier,
            distanceKm, rawCategory);
    }

    /** 네이버 시드에 든 후보인가 — 목록 정렬 ①그룹의 판정 기준이다. */
    public boolean seeded() {
        return seedRank != null;
    }

    /** TourAPI에 등록된 후보인가(단독이든 시더와 병합됐든). */
    public boolean official() {
        return source == CandidateSourceType.LISTED;
    }

    /**
     * 도시 전역 질의까지 내려가 데려온 후보인가 (이슈 #113).
     *
     * <p><b>{@code seeded()}와 함께 봐야 뜻이 산다</b> — 이 값이 참이면 {@code seedRank}는 권역이
     * 아니라 도시 전역에서의 순위라, 목록에 순위 숫자를 실으면 잘못된 근거가 된다.
     */
    public boolean fromCityWideQuery() {
        return seedScope == SeedScope.LOCATION;
    }
}
