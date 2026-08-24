package backend.yourtrip.global.ai.candidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 후보 풀의 중복 정리 (ROADMAP 5-8). <b>순수 함수</b>라 스테이지에서 떼어내 결정론적으로 테스트한다.
 *
 * <p>중복은 세 층에서 생기고, 층마다 규칙이 다르다.
 *
 * <ol>
 *   <li><b>같은 소스 안</b> — 기본 쿼리와 스타일 modifier 쿼리가 같은 가게를 물어온다.
 *       provider가 하나라 {@code 정규화 이름 + 주소} 등가 키로 충분하다</li>
 *   <li><b>소스 간</b>(관광 슬롯) — 대릉원은 네이버에도 TourAPI에도 있다. 여기는 좌표와 이름을
 *       <b>둘 다</b> 봐야 한다({@link CandidateMatcher#isSamePlace})</li>
 *   <li><b>본체와 부속</b>(이슈 #106) — {@code 영주댐전망대}와 {@code 영주댐전망대주차장1}은
 *       <b>이름이 달라</b> ①에 안 걸리고 <b>소스가 같아</b> ②도 타지 않는다.
 *       {@link #collapseSubordinates}가 ①·② 뒤에 한 번 더 훑는다</li>
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

    /**
     * 본체와 부속을 하나로 접는다 (이슈 #106). <b>①·② 뒤에 마지막으로 돈다.</b>
     *
     * <h2>왜 별도 단계인가</h2>
     * {@code 영주댐전망대 ↔ 영주댐전망대주차장1}(99m)·{@code 갑사 ↔ 공주 갑사 철당간}(196m)은
     * 5-9 실측에서 <b>둘 다 목록에 남았다.</b> 이름이 달라 등가 키를 통과하고, 둘 다 TourAPI라
     * 소스 간 병합의 대상도 아니다. 손해는 두 가지다 — cap 25의 한 칸을 먹고, Curator가 자리마다
     * 골라야 하는 <b>"서로 대체할 수 있는 세 개"라는 전제가 깨진다</b>(본체가 그라운딩에서
     * 탈락하면 99m 옆의 부속도 함께 위태롭다).
     *
     * <h2>{@code dedupeWithinSource}를 고치지 않는 이유</h2>
     * 그 함수는 {@code NaverLocalSeedSource}에서 <b>캐스케이드 재질의의 발동 조건</b>으로도 쓰인다
     * ({@code merged.size() >= fallback.minCandidates()}). 거기서 건수를 더 줄이면 재질의가 더
     * 자주 발동해, 이슈 #106과 무관한 동작 변경이 된다.
     *
     * <h2>어느 쪽을 남기는가 — 짧은 쪽이 본체다</h2>
     * <b>부속 이름은 본체 이름에 수식이 붙은 것이라 언제나 더 길다</b>
     * ({@code 갑사} ⊊ {@code 공주갑사철당간}). 진포함을 조건으로 삼은 이상 길이 차이가 항상
     * 있으므로, <b>길이만으로 승자가 정해진다.</b>
     *
     * <p><b>부속 어휘({@code 주차장}·{@code 입구}) 목록은 두지 않는다.</b> 이슈 #106은 그 어휘로
     * 버릴 쪽을 고르는 안을 함께 올렸지만, {@code A ⊊ B}이면 {@code B}가 {@code A}를 통째로
     * 품으므로 <b>어휘까지 함께 갖는다</b> — 어휘 점수가 같아져 결국 길이가 정한다. 어느 조합을
     * 따져도 결과가 같아, 목록을 둬도 하는 일이 없다.
     *
     * <p><b>이 규칙은 짝이 잡혔을 때만 발동한다</b> — 본체 없이 홀로 있는 주차장은 그대로 남는다.
     * "이름이 부속스러우니 지운다"가 아니라 "본체가 옆에 있으니 중복이다"라는 판단이다.
     *
     * <p><b>버리지 않고 흡수한다.</b> {@link #absorbDuplicate}를 그대로 쓰므로 부속이 갖고 있던
     * {@code styleTags}는 본체에 합쳐진다.
     *
     * <p><b>돌려주는 순서는 입력 순서다.</b> 훑는 차례(본체 자격 순)와 내보내는 차례를 분리하지
     * 않으면 {@code mergeAcrossSources}가 세운 "시드 먼저"가 무너져 {@code CandidateOrdering}의
     * 결과가 흔들린다.
     */
    public static List<PlaceCandidate> collapseSubordinates(List<PlaceCandidate> candidates) {
        return collapseSubordinates(candidates, (host, absorbed) -> {
        });
    }

    /**
     * 접힌 쌍을 관측할 수 있는 오버로드.
     *
     * <p><b>사라진 것을 셀 수 없으면 이 규칙은 검수할 수 없다.</b> 조립이 끝난 목록에는 본체만
     * 남아 "무엇이 왜 빠졌는가"를 되짚을 방법이 없으므로, 접는 순간에 훅을 준다. 5-9 오탈락
     * 검수가 이 훅으로 쌍을 모으고, {@code CandidateRetrievalStage}는 같은 훅으로 로그를 남긴다.
     *
     * @param onCollapse {@code (본체, 흡수된 부속)}. 흡수 <b>전</b>의 본체가 넘어간다
     */
    public static List<PlaceCandidate> collapseSubordinates(List<PlaceCandidate> candidates,
        BiConsumer<PlaceCandidate, PlaceCandidate> onCollapse) {
        if (candidates == null || candidates.size() < 2) {
            return candidates == null ? List.of() : List.copyOf(candidates);
        }

        // ① 본체가 될 자격 순으로 훑을 차례. 인덱스를 들고 다녀야 ③에서 입력 순서로 되돌린다.
        List<Integer> visitOrder = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            visitOrder.add(i);
        }
        visitOrder.sort(Comparator
            .comparingInt(
                (Integer i) -> PlaceNameNormalizer.normalize(candidates.get(i).name()).length())
            // 동점을 이름으로 깬다 — 같은 입력에 같은 목록이 나가야 6-7의 listIndex 검증이 재현된다.
            .thenComparing(i -> candidates.get(i).name()));

        // ② 앞선 본체에 걸리면 흡수되고, 아니면 자신이 본체가 된다. 짧은 쪽을 먼저 보므로
        //    A ⊊ B ⊊ C 체인도 가장 짧은 A 하나로 모인다.
        Map<Integer, PlaceCandidate> primaries = new LinkedHashMap<>();
        for (int index : visitOrder) {
            PlaceCandidate candidate = candidates.get(index);
            Integer host = findHost(primaries, candidate);
            if (host == null) {
                primaries.put(index, candidate);
                continue;
            }
            PlaceCandidate primary = primaries.get(host);
            onCollapse.accept(primary, candidate);
            primaries.put(host, absorbDuplicate(primary, candidate));
        }

        // ③ 입력 순서로 복원.
        return primaries.keySet().stream()
            .sorted()
            .map(primaries::get)
            .toList();
    }

    /** @return 이 후보를 부속으로 삼는 본체의 인덱스. 없으면 {@code null} */
    private static Integer findHost(Map<Integer, PlaceCandidate> primaries,
        PlaceCandidate candidate) {
        for (Map.Entry<Integer, PlaceCandidate> entry : primaries.entrySet()) {
            PlaceCandidate primary = entry.getValue();
            if (CandidateMatcher.isSubordinate(
                primary.name(), primary.latitude(), primary.longitude(),
                candidate.name(), candidate.latitude(), candidate.longitude())) {
                return entry.getKey();
            }
        }
        return null;
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
