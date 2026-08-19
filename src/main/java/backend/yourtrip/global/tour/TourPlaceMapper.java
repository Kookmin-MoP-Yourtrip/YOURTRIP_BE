package backend.yourtrip.global.tour;

import backend.yourtrip.global.tour.dto.TourApiResponse;
import backend.yourtrip.global.tour.dto.TourApiResponse.Item;
import java.util.ArrayList;
import java.util.List;

/**
 * TourAPI 응답 원문 → {@link TourPlace} 변환 (ROADMAP 4-7). <b>순수 함수만 둔다.</b>
 *
 * <p>클라이언트에서 떼어 두는 이유는 {@code NaverPlaceMapper}와 같다 — 변환은 실패할 수 있고
 * (좌표가 비거나 숫자가 아니다) 그 분기는 HTTP 없이 단위 테스트로 고정돼야 한다.
 *
 * <h2>좌표를 나누지 않는다</h2>
 * 네이버는 {@code mapx}/{@code mapy}가 WGS84 × 10⁷ 정수 문자열이라 나눠야 하지만, TourAPI는
 * <b>평문 십진 도</b>다({@code mapx=129.2095707739}). 4-7 실호출로 확인했고, 같은 이름의 필드가
 * 소스마다 다른 형식이라 <b>두 매퍼를 합치면 안 되는 이유</b>이기도 하다.
 */
public final class TourPlaceMapper {

    private TourPlaceMapper() {
    }

    /**
     * 응답 전체를 장소 목록으로 바꾼다.
     *
     * <p>응답이 {@code null}이거나 {@code items}가 비면 빈 목록이다 — 0건일 때 TourAPI가
     * {@code "items": ""}를 주기 때문에 실제로 자주 지나는 경로다.
     */
    public static List<TourPlace> toPlaces(TourApiResponse response) {
        List<TourPlace> places = new ArrayList<>();
        if (response == null || response.response() == null
            || response.response().body() == null
            || response.response().body().items() == null
            || response.response().body().items().item() == null) {
            return places;
        }

        for (Item item : response.response().body().items().item()) {
            if (item == null) {
                continue;
            }
            places.add(toPlace(item));
        }
        return places;
    }

    /**
     * 항목 하나를 정규화한다. <b>좌표를 못 읽어도 버리지 않는다</b> — 좌표 없는 후보는 거리
     * 정렬·dedupe에서 빠지되 목록에는 남는다({@code NaverPlace}와 같은 규칙).
     */
    public static TourPlace toPlace(Item item) {
        return new TourPlace(
            trimToNull(item.contentid()),
            trimToNull(item.contenttypeid()),
            trimToEmpty(item.title()),
            trimToNull(item.cat1()),
            trimToNull(item.cat2()),
            trimToNull(item.cat3()),
            joinAddress(item.addr1(), item.addr2()),
            parseCoordinate(item.mapy()),
            parseCoordinate(item.mapx()),
            parseCoordinate(item.dist()),
            trimToNull(item.firstimage()));
    }

    /**
     * {@code addr2}는 상세주소(동·호수)이고 비어 오는 경우가 대부분이다. 4-5가 주소로 중복을
     * 잡으므로 <b>공백 하나가 붙어 다니는 주소</b>가 생기지 않게 합칠 때 정리한다.
     */
    private static String joinAddress(String addr1, String addr2) {
        String first = trimToEmpty(addr1);
        String second = trimToEmpty(addr2);
        if (second.isEmpty()) {
            return first;
        }
        if (first.isEmpty()) {
            return second;
        }
        return first + " " + second;
    }

    /** @return 비었거나 숫자가 아니면 {@code null} */
    private static Double parseCoordinate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
