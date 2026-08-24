package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.GeoUtils;

/**
 * 후보 중복 판정 (ROADMAP 4-5). <b>순수 함수만 둔다.</b>
 *
 * <h2>중복이 두 종류다</h2>
 * <ol>
 *   <li><b>같은 소스 안의 쿼리 간 중복</b> — MEAL·CAFE·SHOPPING은 provider가 네이버 하나라,
 *       기본 쿼리와 스타일 modifier 쿼리가 같은 가게를 함께 물어 온다.
 *       {@link #dedupeKey}로 잡는다</li>
 *   <li><b>소스 간 같은 장소</b> — 관광 슬롯은 시더(네이버)와 TourAPI가 같은 곳을 각자 준다.
 *       {@link #isSamePlace}로 잡는다</li>
 * </ol>
 * 둘을 한 함수로 만들지 않는 이유는 <b>쓸 수 있는 재료가 다르기</b> 때문이다. 같은 소스끼리는
 * 주소 표기가 글자까지 같아서 등가 키로 충분하지만, 소스가 다르면 정식 명칭과 상호명이 달라
 * 등가 비교가 성립하지 않는다.
 *
 * <h2>관광 슬롯에 좌표와 이름을 <b>둘 다</b> 보는 근거 — 실측이 있다</h2>
 * 설계는 *"좌표만 쓰면 대릉원 안의 천마총이 합쳐지고, 이름만 쓰면 전국의 향교가 합쳐진다"* 고
 * 적었다. 4-7 표본(경주 황리단길·부산 해운대·삼척 죽서루 각 관광지 50건, 쌍 3,675개)에서 이것을
 * 실제로 쟀다({@code TourApiProbeTest}의 [4-7j]로 재현할 수 있다).
 *
 * <pre>
 *   300m 이내인 쌍            38건
 *     그중 이름까지 비슷한 쌍    1건
 *     이름이 다른 쌍           37건  ← 좌표만으로 합치면 전부 오합침
 * </pre>
 *
 * <b>97%가 오합침</b>이다. 실제로 천마총(대릉원)과 경주 쌈밥거리는 62m, 금관총과 천마총은 284m
 * 떨어져 있는 <b>서로 다른 장소</b>다. 좌표 단독 규칙은 이 표본에서 성립하지 않는다.
 *
 * <h2>임계값 300m는 아직 잠정이다</h2>
 * 설계 초안 값을 그대로 두되, 위 측정은 <b>"좌표만으로는 안 된다"를 보였을 뿐 "300m가 옳다"를
 * 보인 것이 아니다.</b> 소스 간 좌표 차이의 실제 분포는 시더와 TourAPI를 함께 돌려야 나오므로
 * <b>5-9 후보 공급 실측에서 조정한다.</b> 상수 하나만 고치면 되도록 여기 모아 둔다.
 */
public final class CandidateMatcher {

    /**
     * 같은 장소로 볼 수 있는 최대 거리(km). <b>잠정값이고 5-9에서 조정한다</b>(위 javadoc 참고).
     *
     * <p>이 값을 키우면 이웃한 유적이 합쳐지고, 줄이면 같은 장소가 좌표 오차만큼 갈라져 후보에
     * 두 번 오른다. 어느 쪽이 나쁜지는 명확하다 — <b>합쳐서 사라지는 쪽이 더 나쁘다.</b>
     * 후보가 둘로 남으면 Curator가 하나를 고르지만, 잘못 합쳐지면 고를 기회 자체가 없다.
     */
    public static final double PROXIMITY_THRESHOLD_KM = 0.3;

    private CandidateMatcher() {
    }

    /**
     * 같은 소스 안에서 중복을 잡는 등가 키.
     *
     * <p><b>이름만으로는 부족하다</b> — 프랜차이즈 지점명이 빠진 상호가 흔하다. 주소를 함께 넣어야
     * "황리단길 스타벅스"와 "보문단지 스타벅스"가 갈린다. 반대로 <b>주소만으로도 부족하다</b> —
     * 한 건물에 여러 가게가 있다.
     *
     * @param address {@code NaverPlace.bestAddress()}처럼 도로명 우선으로 고른 값
     * @return 같은 장소면 같은 문자열. {@code HashSet}에 그대로 넣어 쓴다
     */
    public static String dedupeKey(String name, String address) {
        return PlaceNameNormalizer.normalize(name) + "|" + PlaceNameNormalizer.normalize(address);
    }

