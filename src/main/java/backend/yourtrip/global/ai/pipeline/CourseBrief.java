package backend.yourtrip.global.ai.pipeline;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.route.TravelMode;
import java.util.List;
import java.util.Objects;

/**
 * 파이프라인 입력 — 설계가 그림의 출발점에 놓은 값이다.
 *
 * <p><b>요청 DTO를 그대로 받지 않는 이유.</b> {@code AICourseCreateRequest}는
 * {@code domain.mycourse}의 타입이고, 파이프라인은 {@code global.ai}에 있다. 요청 DTO를 여기까지
 * 끌고 들어오면 의존 방향이 뒤집히고, 날짜(→ {@code days}) 계산 같은 도메인 규칙이 파이프라인
 * 안으로 새어 들어온다. 변환은 8단계가 컨트롤러 쪽에서 한다.
 *
 * @param days       여행 일수. <b>LLM 호출 수({@code 1 + days})이자 Curator 병렬 팬아웃</b>이다
 * @param travelMode {@code RouteOptimizer}의 이동시간 계산 입력. {@code null}이면
 *                   {@link TravelMode#UNSPECIFIED}
 */
public record CourseBrief(
    String location,
    int days,
    List<KeywordType> keywords,
    TravelMode travelMode
) {

    public CourseBrief {
        Objects.requireNonNull(location, "location 은 필수다 — 모든 검색어의 접두사가 된다");
        if (days <= 0) {
            throw new IllegalArgumentException("days 는 1 이상이어야 한다: " + days);
        }
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        travelMode = travelMode == null ? TravelMode.UNSPECIFIED : travelMode;
    }

    /**
     * 이동수단을 <b>키워드에서 읽어</b> 조립한다.
     *
     * <p>사용자가 고른 {@code WALK}(뚜벅이)·{@code CAR}(자차)는 {@code travelMode} 카테고리의
     * 키워드로 이미 요청에 실려 온다. 그런데 그 값을 {@link TravelMode}로 옮기는 자리가 지금까지
     * 없었다 — 파이프라인이 처음으로 {@code RouteOptimizer}를 부르는 곳이라, 여기가 그 자리다.
     * 옮기지 않으면 뚜벅이 여행도 시속 15km로 계산돼 <b>이동시간이 실제와 어긋난 채 시각이 확정된다.</b>
     *
     * <p>둘 다 골랐으면 {@link TravelMode#UNSPECIFIED}다. 모순을 임의로 한쪽으로 풀면 그 판단이
     * 코드 안에 숨고, 중간값이 두 경우 모두에서 크게 틀리지 않는다.
     */
    public static CourseBrief of(String location, int days, List<KeywordType> keywords) {
        return new CourseBrief(location, days, keywords, travelModeOf(keywords));
    }

    private static TravelMode travelModeOf(List<KeywordType> keywords) {
        if (keywords == null) {
            return TravelMode.UNSPECIFIED;
        }
        boolean walk = keywords.contains(KeywordType.WALK);
        boolean car = keywords.contains(KeywordType.CAR);
        if (walk == car) {
            return TravelMode.UNSPECIFIED;
        }
        return walk ? TravelMode.WALK : TravelMode.CAR;
    }
}
