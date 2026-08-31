package backend.yourtrip.global.benchmark;

import backend.yourtrip.global.benchmark.BaselineInputSet.RegionTier;
import backend.yourtrip.global.benchmark.BaselineInputSet.RequestSpec;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.PlaceLookup;
import backend.yourtrip.global.kakao.PlaceMatchScorer;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import java.util.List;
import java.util.Set;

/**
 * 장소 이름 하나를 <b>프로덕션 검증 경로로</b> 채점해 결과 구간(band)을 매긴다.
 *
 * <p><b>이 클래스가 따로 있는 이유</b>: 채점 절차는 측정점 사이의 비교 가능성을 떠받치는
 * 유일한 축이다. ROADMAP의 3점 비교(Gemini 단일 호출 / OpenAI 단일 호출 / OpenAI 파이프라인)는
 * "같은 자로 쟀다"는 전제 위에만 성립하는데, 하네스마다 채점을 복제하면 한쪽만 바뀌어도
 * <b>겉으로는 드러나지 않는다</b> — 두 CSV 모두 같은 열 이름을 갖고 같은 행수를 내기 때문이다.
 * {@link BaselineInputSet}(입력 세트)이 같은 이유로 먼저 떨어져 나왔고, 이 클래스는 그 반대편
 * 끝(판정)이다.
 *
 * <p>원래 {@code AiHallucinationBaselineTest}의 private 메서드였다. ROADMAP 8-6이 파이프라인
 * 출력에 <b>같은 판정</b>을 걸어야 해서 공유 지점으로 승격했다 — 값은 한 글자도 바뀌지 않았고,
 * 그 사실은 기존 산출물 재채점이 바이트 단위로 같은지로 확인한다(STEP-8 판정 4).
 *
 * <p><b>{@code searchPlace()}가 아니라 {@link KakaoLocalClient#lookupBestPlace}를 쓴다.</b>
 * 프로덕션은 검색 결과에 이름 게이트를 먼저 걸고 통과한 후보 중에서 점수로 순위를 매긴다.
 * 하네스가 검색만 불러 직접 채점하면 게이트를 우회해 <b>옛 매칭 로직을 재현하게 된다.</b>
 * 결과를 예외가 아니라 값으로 받는 것도 요구사항이다 — 무결과와 이름 불일치를 갈라야
 * 자동 프록시와 이름 불일치율을 따로 낼 수 있다.
 */
public final class HallucinationScoring {

    /**
     * 결과 구간. 앞의 셋은 {@link PlaceLookup}의 변형을 그대로 옮긴 것이고, 뒤의 넷은
     * {@code Found}의 점수를 나눈 것이다. bestScore 는 -3 = 이름 게이트 전멸,
     * -2 = 카카오 API 오류, -1 = 검색 결과 0건, 그 외 0~10.
     */
    public static final String BAND_KAKAO_ERROR = "KAKAO_ERROR";
    public static final String BAND_NO_RESULT = "NO_RESULT";
    public static final String BAND_NAME_MISMATCH = "NAME_MISMATCH";
    public static final String BAND_S0 = "S0";
    public static final String BAND_S1_4 = "S1_4";
    public static final String BAND_S5_7 = "S5_7";
    public static final String BAND_S8_10 = "S8_10";

    public static final List<String> ALL_BANDS = List.of(
        BAND_KAKAO_ERROR, BAND_NO_RESULT, BAND_NAME_MISMATCH,
        BAND_S0, BAND_S1_4, BAND_S5_7, BAND_S8_10);

    /**
     * 자동 프록시가 세는 구간. <b>{@code NO_RESULT} 하나뿐이다.</b>
     *
     * <p>예전에는 {@code S0}·{@code S1_4}도 넣었는데, <b>표기만 다른 장소가 어느 구간에 떨어질지를
     * 카카오의 {@code category_group_code} 부여 여부가 갈랐다</b> — 카테고리 +2가 붙으면 5점
     * ({@code S5_7}, 안 걸림), 안 붙으면 3점({@code S1_4}, 걸림). 실측에서 표기 차이 32건이
     * 15 / 17로 쪼개졌다. {@code NO_RESULT}는 검색 결과가 0건이라 점수 구성 자체가 개입하지 않아
     * 그런 자의성이 없다.
     *
     * <p>{@code NAME_MISMATCH}는 <b>따로 센다</b>(이름 불일치율). 게이트에도 거짓 양성이 있기
     * 때문이다("해운대 해변" → "해운대해수욕장"). 둘의 합은 장소 미확보율로 따로 보고한다.
     */
    public static final Set<String> SUSPECT_BANDS = Set.of(BAND_NO_RESULT);

    private HallucinationScoring() {
    }

    /**
     * 장소 1건의 검증 결과.
     *
     * <p>{@code kakaoTotalCount}를 뺐다 — {@link PlaceLookup}은 검색 총건수를 노출하지 않고,
     * 그 값을 얻자고 검색을 두 번 부르면 판정과 기록이 서로 다른 응답을 볼 수 있다. 대신
     * {@code rejectedCandidateName}이 들어왔다: 이름 게이트가 무엇을 걸렀는지 봐야 거짓 양성
     * (같은 곳인데 표기가 다른 경우)을 사후에 가려낼 수 있다.
     *
     * <p><b>{@code matchedX}/{@code matchedY}/{@code matchedCategoryGroupCode}는 환각률 측정이
     * 쓰지 않는다 — 그럼에도 남기는 이유.</b> 카카오 응답에 이미 들어 있는데 버리고 있었고,
     * 버리고 나면 되찾는 방법이 389건 재호출뿐이다. 응답에 있는 것을 그대로 적는 비용은 0에
     * 가깝고, {@code artifacts/README.md}가 기록한 "산출물 소실" 사고는 되돌릴 수 없다.
     */
    public record PlaceRow(
        int requestId, String location, RegionTier tier, String keywordSetId,
        int day, int placeIndex, String aiPlaceName,
        int bestScore, String scoreBand,
        String matchedPlaceName, String matchedCategory, String matchedAddress,
        String matchedPlaceUrl, String rejectedCandidateName,
        String matchedX, String matchedY, String matchedCategoryGroupCode
    ) {}

