package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.GeoUtils;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.NaverLocalResult;
import backend.yourtrip.global.naver.NaverPlace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     * {@code "{검색 가능한 지명} [{modifier}] {searchHint}"}로 물어 상위 5건을 후보로 만든다.
     * 권역 라벨을 지명으로 줄이는 것은 {@link AreaQueryNormalizer}다(이슈 #110).
     *
     * <h2>0건이면 지명을 넓혀 가며 다시 묻는다</h2>
     * 표기 정규화만으로는 <b>상권이 없는 지명이 권역명일 때</b> 살아나지 않는다 — 실측에서
     * {@code 송산리 고분군} 권역의 MEAL·CAFE가 0건이었다. 그래서 넓은 지명으로 차례로 물어본다.
     *
     * <p><b>{@code anchor}를 {@code location}보다 먼저 두는 것이 요점이다.</b> 실측에서 area 질의가
     * 0건이던 4칸 중 <b>3칸이 {@code anchor}에서 살아났고</b>, 그렇게 살아난 후보는 권역 안에
     * 머문다(거리 중앙값 0.61km). 곧장 {@code location}으로 넓히면 같은 칸이 채워지긴 해도
     * <b>거리 중앙값이 6.2km</b>로 열 배가 되어 day 단위 권역이 사실상 무의미해진다.
     *
     * <p>지오코딩이 {@code anchor → area → location}으로 내려가는 것과 같은 형태다 — <b>이 저장소가
     * 권역을 다루는 방식이 캐스케이드라는 것</b>이 여기서 한 번 더 쓰인다.
     *
     * @param fallbackAreas 0건일 때 차례로 시도할 지명. <b>{@code anchor} → {@code location} 순서</b>로
     *                      넣는다. 이미 물어본 지명과 겹치면 건너뛴다(60%는 정규화 결과가
     *                      {@code anchor}와 같아서, 그대로 두면 같은 질의를 두 번 던진다).
     *                      비어 있으면 재질의하지 않는다
     * @param modifier      스타일 수식어. null이면 기본 쿼리
     * @param anchorLatitude 권역 중심 좌표. null이면 {@code distanceKm}을 채우지 않는다
     */
    public CandidateBatch fetch(String area, List<String> fallbackAreas, SlotType slotType,
        StyleTag modifier, Double anchorLatitude, Double anchorLongitude) {
        List<String> terms = searchTerms(area, fallbackAreas, modifier);

        CandidateBatch batch = CandidateBatch.empty();
        for (int step = 0; step < terms.size(); step++) {
            String term = terms.get(step);
            if (step > 0) {
                log.debug("권역 '{}' 의 {} 후보가 0건이라 '{}' 로 다시 묻는다", area, slotType, term);
            }
            batch = searchOnce(term, slotType, modifier, anchorLatitude, anchorLongitude);
            // HIT 이면 끝이고, FAILED 여도 멈춘다 — "물어봤는데 없더라"와 "물어보지 못했다"는
            // 다른 사건이라, 후자에 재질의를 걸면 네이버 장애 때 호출만 배로 늘어난다(4-1).
            if (batch.outcome() != CandidateOutcome.EMPTY) {
                return batch;
            }
        }
        return batch;
    }

    /**
     * 차례로 물어볼 지명 목록.
     *
     * <p><b>modifier 쿼리는 재질의하지 않는다.</b> 부가 축이라 기본이 0건이면 그쪽도 0건일 가능성이
     * 높고, 전부 재질의하면 호출이 배로 늘면서 얻는 것은 거의 없다.
     */
    private static List<String> searchTerms(String area, List<String> fallbackAreas,
        StyleTag modifier) {
        String primary = AreaQueryNormalizer.toSearchTerm(area);
        List<String> onlyPrimary = List.of(primary == null ? "" : primary);
        if (modifier != null || fallbackAreas == null || fallbackAreas.isEmpty()) {
            return onlyPrimary;
        }

        Map<String, String> byKey = new LinkedHashMap<>();
        putIfNew(byKey, primary);
        for (String fallback : fallbackAreas) {
            putIfNew(byKey, AreaQueryNormalizer.toSearchTerm(fallback));
        }
        return byKey.isEmpty() ? onlyPrimary : List.copyOf(byKey.values());
    }

    /**
     * 아직 안 물어본 지명이면 목록에 더한다.
     *
     * <p>공백과 대소문자를 무시하고 비교한다 — {@code 송산리 고분군}과 {@code 송산리고분군}은 같은
     * 곳을 가리키므로 둘 다 던질 이유가 없다.
     */
    private static void putIfNew(Map<String, String> byKey, String term) {
        if (term == null || term.isBlank()) {
            return;
        }
        byKey.putIfAbsent(term.replace(" ", "").toLowerCase(Locale.ROOT), term);
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
