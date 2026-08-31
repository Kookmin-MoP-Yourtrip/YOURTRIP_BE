package backend.yourtrip.global.tour;

import java.util.Locale;

/**
 * TourAPI 조회 좌표를 <b>약 1km 격자</b>로 뭉치는 순수 함수 (ROADMAP 4-7).
 *
 * <h2>왜 좌표를 그대로 캐시 키로 쓰지 않는가</h2>
 * 지오코딩이 돌려주는 좌표는 소수점 아래가 길어 <b>사실상 매번 다르다.</b> 같은 대릉원을 짚어도
 * 카카오가 1등으로 주는 장소가 조금만 달라지면 키가 갈리고, 캐시 적중률이 0에 수렴한다.
 * 격자로 뭉치면 <b>같은 권역을 본 사용자끼리 캐시를 공유</b>한다 — 설계가 *"인기 권역은 사용자 간
 * 공유되므로 장소 단위 캐시보다 효율이 높다"* 고 적은 근거가 이것이다.
 *
 * <h2>왜 하필 1km인가</h2>
 * 조회 반경이 20km라, 중심이 1km 어긋나도 <b>결과 집합의 차이가 작다</b>(거리순 상위 50건은
 * 실측에서 4.5km 안에 들어왔다). 반대로 격자를 10km로 키우면 서로 다른 day 권역이 한 칸에 뭉쳐
 * 권역 분리가 캐시 단계에서 무너진다.
 *
 * <p>소수 둘째 자리 반올림이 곧 1km다 — 위도 0.01°는 약 1.11km, 경도 0.01°는 북위 36°에서 약
 * 0.90km다. 삼각함수 없이 격자가 만들어지므로 <b>계산이 어긋날 여지 자체가 없다.</b>
 *
 * <h2>캐시는 여기서 붙이지 않는다</h2>
 * 10단계 소관이다. 이 클래스는 키 <b>계산</b>만 갖고 있어 그때 {@code @Cacheable}의 키 표현식이
 * 그대로 부를 수 있다.
 */
public final class TourGridKey {

    /** 격자 한 칸의 크기(도). 0.01° ≈ 1km. */
    private static final double CELL_DEGREES = 0.01;

    private TourGridKey() {
    }

    /**
     * 좌표와 콘텐츠 타입으로 캐시 키를 만든다.
     *
     * @return {@code "35.83:129.21:12"} 형태. 같은 격자·같은 타입이면 같은 문자열이다
     */
    public static String of(double latitude, double longitude, int contentTypeId) {
        return "%s:%s:%d".formatted(cell(latitude), cell(longitude), contentTypeId);
    }

    /**
     * 좌표 한 축을 격자 값으로 뭉친다.
     *
     * <p>{@code Math.round}가 아니라 문자열 포맷으로 자릿수를 고정하는 이유는 <b>-0.0과 부동소수점
     * 표기 흔들림</b>을 없애기 위해서다. {@code 35.834999...}와 {@code 35.835000...}이 같은 칸에
     * 들어가야 하는데, 나눗셈 결과를 그대로 이어붙이면 {@code 3583.0}과 {@code 3583.0000000001}이
     * 다른 키가 된다.
     */
    private static String cell(double degrees) {
        double snapped = Math.floor(degrees / CELL_DEGREES) * CELL_DEGREES;
        return String.format(Locale.ROOT, "%.2f", snapped);
    }
}
