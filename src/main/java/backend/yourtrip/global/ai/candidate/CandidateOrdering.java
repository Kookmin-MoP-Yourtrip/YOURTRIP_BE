package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Curator에게 넘길 후보 목록의 정렬과 cap (ROADMAP 5-8). <b>순수 함수</b>다.
 *
 * <h2>정렬이 실제로 결정하는 것은 "누가 목록에 들어가는가"다</h2>
 * {@link #DEFAULT_CAP}에서 자르는데 <b>TourAPI 하나만도 슬롯당 50건까지 준다</b>
 * ({@code TourApiClient.MAX_ROWS}). 그러니 순서는 앞뒤 배치이기 전에 <b>정원 배분</b>이고, 이건
 * LLM이 목록을 어떻게 읽든 성립한다 — 보여주지 않은 후보는 고를 수가 없다.
 *
 * <p>여기에 <b>재현성</b>이 붙는다. 같은 입력에 같은 목록이 나가야 6-7의 {@code listIndex} 검증과
 * 5-9 실측이 재현된다(5단계 판정 8이 스타일 태그 순서로 같은 문제를 겪었다).
 *
 * <h2>"LLM이 앞쪽을 더 고른다"에는 기대지 않는다</h2>
 * 설계는 그 위치 편향을 <b>이용한다</b>고 적었지만 <b>이 저장소에서 확인된 적이 없다.</b> 순서만
 * 섞어 같은 질문을 두 번 던진 실험에서 1순위 일치율이 55.6%로 <b>사전에 못 박은 미판정 구간</b>에
 * 들었고(6단계 판정 12), 섞은 목록에서 {@code #18}·{@code #22}를 고른 사례도 나왔다. 순서가 선택을
 * 흔들기는 하지만 <b>통제 가능한 지렛대라기보다 잡음에 가깝다.</b>
 *
 * <p>그래서 인기도·거리 같은 <b>판단 근거는 순서가 아니라 각 줄의 텍스트가 나른다</b>
 * ({@code CandidateListRenderer}). 이 클래스는 그 줄들을 <b>몇 개까지, 어떤 차례로</b> 실을지만 정한다.
 *
 * <p>정렬이 <b>가중치 합이 아니라 사전식</b>인 이유도 여기서 나온다 — 계수를 두면 "그 값이 왜
 * 0.3인가"에 답할 근거가 필요한데, 정원 배분에는 그런 근거가 필요 없다. 튜닝할 계수가 하나도 없다.
 *
 * <table>
 *   <tr><th>순서</th><th>그룹</th><th>그룹 안 정렬</th></tr>
 *   <tr><td>①</td><td>시드에 든 후보 (병합됐든 네이버 단독이든)</td><td>{@code seedRank} 오름차순</td></tr>
 *   <tr><td>②</td><td>시드에 없지만 {@code styleTags}가 사용자 키워드와 맞는 후보</td><td>{@code distanceKm} 오름차순</td></tr>
 *   <tr><td>③</td><td>나머지</td><td>{@code distanceKm} 오름차순</td></tr>
 *   <tr><td>④</td><td>MEAL 슬롯의 술집 계열 (5-3)</td><td>같은 기준</td></tr>
 * </table>
 *
 * <p><b>술집을 버리지 않고 맨 뒤로 미는 이유.</b> 설계가 요구한 것은 하드 제약이 아니라 <b>감점</b>
 * 이다("점심에 호프집 방지") — 그런데 슬롯에는 시각 정보가 없어(배치는 RouteOptimizer가 나중에
 * 한다) 점심 자리와 저녁 자리를 가를 수 없다. 하드 드롭하면 저녁 술집까지 죽는다. 후순위로 밀면
 * 다른 MEAL 후보가 있을 때는 그쪽이 뽑히고, 그 슬롯에 술집밖에 없으면 여전히 쓰인다 —
 * <b>보조 신호를 필수 조건으로 승격시키지 않는다</b>는 원칙 그대로다.
 *
 * <p><b>cap에 걸려 잘리는 건 여전히 ③의 먼 곳이다.</b> 토큰 상한 때문에 가까운 후보가 누락되지는
 * 않고, 그래서 "cap을 몇으로 할 것인가"가 튜닝 문제가 되지 않는다.
 *
 * <p><b>다만 정원의 일부가 권역 밖으로 나간다</b>(이슈 #113, 6단계 판정 12). 지명 캐스케이드(이슈
 * #110)가 도시 전역 질의까지 내려가면 그 후보도 {@code seeded()}라 ①에 들어오는데, 이 정렬은
 * <b>아직 그 경우를 구별하지 않는다.</b> 실측에서 관광 슬롯 하나가 25칸 중 5칸을 그런 후보에게
 * 내줬고, 그중에는 같은 슬롯에 0.1km 후보가 있는데도 14.6km 떨어진 곳이 있었다.
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
    private static final int GROUP_DEPRIORITIZED = 4;

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
        // 시드 여부보다 먼저 본다 — 그러지 않으면 seed 1위 술집이 목록 맨 앞을 차지한다.
        if (isDeprioritized(candidate)) {
            return GROUP_DEPRIORITIZED;
        }
        if (candidate.seeded()) {
            return GROUP_SEEDED;
        }
        if (!preferredTags.isEmpty() && !Collections.disjoint(candidate.styleTags(), preferredTags)) {
            return GROUP_STYLE_MATCHED;
        }
        return GROUP_REST;
    }

    /** 식사 자리에 온 술집 계열. 4-4가 "매핑이 아니라 표시로 다룬다"고 남겨 둔 판정을 여기서 쓴다. */
    private static boolean isDeprioritized(PlaceCandidate candidate) {
        return candidate.slotType() == SlotType.MEAL
            && NaverCategoryMapper.isBarLike(candidate.rawCategory());
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