    /**
     * 소스가 다른 두 후보가 같은 장소인지 판정한다. <b>거리와 이름을 AND로 묶는다.</b>
     *
     * <p><b>좌표가 없으면 합치지 않는다.</b> 거리 조건을 평가할 수 없는데 이름만으로 통과시키면
     * 전국의 향교가 하나가 된다. 못 합쳐서 후보가 둘로 남는 실수가, 다른 장소를 합쳐서 후보 하나가
     * 사라지는 실수보다 낫다 — {@link #PROXIMITY_THRESHOLD_KM}에 적은 것과 같은 비대칭이다.
     *
     * @return 거리 ≤ {@link #PROXIMITY_THRESHOLD_KM} <b>이고</b> 이름이 유사할 때만 {@code true}
     */
    public static boolean isSamePlace(String nameA, Double latitudeA, Double longitudeA,
        String nameB, Double latitudeB, Double longitudeB) {
        if (latitudeA == null || longitudeA == null || latitudeB == null || longitudeB == null) {
            return false;
        }
        if (!PlaceNameNormalizer.similar(nameA, nameB)) {
            return false;
        }
        return distanceKm(latitudeA, longitudeA, latitudeB, longitudeB) <= PROXIMITY_THRESHOLD_KM;
    }

    /**
     * 한쪽이 다른 쪽의 <b>부속</b>인지 판정한다 (이슈 #106).
     * {@code 영주댐전망대주차장1}·{@code 공주 갑사 철당간}처럼 <b>본체와 함께 목록에 실리는</b>
     * 근접 POI를 잡는다.
     *
     * <h3>{@link #isSamePlace}와 무엇이 같고 무엇이 다른가</h3>
     * <b>거리 조건은 그대로 공유한다</b> — {@link #PROXIMITY_THRESHOLD_KM}을 따로 두지 않는 이유는
     * 5-9 판정 10이 유효 구간을 (196m, 440m)로 좁히며 쓴 표본이 <b>바로 갑사 철당간(196m)</b>
     * 이어서다. 상수를 나누면 같은 근거가 두 곳으로 갈라진다.
     *
     * <p><b>이름 조건은 더 엄격하다</b> — {@link PlaceNameNormalizer#properlyContains}로
     * <b>완전 일치를 뺀다.</b> 소스 간 병합은 표기만 다른 같은 장소를 합치는 일이라 완전 일치가
     * 정상 입력이지만, 여기서 완전 일치는 <b>같은 상호의 다른 지점</b>일 수 있다.
     *
     * <p><b>이 판정만으로 후보를 지우지 않는다.</b> 어느 쪽이 본체인지는 호출자
     * ({@link CandidateMerger#collapseSubordinates})가 정하고, 버리는 쪽의 정보는 흡수된다.
     *
     * @return 진포함 <b>이고</b> 거리 ≤ {@link #PROXIMITY_THRESHOLD_KM}일 때만 {@code true}
     */
    public static boolean isSubordinate(String nameA, double latitudeA, double longitudeA,
        String nameB, double latitudeB, double longitudeB) {
        if (!PlaceNameNormalizer.properlyContains(nameA, nameB)) {
            return false;
        }
        return distanceKm(latitudeA, longitudeA, latitudeB, longitudeB) <= PROXIMITY_THRESHOLD_KM;
    }

    /**
     * 두 후보 사이의 거리(km).
     *
     * <p>{@code GeoUtils}를 재사용한다 — 3-2에서 <b>route 타입을 모르는 원시 함수</b>로 만들어 둔
     * 것이 정확히 이 용처다. 거리 계산이 두 곳에 생기면 4-5의 판정과 {@code RouteOptimizer}의
     * 동선 비용이 미세하게 어긋나기 시작한다.
     */
    public static double distanceKm(double latitudeA, double longitudeA,
        double latitudeB, double longitudeB) {
        return GeoUtils.haversineKm(latitudeA, longitudeA, latitudeB, longitudeB);
    }
}
