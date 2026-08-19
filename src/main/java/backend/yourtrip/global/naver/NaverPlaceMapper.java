package backend.yourtrip.global.naver;

import backend.yourtrip.global.naver.dto.NaverLocalResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 지역검색 응답 원문 → {@link NaverPlace} 변환. <b>순수 함수만 둔다.</b>
 *
 * <p>클라이언트 안에 두지 않고 떼어낸 이유는 여기가 실제로 틀리기 쉬운 곳이고, 떼어내야 외부 호출
 * 없이 단위 테스트할 수 있기 때문이다(4-6). {@code GeoUtils}를 route 타입과 분리한 것과 같은 취급이다.
 */
public final class NaverPlaceMapper {

    /**
     * 검색어 매칭 강조 태그. 지역검색은 {@code title}에만 이 태그를 넣는다
     * ({@code 두낫디스터브 <b>경주</b>본점}).
     */
    private static final Pattern BOLD_TAG = Pattern.compile("</?b>", Pattern.CASE_INSENSITIVE);

    /**
     * 좌표 스케일. {@code mapx}/{@code mapy}는 WGS84를 10⁷배한 정수 문자열이다(4-2 실측).
     *
     * <p><b>자릿수로 소수점을 끼워 넣으면 안 된다</b> — 경도는 10자리({@code 1292092884}), 위도는
     * 9자리({@code 358363900})라 같은 규칙으로 자르면 위도가 10배로 튄다. 정수로 읽어 나눈다.
     */
    private static final double COORDINATE_SCALE = 1e7;

    private NaverPlaceMapper() {
    }

    /**
     * 응답을 순위가 매겨진 후보 목록으로 바꾼다.
     *
     * <p>{@code seedRank}는 <b>응답 순서 그대로 1부터</b> 매긴다. 요청이 {@code sort=comment}이므로
     * 이 순서가 곧 리뷰 수 순위이고, 설계가 말한 "시드 순위 자체가 인기도 순위"가 여기서 성립한다.
     */
    public static List<NaverPlace> toPlaces(NaverLocalResponse response) {
        if (response == null || response.items() == null) {
            return List.of();
        }
        List<NaverPlace> places = new ArrayList<>(response.items().size());
        int rank = 1;
        for (NaverLocalResponse.Item item : response.items()) {
            places.add(toPlace(item, rank++));
        }
        return places;
    }

    static NaverPlace toPlace(NaverLocalResponse.Item item, int seedRank) {
        return new NaverPlace(
            stripBoldTags(item.title()),
            nullToEmpty(item.category()),
            nullToEmpty(item.roadAddress()),
            nullToEmpty(item.address()),
            nullToEmpty(item.link()),
            parseCoordinate(item.mapy()),
            parseCoordinate(item.mapx()),
            seedRank);
    }

    /**
     * {@code <b>} 태그를 걷어낸다. 스트립하지 않으면 상호명 비교가 전부 어긋나고, 그 상호명이
     * 그대로 사용자 코스에 저장된다.
     */
    public static String stripBoldTags(String title) {
        if (title == null) {
            return "";
        }
        return BOLD_TAG.matcher(title).replaceAll("").trim();
    }

    /**
     * 좌표 문자열을 WGS84 도(degree)로 바꾼다.
     *
     * <p><b>실패는 예외가 아니라 {@code null}이다.</b> 좌표 하나가 깨졌다고 후보 목록 전체를 버리면
     * fail-open 원칙에 어긋난다 — 좌표 없는 후보는 거리 정렬에서만 빠지고 후보로는 살아남는다.
     */
    public static Double parseCoordinate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim()) / COORDINATE_SCALE;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
