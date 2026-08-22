package backend.yourtrip.global.ai.candidate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 후보 풀의 중복 정리 (ROADMAP 5-8). <b>순수 함수</b>라 스테이지에서 떼어내 결정론적으로 테스트한다.
 *
 * <p>중복은 두 층에서 생기고, 층마다 규칙이 다르다.
 *
 * <ol>
 *   <li><b>같은 소스 안</b> — 기본 쿼리와 스타일 modifier 쿼리가 같은 가게를 물어온다.
 *       provider가 하나라 {@code 정규화 이름 + 주소} 등가 키로 충분하다</li>
 *   <li><b>소스 간</b>(관광 슬롯) — 대릉원은 네이버에도 TourAPI에도 있다. 여기는 좌표와 이름을
 *       <b>둘 다</b> 봐야 한다({@link CandidateMatcher#isSamePlace})</li>
 * </ol>
 *
 * <p><b>소스 간 중복은 제거가 아니라 병합이다.</b> 한쪽을 버리면 정보를 잃는다 — TourAPI는
 * 정비된 좌표와 분류를, 네이버는 "사람들이 간다"는 신호({@code seedRank})를 갖고 있다.
 */
public final class CandidateMerger {

    private CandidateMerger() {
    }

    /**
     * 같은 소스 안의 중복 제거. <b>먼저 만난 것이 이긴다</b> — 호출자가 기본 쿼리 결과를 앞에 두므로
     * 기본 쿼리의 {@code seedRank}가 유지된다.
     *
     * <p><b>{@code seedRank}를 min으로 합치지 않는 이유.</b> 순위는 쿼리 안에서만 의미가 있어
     * "스타일 쿼리의 3위"와 "기본 쿼리의 3위"는 같은 등급이 아니다(설계: 순위 숫자를 점수로 쓰지
     * 않고 표식과 정렬로만 쓴다). 서로 다른 쿼리의 순위를 비교해 작은 쪽을 고르면 그 경고를
     * 정면으로 어긴다.
     *
     * <p>반면 <b>{@code styleTags}는 합집합이고 {@code matchedModifier}는 비어 있을 때만 채운다</b> —
     * 기본 쿼리에도 나오고 {@code "루프탑"} 쿼리에도 나온 카페는 정보가 늘어난 것이지 충돌이 아니다.
     */
    public static List<PlaceCandidate> dedupeWithinSource(List<PlaceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, PlaceCandidate> byKey = new LinkedHashMap<>();
        for (PlaceCandidate candidate : candidates) {
            String key = CandidateMatcher.dedupeKey(candidate.name(), candidate.address());
            byKey.merge(key, candidate, CandidateMerger::absorbDuplicate);
        }
        return List.copyOf(byKey.values());
    }

    /**
     * 시더({@code SEEDED}) 목록과 TourAPI({@code LISTED}) 목록을 합친다.
     *
     * <p>매칭된 쌍은 하나로 합치고 <b>표식은 둘 다 붙인다</b>. 매칭되지 않은 항목은 양쪽 다 풀에
     * 남는다 — 네이버 단독(카페거리·포토스팟)과 TourAPI 단독(작은 사찰·계곡)이 서로를 보완하는
     * <b>이 비대칭이 두 소스를 같이 쓰는 이유</b>다.
     *
     * <p><b>TourAPI 항목 하나는 최대 한 번만 병합된다.</b> 같은 권역의 시드 여러 개가 같은 관광지에
     * 매칭될 수 있는데(별칭·부속 시설), 그대로 두면 같은 장소가 목록에 여러 번 실린다.
     */
    public static List<PlaceCandidate> mergeAcrossSources(List<PlaceCandidate> seeded,
        List<PlaceCandidate> listed) {
        List<PlaceCandidate> seeds = seeded == null ? List.of() : seeded;
        List<PlaceCandidate> officials = listed == null ? List.of() : listed;
        if (officials.isEmpty()) {
            return List.copyOf(seeds);
        }

        boolean[] consumed = new boolean[officials.size()];
        List<PlaceCandidate> merged = new ArrayList<>(seeds.size() + officials.size());
        for (PlaceCandidate seed : seeds) {
            int matched = indexOfMatch(officials, consumed, seed);
            if (matched < 0) {
                merged.add(seed);
                continue;
            }
            consumed[matched] = true;
            merged.add(combine(seed, officials.get(matched)));
        }
        for (int i = 0; i < officials.size(); i++) {
            if (!consumed[i]) {
                merged.add(officials.get(i));
            }
        }
        return List.copyOf(merged);
    }

    private static int indexOfMatch(List<PlaceCandidate> officials, boolean[] consumed,
        PlaceCandidate seed) {
        for (int i = 0; i < officials.size(); i++) {
            if (consumed[i]) {
                continue;
            }
            PlaceCandidate official = officials.get(i);
            if (CandidateMatcher.isSamePlace(
                seed.name(), seed.latitude(), seed.longitude(),
                official.name(), official.latitude(), official.longitude())) {
                return i;
            }
        }
        return -1;
    }

    /** 같은 소스 안에서 같은 장소를 두 번 만났을 때. {@code base}가 먼저 만난 쪽이다. */
    private static PlaceCandidate absorbDuplicate(PlaceCandidate base, PlaceCandidate duplicate) {
        return new PlaceCandidate(
            base.source(),
            base.name(),
            base.address().isBlank() ? duplicate.address() : base.address(),
            base.latitude(),
            base.longitude(),
            base.slotType(),
            union(base.styleTags(), duplicate.styleTags()),
            base.seedRank(),
            // 순위와 한 쌍으로 남긴다(이슈 #113) — 한쪽만 base 를 따르면 좁은 질의의 순위에
            // 넓은 질의의 단계가 붙어, 없애려던 오해를 반대 방향으로 만든다.
            base.seedScope(),
            base.matchedModifier() != null ? base.matchedModifier() : duplicate.matchedModifier(),
            base.distanceKm() != null ? base.distanceKm() : duplicate.distanceKm(),
            base.rawCategory() != null ? base.rawCategory() : duplicate.rawCategory());
    }

    /**
     * 시드 후보와 TourAPI 후보를 한 레코드로.
     *
     * <p><b>이름·좌표·주소는 TourAPI가 이긴다</b>(정식 명칭과 정비된 좌표). 그래서 병합 결과의
     * {@code source}는 {@link CandidateSourceType#LISTED}이고, 그라운딩도 TourAPI 값을 승계한다.
     * "시드에도 들었다"는 사실은 {@code source}가 아니라 {@code seedRank}가 말한다.
     */
    private static PlaceCandidate combine(PlaceCandidate seed, PlaceCandidate official) {
        return new PlaceCandidate(
            CandidateSourceType.LISTED,
            official.name(),
            official.address().isBlank() ? seed.address() : official.address(),
            official.latitude(),
            official.longitude(),
            official.slotType(),
            union(seed.styleTags(), official.styleTags()),
            seed.seedRank(),
            seed.seedScope(),
            seed.matchedModifier(),
            official.distanceKm() != null ? official.distanceKm() : seed.distanceKm(),
            official.rawCategory() != null ? official.rawCategory() : seed.rawCategory());
    }

    private static Set<StyleTag> union(Set<StyleTag> left, Set<StyleTag> right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        Set<StyleTag> merged = EnumSet.copyOf(left);
        merged.addAll(right);
        return Set.copyOf(merged);
    }
}
