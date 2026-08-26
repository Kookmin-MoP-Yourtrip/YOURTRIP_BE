package backend.yourtrip.global.naver;

import backend.yourtrip.global.naver.dto.NaverLocalResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     * 지역검색이 {@code title}에 실어 보내는 HTML 엔티티 (이슈 #147).
     *
     * <p><b>{@code &amp;}가 여기 없다.</b> 그것만 순서를 지켜 마지막에 풀어야 하기 때문이다 —
     * {@link #decodeEntities} 참고.
     */
    private static final Map<String, String> ENTITIES = Map.of(
        "&lt;", "<",
        "&gt;", ">",
        "&quot;", "\"",
        "&#39;", "'");

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
            normalizeTitle(item.title()),
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
     * 상호명 하나를 후보 목록에 실을 형태로 만든다 — <b>태그 제거와 엔티티 디코딩은 한 세트다</b>
     * (이슈 #147).
     *
     * <p><b>순서를 뒤집으면 안 된다.</b> 디코딩을 먼저 하면 원문의 {@code &lt;b&gt;}(강조가 아니라
     * 상호명에 실제로 들어 있는 문자열)가 진짜 태그로 되살아나 스트립 대상이 된다.
     *
     * <p>이 함수 하나가 <b>전 경로를 덮는다</b>. {@code NaverLocalSeedSource}가 여기서 나온 이름을
     * 그대로 {@code PlaceCandidate.name}으로 옮기므로 후보 목록·프롬프트·위조 강등 판정·dedupe
     * 키·그라운딩 승계·저장되는 코스 상호명이 전부 같은 문자열을 쓰게 된다.
     */
    public static String normalizeTitle(String title) {
        return decodeEntities(stripBoldTags(title));
    }

    /**
     * HTML 엔티티를 원래 문자로 되돌린다 (이슈 #147).
     *
     * <h2>고치지 않으면 실존하는 장소를 잃는다</h2>
     * 원문 {@code <b>쉼팡마씸</b> 24시 무인카페 &amp;amp; 4가지 식당}에서 태그만 걷으면 후보 목록에
     * {@code &amp;amp;}가 그대로 실린다. 모델이 그걸 {@code &amp;}로 고쳐 답하면
     * {@code PlaceNameNormalizer}가 {@code &amp;}는 잡음으로 지우고 {@code amp}는 글자로 남겨
     * 두 이름이 어긋나므로 <b>위조로 강등되고</b>, 강등된 이름은 카카오에 없는 표기라 무결과로
     * 탈락한다 — <b>좌표를 이미 확보한 {@code SEEDED} 후보를 잃는 셈이다.</b> 8단계 병합 검증의
     * {@code NO_RESULT} 4건 중 3건이 이것이었다.
     *
     * <h2>{@code &amp;amp;}를 마지막에 푼다</h2>
     * 먼저 풀면 {@code &amp;amp;lt;}가 {@code &amp;lt;}를 거쳐 {@code <}까지 <b>이중 디코딩</b>된다.
     * 앰퍼샌드를 맨 뒤로 미루면 그 경로가 원리적으로 생기지 않는다.
     *
     * <h2>{@code title}에만 쓴다</h2>
     * 주소는 이스케이프되지 않는다는 실측 근거가 있다 — 같은 상호를 응답이 title 은
     * {@code 하루카페&amp;amp;밤떡명가}로, address 는 {@code 하루카페&amp;밤떡명가}로 담는다
     * ({@code docs/tasks/ai-course-create/decisions/artifacts/seed-distance-cap-20260825-after.csv}).
     * 근거 없이 넓히면 주소 안의 {@code &amp;amp;}가 아닌 문자열을 건드릴 위험만 는다.
     */
    public static String decodeEntities(String value) {
        if (value == null) {
            return "";
        }
        String decoded = value;
        for (Map.Entry<String, String> entity : ENTITIES.entrySet()) {
            decoded = decoded.replace(entity.getKey(), entity.getValue());
        }
        return decoded.replace("&amp;", "&");
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
