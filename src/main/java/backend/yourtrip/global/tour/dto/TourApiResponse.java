package backend.yourtrip.global.tour.dto;

import java.util.List;

/**
 * TourAPI {@code locationBasedList2} 응답 원문 (ROADMAP 4-7).
 *
 * <p>필드 이름은 <b>API가 주는 그대로</b> 둔다. TourAPI는 {@code contenttypeid}·{@code mapx}처럼
 * 소문자로 붙여 쓰므로 자바 관례와 어긋나지만, 이름을 바꾸면 매핑 애너테이션이 필요해지고 응답
 * 원문과 코드를 나란히 놓고 볼 수 없게 된다. <b>정규화는 {@code TourPlaceMapper}가 한다.</b>
 *
 * <h2>{@code items}는 0건일 때 빈 문자열로 온다</h2>
 * {@code {"items": "", "totalCount": 0}} — 객체가 아니다. {@code TourApiConfig}가
 * {@code ACCEPT_EMPTY_STRING_AS_NULL_OBJECT}로 이것을 {@code null}로 흡수하므로 여기서는
 * {@code items}가 {@code null}일 수 있다고만 알면 된다.
 */
public record TourApiResponse(Response response) {

    public record Response(Header header, Body body) {

    }

    /**
     * <b>실패가 여기로도 온다.</b> HTTP 200이면서 {@code resultCode}가 {@code 0000}이 아닌 응답이
     * 존재하므로, 상태코드만 보는 클라이언트는 실패를 성공으로 읽는다.
     */
    public record Header(String resultCode, String resultMsg) {

        public static final String OK = "0000";

        public boolean isOk() {
            return OK.equals(resultCode);
        }
    }

    public record Body(Items items, Integer numOfRows, Integer pageNo, Integer totalCount) {

    }

    public record Items(List<Item> item) {

    }

    /**
     * @param mapx WGS84 <b>경도</b>. 평문 십진 도 문자열이다 — 네이버의 10⁷ 정수와 다르다
     * @param mapy WGS84 <b>위도</b>
     * @param dist 조회 좌표로부터의 거리(m). <b>API가 계산해 준다</b>
     * @param cat3 3단계 분류의 소분류. 4-9 스타일 태그 사전의 입력이다
     */
    public record Item(
        String contentid,
        String contenttypeid,
        String title,
        String addr1,
        String addr2,
        String cat1,
        String cat2,
        String cat3,
        String mapx,
        String mapy,
        String dist,
        String firstimage,
        String firstimage2,
        String tel) {

    }
}
