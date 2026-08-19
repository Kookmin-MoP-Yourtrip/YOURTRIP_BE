package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.PlaceLookup;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * day 권역의 중심 좌표를 구한다 (ROADMAP 4-8). <b>TourAPI 조회의 입력을 만드는 단계다.</b>
 *
 * <h2>왜 지오코딩이 필요한가</h2>
 * TourAPI는 텍스트 지역명을 받지 않는다. 지역으로 받는 방식은 시도·시군구 코드({@code areaBasedList2})와
 * 좌표+반경({@code locationBasedList2}) 둘인데 설계가 <b>후자</b>를 택했다 — 전자는 코드표·이름 별칭
 * 매칭이 필요하고, 속초·양양처럼 시군구 경계에 걸친 권역을 못 다루며, 결국 중심점 거리로 다시 걸러야
 * 한다. 좌표 기반은 day별 권역 필터가 조회 자체에 내장된다.
 *
 * <h2>왜 {@code area}를 그냥 지오코딩하지 않는가</h2>
 * {@code area}는 {@code "황리단길·대릉원 일대"} 같은 사람이 읽는 라벨이라 가운뎃점·"일대"가 붙어
 * 검색 결과가 흔들린다. 그래서 Planner가 그 권역 안의 <b>구체적 랜드마크 하나</b>({@code anchor},
 * 예: "대릉원")를 함께 내고, {@code "{location} {anchor}"}를 검색한다.
 *
 * <h2>캐스케이드</h2>
 * <pre>
 *   ① "{location} {anchor}"   → HIT
 *   ② "{location} {area}"     → FALLBACK_AREA
 *   ③ "{location}"            → FALLBACK_LOCATION
 * </pre>
 * <b>무결과면 다음 단계로, 호출 실패면 즉시 중단한다.</b> 같은 API가 죽었는데 문자열만 바꿔 두 번 더
 * 두드릴 이유가 없다 — 쿼터만 쓰고 같은 실패를 받는다. 이 둘을 가르기 위해 {@link PlaceLookup}이
 * 존재한다.
 *
 * <p>①②는 <b>LLM이 지어낸 이름</b>이라 이름 게이트를 통과해야 하고, ③은 사용자가 입력한 여행지라
 * 게이트 없이 카카오 1등을 받는다({@link KakaoLocalClient#lookupFirstPlace} 참고).
 *
 * <h2>캐시는 여기서 붙이지 않는다</h2>
 * 설계는 {@code anchor} 텍스트를 키로 TTL 30일 캐시를 두라고 하지만, 캐시는 10단계 소관이다.
 * 이 클래스가 순수하게 "부르고 고르는" 일만 하고 있어야 그때 데코레이터로 감싸기 쉽다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AreaGeocoder {

    private final KakaoLocalClient kakaoLocalClient;

    /**
     * 권역 중심 좌표를 구한다.
     *
     * @param location 사용자가 입력한 여행지("경주시"). <b>비면 물어볼 것이 없어 바로 무결과다</b>
     * @param area     Planner가 낸 day 권역 라벨("황리단길·대릉원 일대"). 없으면 건너뛴다
     * @param anchor   Planner가 낸 권역 안의 랜드마크("대릉원"). 없으면 건너뛴다
     * @return 좌표와 {@link GeocodeOutcome}. 실패해도 예외를 던지지 않는다(fail-open)
     */
    public GeocodeResult geocode(String location, String area, String anchor) {
        if (location == null || location.isBlank()) {
            log.warn("지오코딩 생략 — 여행지가 비어 있다");
            return GeocodeResult.noResult();
        }

        // 순서가 곧 캐스케이드다. 앞의 것일수록 권역을 좁게 짚는다.
        List<Attempt> attempts = List.of(
            new Attempt(anchor, GeocodeOutcome.HIT),
            new Attempt(area, GeocodeOutcome.FALLBACK_AREA),
            new Attempt(location, GeocodeOutcome.FALLBACK_LOCATION));

        for (Attempt attempt : attempts) {
            if (attempt.query() == null || attempt.query().isBlank()) {
                // 물어볼 말이 없는 단계는 호출 없이 건너뛴다. 클라이언트에 맡기면 안 된다 —
                // 거기서는 접두사가 붙어 "경주시 "가 되므로 빈 문자열로 보이지 않는다.
                continue;
            }
            boolean isLastResort = attempt.outcome() == GeocodeOutcome.FALLBACK_LOCATION;
            PlaceLookup lookup = isLastResort
                ? kakaoLocalClient.lookupFirstPlace(location)
                : kakaoLocalClient.lookupBestPlace(attempt.query(), location);

            if (lookup instanceof PlaceLookup.Failed failed) {
                log.warn("지오코딩 중단 — 카카오 호출 실패: location={}, cause={}, detail={}",
                    location, failed.cause(), failed.detail());
                return GeocodeResult.failed();
            }
            if (lookup instanceof PlaceLookup.Found found) {
                GeocodeResult resolved = toResult(found.document(), attempt.outcome());
                if (resolved != null) {
                    return resolved;
                }
                // 좌표를 못 읽은 경우다. 찾긴 찾았으나 쓸 수 없으므로 다음 단계로 넘어간다 —
                // 중단할 일은 아니다(카카오는 멀쩡하다).
                log.warn("지오코딩 결과에 좌표가 없다: query={}, place={}",
                    attempt.query(), found.document().place_name());
            }
        }

        log.info("지오코딩 무결과 — 그 day의 TourAPI를 건너뛴다: location={}", location);
        return GeocodeResult.noResult();
    }

    /** @return 좌표를 읽지 못하면 {@code null}(= 다음 단계로 넘어가라) */
    private static GeocodeResult toResult(Document document, GeocodeOutcome outcome) {
        Double longitude = parse(document.x());
        Double latitude = parse(document.y());
        if (latitude == null || longitude == null) {
            return null;
        }
        return GeocodeResult.resolved(latitude, longitude, outcome);
    }

    /**
     * 카카오는 좌표를 문자열로 준다. 형식이 어긋나면 {@code null}이다 —
     * {@code NaverPlaceMapper}가 같은 이유로 같은 선택을 했다.
     */
    private static Double parse(String coordinate) {
        if (coordinate == null || coordinate.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(coordinate.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 캐스케이드 한 단계. {@code query}가 비면 위 반복문이 호출 없이 넘어간다. */
    private record Attempt(String query, GeocodeOutcome outcome) {

    }
}
