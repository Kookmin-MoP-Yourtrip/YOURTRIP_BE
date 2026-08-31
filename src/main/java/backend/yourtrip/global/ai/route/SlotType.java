package backend.yourtrip.global.ai.route;

import java.util.Set;
import lombok.Getter;

/**
 * 코스의 한 자리가 어떤 종류인지 — 그리고 그 종류가 가지는 배치 정보를 함께 소유한다.
 *
 * <p><b>체류시간과 가중치를 LLM이 아니라 이 enum이 갖는 이유</b>(SlotType 설계). 지금은 LLM이
 * 장소마다 시각을 추측해서 뱉는데, 그건 검증할 수도 없고 요청마다 달라진다. 여기에 값을 박아두면
 * LLM이 내보내야 할 필드가 하나 줄어 <b>스키마 위반 가능성도 하나 줄고</b>, "카페는 60분"이라는
 * 판단이 코드 리뷰 대상이 된다.
 *
 * <p>트레이드오프는 "경복궁은 2시간, 골목 카페는 30분" 같은 장소별 차등을 못 준다는 것이다.
 * 카카오 {@code category_name}으로 사후 보정하면 충분하다고 보고 2차 개선으로 미뤘다.
 *
 * <p><b>이 값들을 읽는 곳</b> — 3단계(체류시간), 로드맵 4-1·5-8(후보 공급 검색어), 로드맵 5-3
 * (카테고리 제약). 인기도 가중치만은 <b>V1에서 읽는 곳이 없다</b>(아래). 값 하나를 바꾸면
 * 여러 곳의 동작이 함께 움직이므로 {@code SlotTypeTest}가 전부 고정해 변경이 리뷰를 거치게 한다.
 *
 * <p>{@link #WALK}(산책로)는 뒤에 추가될 {@code TravelMode.WALK}(뚜벅이)와 이름이 겹치지만 다른
 * 개념이다. 앞의 타입명이 의미를 복원해주므로 이름을 비틀지 않았다.
 */
@Getter
/**
 * <h2>상수 이름은 자기 라벨과 일치시킨다</h2>
 * 처음에는 {@code ACTIVITY}(체험)·{@code WALK}(산책로)였는데, <b>이름과 라벨이 어긋난 그 둘이
 * 정확히 다른 enum과 충돌하던 둘</b>이었다.
 *
 * <ul>
 *   <li>{@code WALK} — 여기서는 "산책로"인데 {@code KeywordType.WALK}·{@code TravelMode.WALK}는
 *       "뚜벅이"(이동수단)다. 셋 중 <b>이 enum만 뜻이 달랐다</b></li>
 *   <li>{@code ACTIVITY} — 여기서는 "체험"인데 {@code KeywordType.ACTIVITY}·{@code StyleTag.ACTIVITY}는
 *       "액티비티"다</li>
 * </ul>
 *
 * <p>자바 타입 시스템이 enum 간 대입을 막아 주므로 컴파일 오류로는 이어지지 않지만,
 * <b>이름으로 매핑하는 코드</b>({@code SlotType.valueOf(keyword.name())} 같은)와 코드를 읽는 사람에게는
 * 함정이다. 라벨과 이름을 맞추는 규칙 하나로 두 충돌이 함께 사라진다.
 *
 * <p>남은 동명 상수({@code SHOPPING}·{@code CULTURE}·{@code NATURE}·{@code ACTIVITY})는
 * <b>같은 개념을 다른 층에서 부르는 것</b>이라 그대로 둔다 — 사용자의 취향({@code KeywordType})과
 * 장소의 속성({@code StyleTag})은 뜻이 어긋나지 않는다.
 */
public enum SlotType {

    ATTRACTION(90, 150, "관광명소", 0.2, Set.of("AT4", "CT1")),
    MEAL(75, 90, "맛집", 1.0, Set.of("FD6")),
    CAFE(60, 90, "카페", 1.0, Set.of("CE7")),
    /** 이름이 {@code ACTIVITY}였으나 {@code KeywordType}·{@code StyleTag}의 "액티비티"와 충돌해 바꿨다. */
    EXPERIENCE(120, 180, "체험", 0.6, Set.of("AT4", "CT1")),
    VIEWPOINT(45, 60, "전망대", 0.2, Set.of("AT4")),
    SHOPPING(60, 90, "쇼핑", 0.6, Set.of("MT1", "CS2")),
    /** 이름이 {@code WALK}였으나 {@code KeywordType}·{@code TravelMode}의 "뚜벅이"와 충돌해 바꿨다. */
    STROLL(60, 90, "산책로", 0.2, Set.of("AT4"));

