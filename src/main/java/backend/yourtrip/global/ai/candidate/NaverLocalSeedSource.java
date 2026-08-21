package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.GeoUtils;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.NaverLocalResult;
import backend.yourtrip.global.naver.NaverPlace;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 네이버 지역검색 시더를 후보로 바꾸는 어댑터 — <b>전 슬롯의 인기 축</b> (ROADMAP 5-8).
 *
 * <p><b>호출 하나 = 반환 하나다.</b> 여러 쿼리를 묶어 던지거나 병렬로 돌리는 것은 이 클래스가
 * 하지 않는다 — 병렬화·데드라인·집계는 {@code CandidateRetrievalStage}의 일이고, 여기는 "쿼리
 * 하나를 후보 목록으로 바꾼다"만 안다. 그래야 스텁 테스트가 쿼리 단위로 단순해진다.
 *
 * <p><b>카카오로 다시 묻지 않는다.</b> 지역검색 응답에 좌표·주소·카테고리가 이미 들어 있으므로
 * 그대로 그라운딩 데이터로 쓴다 — 상호명으로 카카오에 다시 사러 가는 것은 후보가 전부
 * 파라메트릭이라 좌표를 얻을 곳이 카카오뿐이던 시절의 잔재다(설계).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NaverLocalSeedSource {

    private final NaverLocalClient naverLocalClient;

    /**
     * 후보 하나의 검색 단계. <b>지명이 넓어질수록 쓰는 기준이 엄격해진다.</b>
     *
     * @param area           넓혀서 물어볼 지명({@code anchor} 또는 사용자가 입력한 여행지)
     * @param minCandidates  현재 확보한 후보가 이 수에 못 미칠 때만 이 단계를 탄다.
     *                       {@code anchor}는 3(Curator가 슬롯당 3개를 고른다), {@code location}은
     *                       1(0건일 때만) — <b>근거는 실측이다.</b> anchor 로 채운 후보는 거리
     *                       중앙값 1.07km 로 1단계(1.20km)와 다르지 않은데, location 으로 채우면
     *                       6.34km 로 다섯 배가 되어 day 를 권역으로 나눈 의미가 사라진다
     */
    public record Fallback(String area, int minCandidates) {

        public Fallback {
            if (minCandidates < 1) {
                throw new IllegalArgumentException(
                    "minCandidates는 1 이상이어야 한다(1이면 0건일 때만): " + minCandidates);
            }
        }
    }

    /**
     * {@code "{검색 가능한 지명} [{modifier}] {searchHint}"}로 물어 상위 5건을 후보로 만든다.
     * 권역 라벨을 지명으로 줄이는 것은 {@link AreaQueryNormalizer}다(이슈 #110).
     *
     * <h2>후보가 모자라면 지명을 넓혀 가며 <b>더 모은다</b></h2>
     * 표기 정규화만으로는 <b>상권이 없는 지명이 권역명일 때</b> 살아나지 않는다 — 실측에서
     * {@code 송산리 고분군} 권역의 MEAL·CAFE 가 0건이었다. 게다가 0건은 아니어도 <b>3건에 못 미치는
     * 칸이 24%</b>였는데, 그런 슬롯에서는 Curator 가 3순위를 실존 후보로 채우지 못한다.
     *
     * <p><b>단계마다 기준이 다르다.</b> {@code anchor}는 3건에 못 미치면 타고, {@code location}은
     * 0건일 때만 탄다({@link Fallback#minCandidates()}). 넓은 지명일수록 데려오는 후보가 멀어지기
     * 때문이다 — anchor 추가분 1.07km 대 location 추가분 6.34km.
     *
     * <p><b>겹치는 장소는 {@link CandidateMerger#dedupeWithinSource}가 합친다.</b> 앞 단계를 먼저
     * 넣으므로 <b>{@code seedRank}는 좁은 지명의 것이 남고</b> {@code styleTags}는 합집합이 된다 —
     * "먼저 만난 쪽이 이긴다"가 곧 "가까운 질의의 순위가 남는다"가 되게 하는 순서다.
     *
     * @param fallbacks 0건이거나 모자랄 때 차례로 시도할 단계. <b>{@code anchor} → {@code location}
     *                  순서</b>로 넣는다. 이미 물어본 지명은 건너뛴다(관측 30쌍 중 18쌍이 정규화
     *                  결과와 {@code anchor}가 같아, 그대로 두면 같은 질의를 두 번 던진다).
     *                  비어 있으면 재질의하지 않는다
     * @param modifier  스타일 수식어. <b>null이 아니면 재질의하지 않는다</b> — 부가 축이라 기본이
     *                  모자라면 그쪽도 모자랄 가능성이 높고, 전부 재질의하면 호출만 배로 늘어난다
     * @param anchorLatitude 권역 중심 좌표. null이면 {@code distanceKm}을 채우지 않는다
     */
    public CandidateBatch fetch(String area, List<Fallback> fallbacks, SlotType slotType,
        StyleTag modifier, Double anchorLatitude, Double anchorLongitude) {
        Set<String> asked = new LinkedHashSet<>();
        String primary = AreaQueryNormalizer.toSearchTerm(area);
        markAsked(asked, primary);

        CandidateBatch batch =
            searchOnce(primary, slotType, modifier, anchorLatitude, anchorLongitude);
        if (modifier != null || batch.outcome() == CandidateOutcome.FAILED
            || fallbacks == null || fallbacks.isEmpty()) {
            // FAILED 는 "물어보지 못했다"라 재질의 대상이 아니다 — "물어봤는데 없더라"와 다른
            // 사건이고, 여기에 재질의를 걸면 네이버 장애 때 호출만 배로 늘어난다(4-1).
            return batch;
        }

        List<PlaceCandidate> merged =
            CandidateMerger.dedupeWithinSource(batch.candidates());
        for (Fallback fallback : fallbacks) {
            if (merged.size() >= fallback.minCandidates()) {
                break;
            }
            String term = AreaQueryNormalizer.toSearchTerm(fallback.area());
            if (!markAsked(asked, term)) {
                continue;
            }
            log.debug("권역 '{}' 의 {} 후보가 {}건뿐이라 '{}' 로 더 모은다",
                area, slotType, merged.size(), term);

            CandidateBatch next =
                searchOnce(term, slotType, modifier, anchorLatitude, anchorLongitude);
            if (next.outcome() == CandidateOutcome.FAILED) {
                // 여기까지 모은 것은 살린다. 실패를 돌려주면 앞 단계의 성과까지 버리게 된다.
                log.debug("'{}' 재질의가 실패했다 — 지금까지 모은 {}건으로 진행한다", term, merged.size());
                break;
            }
            merged = CandidateMerger.dedupeWithinSource(concat(merged, next.candidates()));
        }
        return CandidateBatch.of(merged);
    }

    /**
     * 아직 안 물어본 지명이면 표시하고 {@code true}.
     *
     * <p>공백과 대소문자를 무시하고 비교한다 — {@code 송산리 고분군}과 {@code 송산리고분군}은 같은
     * 곳을 가리키므로 둘 다 던질 이유가 없다.
     */
    private static boolean markAsked(Set<String> asked, String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        return asked.add(term.replace(" ", "").toLowerCase(Locale.ROOT));
    }

    private static List<PlaceCandidate> concat(List<PlaceCandidate> left,
        List<PlaceCandidate> right) {
        List<PlaceCandidate> all = new ArrayList<>(left.size() + right.size());
        all.addAll(left);
        all.addAll(right);
        return all;
    }

    private CandidateBatch searchOnce(String area, SlotType slotType, StyleTag modifier,
        Double anchorLatitude, Double anchorLongitude) {
        String query = buildQuery(area, slotType, modifier);
        NaverLocalResult result = naverLocalClient.search(query, NaverLocalClient.MAX_DISPLAY);

        return switch (result) {
            case NaverLocalResult.Found found -> CandidateBatch.of(
                toCandidates(found.places(), slotType, modifier, anchorLatitude, anchorLongitude));
            case NaverLocalResult.Empty ignored -> CandidateBatch.empty();
            case NaverLocalResult.Failed failed -> CandidateBatch.failed(failed.cause());
        };
    }

    /**
     * 검색어 조립. <b>어순은 4-3 실측(3라운드·122회)으로 확정한 표기를 그대로 쓴다</b> —
     * {@code "황리단길 루프탑 카페"}처럼 수식어가 슬롯 힌트 앞에 온다.
     *
     * <p><b>{@code area}를 그대로 붙이지 않는다.</b> Planner가 내는 권역 라벨은 사람이 읽는
     * 문자열이라({@code "황리단길·대릉원 일대"}) 그대로 검색하면 <b>0건이 된다</b> — 실측에서
     * 12권역 × 3슬롯의 95%가 빈 결과였다(이슈 #110). {@link AreaQueryNormalizer}가 검색 가능한
     * 지명으로 줄인다. <b>{@code PlannerDayPlan.area} 필드 자체는 바꾸지 않는다</b> — Curator
     * 프롬프트와 로그가 읽는 값이라 사람이 읽는 형태로 남아야 한다.
     */
    static String buildQuery(String area, SlotType slotType, StyleTag modifier) {
        StringBuilder query = new StringBuilder();
        String searchTerm = AreaQueryNormalizer.toSearchTerm(area);
        if (searchTerm != null && !searchTerm.isBlank()) {
            query.append(searchTerm).append(' ');
        }
        if (modifier != null) {
            modifier.searchTerm().ifPresent(term -> query.append(term).append(' '));
        }
        return query.append(slotType.getSearchHint()).toString();
    }

    private static List<PlaceCandidate> toCandidates(List<NaverPlace> places, SlotType slotType,
        StyleTag modifier, Double anchorLatitude, Double anchorLongitude) {
        List<PlaceCandidate> candidates = new ArrayList<>(places.size());
        int withoutCoordinates = 0;
        int categoryMismatched = 0;
        for (NaverPlace place : places) {
            // 좌표 없는 후보는 풀에 넣어봐야 RouteOptimizer 에 못 들어간다. 거르는 책임이
            // 소스에 있다는 것이 PlaceCandidate 의 계약이다.
            if (!place.hasCoordinates()) {
                withoutCoordinates++;
                continue;
            }
            // 슬롯 힌트로 물었는데 다른 업종이 온 경우(5-3). 풀에 넣으면 Curator 입력 토큰만
            // 먹고, 골라지면 "카페 자리에 주유소"가 된다. 매핑에 없는 분류는 통과시킨다 —
            // 하드 드롭은 매핑이 아는 것에만 건다(4-4).
            if (!NaverCategoryMapper.isCompatibleWith(place.category(), slotType)) {
                categoryMismatched++;
                continue;
            }
            candidates.add(new PlaceCandidate(
                CandidateSourceType.SEEDED,
                place.name(),
                place.bestAddress(),
                place.latitude(),
                place.longitude(),
                slotType,
                // 스타일 쿼리에서 왔다는 것은 "검색이 그렇게 주장했다"는 힌트일 뿐 검증된
                // 속성이 아니다. 그 검증을 맡을 자리였던 4층은 V1에서 빠졌다.
                modifier == null ? Set.of() : Set.of(modifier),
                place.seedRank(),
                modifier,
                distanceKm(place, anchorLatitude, anchorLongitude),
                place.category()));
        }
        if (withoutCoordinates > 0 || categoryMismatched > 0) {
            log.debug("네이버 후보 제외: slot={}, 좌표없음={}건, 분류불일치={}건",
                slotType, withoutCoordinates, categoryMismatched);
        }
        return candidates;
    }

    private static Double distanceKm(NaverPlace place, Double anchorLatitude,
        Double anchorLongitude) {
        if (anchorLatitude == null || anchorLongitude == null) {
            return null;
        }
        return GeoUtils.haversineKm(anchorLatitude, anchorLongitude,
            place.latitude(), place.longitude());
    }
}
