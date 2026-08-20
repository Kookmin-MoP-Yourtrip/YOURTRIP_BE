package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.GeoUtils;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.NaverLocalResult;
import backend.yourtrip.global.naver.NaverPlace;
import java.util.ArrayList;
import java.util.List;
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
     * {@code "{area} [{modifier}] {searchHint}"}로 한 번 물어 상위 5건을 후보로 만든다.
     *
     * @param modifier        스타일 수식어. null이면 기본 쿼리
     * @param anchorLatitude  권역 중심 좌표. null이면 {@code distanceKm}을 채우지 않는다
     */
    public CandidateBatch fetch(String area, SlotType slotType, StyleTag modifier,
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
     */
    static String buildQuery(String area, SlotType slotType, StyleTag modifier) {
        StringBuilder query = new StringBuilder();
        if (area != null && !area.isBlank()) {
            query.append(area.strip()).append(' ');
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
        for (NaverPlace place : places) {
            // 좌표 없는 후보는 풀에 넣어봐야 RouteOptimizer 에 못 들어간다. 거르는 책임이
            // 소스에 있다는 것이 PlaceCandidate 의 계약이다.
            if (!place.hasCoordinates()) {
                withoutCoordinates++;
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
        if (withoutCoordinates > 0) {
            log.debug("좌표 없는 네이버 후보 {}건을 제외했다: slot={}", withoutCoordinates, slotType);
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