    /** 이 종류의 장소에 보통 머무는 시간(분). 시간 모델 {@code t[i] = t[i-1] + 체류 + 이동}의 체류 항. */
    private final int defaultStayMinutes;

    /**
     * 이 종류의 장소에 머물 수 있다고 보는 상한(분). 체류를 점이 아니라 <b>구간
     * {@code [기본, 최대]}</b>로 정의하는 값이다(이슈 #135).
     *
     * <p>{@code RouteOptimizer}의 탄력 체류(stretch)가 읽는다 — 식사가 시간창보다 이르게
     * 도착하면 그 격차를 앞 슬롯들의 체류 확대로 흡수하는데, 어디까지 늘려도 어색하지 않은지를
     * 이 값이 정한다. 산정 기준은 5슬롯(관광·식사·카페·관광·식사) 최악 케이스다: 동일 좌표
     * 가정에서 저녁 도착이 15:30이라 격차가 120분인데, 저녁 앞 여력(식사 15 + 카페 30 +
     * 관광 60)과 점심 앞 잔여 여력 35를 합치면 140분이라 <b>저녁 17:30 정각을 맞출 수 있다.</b>
     * 상한을 이보다 좁히면 이른 저녁이 잔여 위반으로 남고, 넓히면 "카페에 2시간" 같은
     * 어색한 체류가 나온다.
     */
    private final int maxStayMinutes;

    /**
     * 후보 공급 검색어. {@code "{area} {searchHint}"} 형태로 조합해 네이버 지역검색에 던지고
     * (로드맵 4-1·5-8), Curator 실패 시 카카오 카테고리 검색 폴백에도 같은 조합을 쓴다(로드맵 7-3).
     */
    private final String searchHint;

    /**
     * 블로그 언급량(인기도)을 이 슬롯의 랭킹에 얼마나 반영할지.
     *
     * <p><b>V1에서는 아무도 읽지 않는다.</b> 이 값을 쓰는 {@code PlaceSignalStage}(3·4층)가 V1에서
     * 빠져 로드맵 9-2 조건부가 됐기 때문이다 — 인기도는 사후 랭킹이 아니라 후보 공급의 시딩으로
     * 앞에서 반영된다. 그럼에도 남겨두는 것은 9단계를 켤 때 같은 값이 그대로 쓰이기 때문이다.
     *
     * <p><b>중요도가 아니라 신호의 신뢰도다.</b> 식당·카페는 블로그 수가 실제 변별력을 갖지만
     * 관광명소는 "경복궁"의 블로그 수가 많다는 사실이 아무것도 말해주지 않아 0.2로 낮다.
     * 이걸 중요도로 오독해 드롭 우선순위에 쓰면 <b>관광지를 버리고 카페를 남기게 된다</b> —
     * {@code RouteOptimizer}가 별도의 {@code DROP_ORDER}를 갖는 이유다.
     */
    private final double popularityWeight;

    /**
     * 이 슬롯에 들어올 수 있는 카카오 {@code category_group_code}.
     *
     * <p>{@code PlaceMatchScorer.score()}는 이 코드를 가점 +2로만 쓰므로 "점심에 호프집"을 막지
     * 못했다. 5-3이 {@code GroundingStage}에 제약으로 올렸고, 이슈 #147이 그것을 <b>하드 드롭에서
     * 슬롯 전멸 시 최후 구제로</b> 낮췄다 — 어긋난 후보를 버리면 그 슬롯에 다른 후보가 없을 때
     * 저녁이 통째로 비기 때문이다. 제약은 그대로 서 있고 <b>차순위가 있을 때만</b> 걸러 낸다.
     */
    private final Set<String> allowedCategoryCodes;

    SlotType(int defaultStayMinutes, int maxStayMinutes, String searchHint,
        double popularityWeight, Set<String> allowedCategoryCodes) {
        this.defaultStayMinutes = defaultStayMinutes;
        this.maxStayMinutes = maxStayMinutes;
        this.searchHint = searchHint;
        this.popularityWeight = popularityWeight;
        // enum 상수는 애플리케이션 전역에 하나뿐이라 여기서 새는 참조는 영구적으로 샌다.
        this.allowedCategoryCodes = Set.copyOf(allowedCategoryCodes);
    }
}
