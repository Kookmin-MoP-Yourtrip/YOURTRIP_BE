package backend.yourtrip.global.ai.candidate;

/**
 * 시더 후보가 권역에서 <b>너무 멀어 쓸 수 없는지</b>를 판정한다 (이슈 #134).
 *
 * <h2>왜 시더에만 필요한가</h2>
 * 네이버 지역검색은 <b>텍스트 검색이라 지리적 제약이 없다.</b> TourAPI는 좌표+반경 20km 질의라
 * ({@code TourApiClient}) 구조적으로 먼 후보를 낼 수 없고, 카카오는 후보 소스가 아니다. 그래서
 * 질의가 지역 한정자를 잃으면 <b>시더만</b> 전국의 후보를 그대로 들여온다.
 *
 * <p>그런데 거리로 후보를 탈락시키는 코드가 파이프라인 어디에도 없었다 —
 * {@link PlaceCandidate#distanceKm()}은 {@link CandidateOrdering}의 정렬 키와 LLM에게 보여줄
 * 텍스트로만 쓰이고, {@link CandidateMatcher} 의 근접 임계값은 <b>중복 병합</b> 판정용이라 목적이
 * 다르다. 이 클래스가 그 빈 관문을 메운다.
 *
 * <h2>{@value #MAX_ANCHOR_DISTANCE_KM}km 의 근거 — 실측 (2026-08-25)</h2>
 * {@code SeedDistanceCapProbeTest}가 1,618개 후보를 전수로 재고, 그 판정 과정은
 * {@code docs/tasks/ai-course-create/decisions/시더-거리-상한.md}에 있다.
 *
 * <table>
 *   <caption>거리 분포와 상한 구간</caption>
 *   <tr><th>구분</th><th>값</th><th>사례</th></tr>
 *   <tr><td>정상 후보 최대</td><td><b>35.76km</b></td><td>제주 {@code 델문도}(제주시 조천읍)</td></tr>
 *   <tr><td>차단할 후보 최소</td><td><b>48.15km</b></td><td>공주 {@code 주말농장} 질의 → 충북 청주시</td></tr>
 *   <tr><td>사고 사례</td><td>102 / 138 / 177km</td><td>순천→목포권, 공주→칠곡, 통영→경북권</td></tr>
 * </table>
 *
 * <p><b>이 구간이 비어 있다는 것이 단일 상수를 정당화하는 유일한 사실이다.</b> {@code SeedScope}별·
 * {@code GeocodeOutcome}별로 상수를 쪼개지 않은 이유가 여기 있다 — 쪼갤 필요가 없었다. 실측에서
 * {@code LOCATION} 단계 최대가 30.23km, 넓은 권역(제주·삼척)도 35.76km로 둘 다 상한 아래였고,
 * 지오코딩이 도시명까지 내려간 {@code FALLBACK_LOCATION} day 131행도 마찬가지였다.
 *
 * <p>값은 두 실패 모드에 같은 <b>비율</b>의 여유를 주는 로그 스케일 중간에서 골랐다 —
 * {@code √(35.76 × 48.15) ≈ 41.5} → 5km 단위로 {@value #MAX_ANCHOR_DISTANCE_KM}. 스윕에서 40~45가
 * 유일하게 성립하는 구간이었다. 30km로 조이면 제주 델문도와 영주 부석사(진짜 쪽)가 잘리고, 50km로
 * 풀면 청주(48.15)·당진(49.19) 후보가 통과한다.
 *
 * <h2>모르는 것을 이탈로 판정하지 않는다</h2>
 * 거리가 {@code null}이면 <b>통과시킨다.</b> 그 값은 "가깝다"가 아니라 <b>"앵커 좌표를 못 얻어 잴 수
 * 없었다"</b>는 뜻이고(지오코딩 실패), 그때 거르면 이 파이프라인의 fail-open 원칙("degrade,
 * don't fail")을 정면으로 어긴다.
 *
 * <p>비대칭이 근거다 — <b>못 거른 후보는 Curator가 안 고를 수도 있지만, 잘못 거른 후보는 고를 기회
 * 자체가 없다.</b> 같은 방향의 판단이 {@link CandidateMatcher}의 근접 임계값에도 적혀 있다.
 *
 * <p>경계값은 <b>살린다</b>({@code >} 이지 {@code >=} 가 아니다). 정확히 상한인 후보를 버릴 근거가
 * 실측 어디에도 없다.
 */
public final class SeedDistanceLimit {

    /**
     * 권역 앵커로부터 허용하는 최대 거리(km).
     *
     * <p><b>이 값을 고치려면 {@code SeedDistanceCapProbeTest}를 다시 돌려야 한다.</b> 근거가 실측
     * 분포이므로, 숫자만 바꾸면 위 표와 어긋나 상수의 정당성이 사라진다.
     */
    public static final double MAX_ANCHOR_DISTANCE_KM = 45.0;

    private SeedDistanceLimit() {
    }

    /**
     * 권역 밖이라 후보에서 빼야 하는가.
     *
     * @param distanceKm 권역 앵커로부터의 거리. <b>{@code null}이면 잴 수 없었다는 뜻이라
     *                   {@code false}</b> — 모르는 것을 이탈로 판정하지 않는다
     */
    public static boolean isOutOfRegion(Double distanceKm) {
        return distanceKm != null && distanceKm > MAX_ANCHOR_DISTANCE_KM;
    }
}
