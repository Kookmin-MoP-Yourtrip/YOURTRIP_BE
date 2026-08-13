package backend.yourtrip.global.ai.route;

import java.util.Set;
import lombok.Getter;

/**
 * 코스의 한 자리가 어떤 종류인지 — 그리고 그 종류가 가지는 배치 정보를 함께 소유한다.
 *
 * <p><b>체류시간과 가중치를 LLM이 아니라 이 enum이 갖는 이유</b>(설계 문서 §5-1). 지금은 LLM이
 * 장소마다 시각을 추측해서 뱉는데, 그건 검증할 수도 없고 요청마다 달라진다. 여기에 값을 박아두면
 * LLM이 내보내야 할 필드가 하나 줄어 <b>스키마 위반 가능성도 하나 줄고</b>, "카페는 60분"이라는
 * 판단이 코드 리뷰 대상이 된다.
 *
 * <p>트레이드오프는 "경복궁은 2시간, 골목 카페는 30분" 같은 장소별 차등을 못 준다는 것이다.
 * 카카오 {@code category_name}으로 사후 보정하면 충분하다고 보고 2차 개선으로 미뤘다.
 *
 * <p><b>이 값들은 세 곳이 읽는다</b> — 3단계(체류시간), 로드맵 4-5(인기도 가중치), 로드맵 5-3
 * (카테고리 하드 제약). 값 하나를 바꾸면 세 곳의 동작이 함께 움직이므로 {@code SlotTypeTest}가
 * 전부 고정해 변경이 리뷰를 거치게 한다.
 *
 * <p>{@link #WALK}(산책로)는 뒤에 추가될 {@code TravelMode.WALK}(뚜벅이)와 이름이 겹치지만 다른
 * 개념이다. 앞의 타입명이 의미를 복원해주므로 이름을 비틀지 않았다.
 */
@Getter
public enum SlotType {

    ATTRACTION(90, "관광명소", 0.2, Set.of("AT4", "CT1")),
    MEAL(75, "맛집", 1.0, Set.of("FD6")),
    CAFE(60, "카페", 1.0, Set.of("CE7")),
    ACTIVITY(120, "체험", 0.6, Set.of("AT4", "CT1")),
    VIEWPOINT(45, "전망대", 0.2, Set.of("AT4")),
    SHOPPING(60, "쇼핑", 0.6, Set.of("MT1", "CS2")),
    WALK(60, "산책로", 0.2, Set.of("AT4"));

    /** 이 종류의 장소에 보통 머무는 시간(분). 시간 모델 {@code t[i] = t[i-1] + 체류 + 이동}의 체류 항. */
    private final int defaultStayMinutes;

    /**
     * Curator가 실패했을 때 카카오 카테고리 검색으로 폴백하기 위한 한국어 검색어(로드맵 7-3).
     * {@code "{area} {searchHint}"} 형태로 조합된다.
     */
    private final String searchHint;

    /**
     * 블로그 언급량(인기도)을 이 슬롯의 랭킹에 얼마나 반영할지(로드맵 4-5).
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
     * <p>현재 {@code KakaoLocalClient.score()}는 이 코드를 가점 +2로만 쓰는데, 로드맵 5-3에서
     * 하드 제약으로 승격한다. 비용이 사실상 0인데 "점심에 호프집"이 구조적으로 사라진다.
     */
    private final Set<String> allowedCategoryCodes;

    SlotType(int defaultStayMinutes, String searchHint, double popularityWeight,
        Set<String> allowedCategoryCodes) {
        this.defaultStayMinutes = defaultStayMinutes;
        this.searchHint = searchHint;
        this.popularityWeight = popularityWeight;
        // enum 상수는 애플리케이션 전역에 하나뿐이라 여기서 새는 참조는 영구적으로 샌다.
        this.allowedCategoryCodes = Set.copyOf(allowedCategoryCodes);
    }
}
