package backend.yourtrip.global.ai.candidate;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Curator에게 넘길 후보 목록의 정렬과 cap (ROADMAP 5-8). <b>순수 함수</b>다.
 *
 * <p><b>목록의 순서 자체가 신호다.</b> LLM은 앞쪽 항목을 더 자주 고르는 위치 편향이 있는데, 이걸
 * 억누르지 않고 쓴다 — 우리가 우선 보이길 원하는 순서로 주면 점수 계산 없이도 인기도가 선별에
 * 반영된다. 그래서 정렬은 <b>가중치 합이 아니라 사전식</b>이고, 튜닝할 계수가 하나도 없다.
 *
 * <table>
 *   <tr><th>순서</th><th>그룹</th><th>그룹 안 정렬</th></tr>
 *   <tr><td>①</td><td>시드에 든 후보 (병합됐든 네이버 단독이든)</td><td>{@code seedRank} 오름차순</td></tr>
 *   <tr><td>②</td><td>시드에 없지만 {@code styleTags}가 사용자 키워드와 맞는 후보</td><td>{@code distanceKm} 오름차순</td></tr>
 *   <tr><td>③</td><td>나머지</td><td>{@code distanceKm} 오름차순</td></tr>
 * </table>
 *
 * <p><b>cap에 걸려 잘리는 건 항상 ③의 먼 곳이다.</b> 토큰 상한 때문에 중요한 후보가 누락되는 일이
 * 구조적으로 없다는 뜻이고, 이것이 "cap을 몇으로 할 것인가"를 튜닝 문제로 만들지 않는다.
 */
public final class CandidateOrdering {

    /**
     * Curator 입력 토큰 상한에서 온 값 (설계는 "20~25"). 관광 슬롯의 병합 후 목록에만 실제로
     * 걸린다 — MEAL/CAFE는 provider가 네이버 하나라 슬롯당 8~15건에 그친다.
     */
    public static final int DEFAULT_CAP = 25;

    private static final int GROUP_SEEDED = 1;
    private static final int GROUP_STYLE_MATCHED = 2;
    private static final int GROUP_REST = 3;

    private CandidateOrdering() {
    }

    public static List<PlaceCandidate> order(List<PlaceCandidate> candidates,
        Set<StyleTag> preferredTags) {
        return order(candidates, preferredTags, DEFAULT_CAP);
    }

    public static List<PlaceCandidate> order(List<PlaceCandidate> candidates,
        Set<StyleTag> preferredTags, int cap) {
        if (cap <= 0) {
            throw new IllegalArgumentException("cap은 1 이상이어야 한다: " + cap);
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<StyleTag> preferred = preferredTags == null ? Set.of() : preferredTags;

        List<PlaceCandidate> sorted = candidates.stream()
            .sorted(Comparator
                .comparingInt((PlaceCandidate candidate) -> group(candidate, preferred))
                .thenComparingDouble(CandidateOrdering::withinGroupKey)
                // 동점을 이름으로 깬다 — 같은 입력에 같은 목록이 나가야 6-7의 listIndex 검증과
                // 5-9 실측이 재현된다.
                .thenComparing(PlaceCandidate::name))
            .toList();

        return sorted.size() <= cap ? sorted : List.copyOf(sorted.subList(0, cap));
    }

    private static int group(PlaceCandidate candidate, Set<StyleTag> preferredTags) {
        if (candidate.seeded()) {
            return GROUP_SEEDED;
        }
        if (!preferredTags.isEmpty() && !Collections.disjoint(candidate.styleTags(), preferredTags)) {
            return GROUP_STYLE_MATCHED;
        }
        return GROUP_REST;
    }

    /**
     * 그룹 안 정렬 키. <b>그룹마다 단위가 다르지만 안전하다</b> — 그룹을 먼저 비교하므로 순위(1~5)와
     * 거리(km)가 서로 비교되는 일이 없다.
     *
     * <p>{@code distanceKm}이 null인 후보(anchor 좌표를 못 얻은 day)는 맨 뒤로 보낸다. 거리를 모르는
     * 것을 0으로 취급하면 아무 근거 없이 목록 맨 앞을 차지한다.
     */
    private static double withinGroupKey(PlaceCandidate candidate) {
        if (candidate.seeded()) {
            return candidate.seedRank();
        }
        return candidate.distanceKm() == null ? Double.MAX_VALUE : candidate.distanceKm();
    }
}
