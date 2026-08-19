package backend.yourtrip.global.tour;

/**
 * TourAPI 응답 항목을 파이프라인이 쓸 수 있는 형태로 정규화한 것 (ROADMAP 4-7).
 *
 * <p>{@code NaverPlace}와 같은 자리에 있는 타입이고 같은 규칙을 따른다 — <b>좌표는 nullable</b>이고
 * 못 읽은 것을 {@code 0.0/0.0}으로 위장하지 않는다.
 *
 * <h2>{@code NaverPlace}와 합치지 않는 이유</h2>
 * 두 소스가 주는 정보가 다르다. TourAPI는 <b>분류({@code cat3})와 거리</b>를 주고 인기 순위를 주지
 * 못하며, 네이버는 <b>{@code seedRank}(리뷰 수 순위)</b>를 주고 분류 체계를 주지 못한다. 합치면 한쪽
 * 필드가 항상 비는 레코드가 되어 "없는 값"과 "그 소스가 못 주는 값"이 구분되지 않는다. 두 목록을
 * 하나로 병합하는 일은 5단계 후보 모델(4-5의 매칭 키를 쓴다)이 맡는다.
 *
 * @param contentId     TourAPI 고유 ID. 4-5 dedupe에서 <b>TourAPI 내부</b> 중복 판정의 기준이다
 * @param contentTypeId 12 관광지 / 14 문화시설 / 28 레포츠
 * @param cat1          대분류(A01 자연 / A02 인문 / A03 레포츠)
 * @param cat2          중분류
 * @param cat3          소분류. <b>4-9 스타일 태그 사전의 입력</b>
 * @param address       {@code addr1 + addr2}를 합친 주소
 * @param distanceMeters 조회 좌표로부터의 거리(m). API가 계산해 준 값이라 우리가 재지 않는다
 * @param imageUrl      대표 이미지. 없으면 {@code null}
 */
public record TourPlace(
    String contentId,
    String contentTypeId,
    String title,
    String cat1,
    String cat2,
    String cat3,
    String address,
    Double latitude,
    Double longitude,
    Double distanceMeters,
    String imageUrl) {

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
