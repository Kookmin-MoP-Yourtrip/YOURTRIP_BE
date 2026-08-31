package backend.yourtrip.global.tour;

import backend.yourtrip.global.common.ApiFailureCause;
import java.util.List;

/**
 * TourAPI 호출 1회의 결과 (ROADMAP 4-7). <b>실패를 예외가 아니라 값으로 돌려준다.</b>
 *
 * <p>{@code NaverLocalResult}와 같은 형태이고 같은 이유다 — 5-8의 fail-open이 요구한다.
 * {@link Empty}와 {@link Failed}를 가르는 것이 핵심인 것도 같다: <b>"그 권역에 등록된 관광지가
 * 없다"와 "물어보지 못했다"</b>는 다른 사건이고, 뭉치면 TourAPI 장애가 "관광지 없는 지역"으로 보인다.
 *
 * <h2>결과 타입을 세 클라이언트가 공유하지 않는 이유</h2>
 * 실패 <b>사유</b> 어휘는 {@link ApiFailureCause}로 공유하지만 결과 타입 자체는 각자 둔다.
 * {@code PlaceLookup}의 {@code Found}는 장소 하나이고 여기와 네이버는 목록이며, "비었다"의 의미도
 * 다르다({@code NoResult}는 "이름이 맞는 게 없다", {@code Empty}는 "그 지역에 없다"). 제네릭 하나로
 * 묶으면 호출부마다 와일드카드가 붙고, 무엇보다 <b>각 소스에서 왜 비는 것이 실패와 다른지를 적어 둔
 * 이 javadoc이 갈 곳을 잃는다.</b> 공유하는 것은 어휘지 구조가 아니다.
 */
public sealed interface TourApiResult {

    /** 결과가 있다. {@code places}는 1건 이상이고 거리 오름차순이다(arrange=E). */
    record Found(List<TourPlace> places) implements TourApiResult {

        public Found {
            places = List.copyOf(places);
        }
    }

    /** 호출은 성공했으나 반경 안에 등록된 항목이 0건이다. */
    record Empty() implements TourApiResult {

    }

    /** 물어보지 못했다. 관광 슬롯의 LISTED 후보만 비고 코스 생성은 계속된다(fail-open). */
    record Failed(ApiFailureCause cause, String detail) implements TourApiResult {

    }

    static TourApiResult of(List<TourPlace> places) {
        return places.isEmpty() ? new Empty() : new Found(places);
    }
}
