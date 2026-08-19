package backend.yourtrip.global.naver;

/**
 * 지역검색 응답 항목을 파이프라인이 쓸 수 있는 형태로 정규화한 것.
 *
 * <p>원문({@code NaverLocalResponse.Item})과 나누는 이유는 <b>정규화가 실패할 수 있기 때문</b>이다 —
 * 좌표 문자열이 비거나 숫자가 아니면 {@code null}이 되는데, 원문 레코드에 그 상태를 섞으면
 * "아직 안 고친 값"과 "고칠 수 없던 값"이 구분되지 않는다.
 *
 * <p><b>좌표는 nullable이다.</b> 1-1에서 {@code Place}의 좌표를 {@code double}에서 {@code Double}로
 * 올린 것과 같은 이유다 — 좌표가 없는 것을 {@code 0.0/0.0}으로 위장하면 아프리카 앞바다에 장소가
 * 생긴다. 좌표 없는 후보는 거리 정렬·dedupe에서 빠지되 후보 자체로는 살아 있다.
 *
 * @param name         {@code <b>} 태그를 걷어낸 상호명
 * @param category     원문 그대로. {@code SlotType} 매핑은 4-4의 순수 함수가 맡는다
 * @param roadAddress  도로명주소. 없으면 빈 문자열일 수 있어 {@link #bestAddress()}로 고른다
 * @param address      지번주소
 * @param link         업소 자체 URL(인스타그램 등)이거나 빈 값. <b>네이버 플레이스 URL이 아니다</b>
 * @param latitude     WGS84. 원문 {@code mapy}를 10⁷로 나눈 값
 * @param longitude    WGS84. 원문 {@code mapx}를 10⁷로 나눈 값
 * @param seedRank     이 쿼리 결과 안에서의 순위(1부터). {@code sort=comment}이므로 곧 인기 순위다
 */
public record NaverPlace(
    String name,
    String category,
    String roadAddress,
    String address,
    String link,
    Double latitude,
    Double longitude,
    int seedRank) {

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    /**
     * dedupe 키에 쓸 주소. 도로명을 우선하되 없으면 지번으로 떨어진다 —
     * 4-5가 "정규화 상호명 + 주소"로 중복을 잡을 때 한쪽만 보면 매칭을 놓친다.
     */
    public String bestAddress() {
        if (roadAddress != null && !roadAddress.isBlank()) {
            return roadAddress;
        }
        return address == null ? "" : address;
    }
}