    /**
     * 이름만 받는다 — 재채점 모드가 응답 DTO 없이(CSV의 문자열로) 재사용하기 위해서다.
     * ROADMAP 8-6의 파이프라인 하네스도 같은 이유로 이 시그니처를 그대로 쓴다
     * ({@code GroundedPlace.name()}을 꺼내 넣는다).
     */
    public static PlaceRow groundOnePlace(RequestSpec spec, int day, int placeIndex,
        String placeName, KakaoLocalClient kakaoLocalClient) {

        String aiPlaceName = placeName == null ? "" : placeName;

        // 실제 호출부(MyCourseServiceImpl 의 구 경로)와 같이 placeLocation 자리에 지역명을 넘긴다.
        // 키워드 조립("{지역} {장소}")과 후보 수(5)는 lookupBestPlace 안에 있다 — 복제하지 않는다.
        String placeLocation = spec.region().name();

        PlaceLookup lookup;
        try {
            lookup = kakaoLocalClient.lookupBestPlace(aiPlaceName, placeLocation);
        } catch (RuntimeException e) {
            // lookupBestPlace 는 실패도 값으로 돌려주므로 여기까지 오는 일은 없어야 한다.
            // 그래도 측정을 멈추지 않기 위해 오류 자체를 하나의 결과로 기록한다.
            return placeRow(spec, day, placeIndex, aiPlaceName, placeLocation,
                -2, BAND_KAKAO_ERROR, null, "");
        }

        return switch (lookup) {
            case PlaceLookup.Failed failed -> placeRow(spec, day, placeIndex, aiPlaceName,
                placeLocation, -2, BAND_KAKAO_ERROR, null, failed.cause().name());

            case PlaceLookup.NoResult ignored -> placeRow(spec, day, placeIndex, aiPlaceName,
                placeLocation, -1, BAND_NO_RESULT, null, "");

            // 검색은 됐는데 이름 게이트를 통과한 후보가 0건이다. 무엇에 걸렸는지 남긴다.
            case PlaceLookup.NameMismatch mismatch -> placeRow(spec, day, placeIndex, aiPlaceName,
                placeLocation, -3, BAND_NAME_MISMATCH, null,
                nullToEmpty(mismatch.bestCandidateName()));

            case PlaceLookup.Found found -> {
                Document doc = found.document();
                int score = PlaceMatchScorer.score(doc, aiPlaceName, placeLocation);
                yield placeRow(spec, day, placeIndex, aiPlaceName, placeLocation,
                    score, bandOf(score), doc, "");
            }
        };
    }

    /** 결과 네 갈래가 같은 열을 채우므로 조립을 한 곳에 모은다. */
    private static PlaceRow placeRow(RequestSpec spec, int day, int placeIndex, String aiPlaceName,
        String placeLocation, int bestScore, String scoreBand, Document doc,
        String rejectedCandidateName) {

        return new PlaceRow(spec.requestId(), placeLocation, spec.region().tier(),
            spec.keywordSet().id(), day, placeIndex, aiPlaceName,
            bestScore, scoreBand,
            doc == null ? "" : nullToEmpty(doc.place_name()),
            doc == null ? "" : nullToEmpty(doc.category_name()),
            // 도로명 우선·지번 폴백도 프로덕션 순수 함수를 쓴다.
            doc == null ? "" : PlaceMatchScorer.bestAddressOf(doc),
            doc == null ? "" : nullToEmpty(doc.place_url()),
            rejectedCandidateName,
            // 카카오는 x=경도·y=위도이고 값이 문자열이다. 여기서 double 로 바꾸지 않는 이유는
            // 이 채점기가 좌표를 해석하지 않기 때문이다 — 파싱은 쓰는 쪽의 일이고, 중간에서
            // 변환하면 응답 원문과 CSV 가 어긋날 수 있는 지점이 하나 늘어난다.
            doc == null ? "" : nullToEmpty(doc.x()),
            doc == null ? "" : nullToEmpty(doc.y()),
            doc == null ? "" : nullToEmpty(doc.category_group_code()));
    }

    /**
     * {@code Found}의 점수를 구간으로 나눈다. <b>게이트를 통과한 후보에만 적용된다</b> —
     * 무결과·이름 불일치·API 오류는 점수 이전에 갈리므로 여기로 오지 않는다.
     *
     * <p>게이트({@code PlaceNameNormalizer.similar})는 정규화 후 비교하는데 점수의 이름 가점은
     * 정규화하지 않으므로, <b>게이트를 통과해도 낮은 구간이 남는다</b>("동궁과 월지" → "동궁과월지"는
     * 통과하지만 +5는 못 받는다). 그래서 이 구간은 정확도 신호가 아니라 <b>층화 추출과 순위</b>의
     * 축이다.
     */
    public static String bandOf(int score) {
        if (score == 0) return BAND_S0;
        if (score <= 4) return BAND_S1_4;
        if (score <= 7) return BAND_S5_7;
        return BAND_S8_10;
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
