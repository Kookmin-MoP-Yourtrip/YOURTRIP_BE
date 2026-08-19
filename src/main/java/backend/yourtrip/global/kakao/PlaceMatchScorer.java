package backend.yourtrip.global.kakao;

import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import java.util.Set;

/**
 * 카카오 후보와 AI가 준 장소가 얼마나 맞는지 점수를 매긴다. <b>순수 함수만 둔다.</b>
 *
 * <h2>왜 클라이언트에서 떼어냈나</h2>
 * 원래 {@code KakaoLocalClient}의 private 메서드였고, 환각률 baseline 하네스가 이것을
 * <b>리플렉션으로</b> 불렀다({@code ReflectionTestUtils.invokeMethod}). 로직을 복제하면 원본과
 * drift가 생겨 before/after 비교 근거가 무너지므로 복제 대신 리플렉션을 택한 것이었는데, 그 선택이
 * <b>프로덕션 코드의 private 시그니처를 측정 하네스의 계약으로 만든다.</b>
 *
 * <p>실제로 값을 치렀다. 4-8에서 {@code KakaoLocalClient}를 고칠 때 이 메서드의 이름·인자를 건드리면
 * <b>컴파일은 통과하고 하네스만 런타임에 깨진다</b> — 그것도 {@code @Tag("benchmark")}라 일반
 * 빌드에서 돌지 않으니 <b>다음 측정 때까지 아무도 모른다.</b> 점수 계산을 공개 순수 함수로 옮기면
 * 리플렉션도, 복제도, 그 함정도 함께 사라진다.
 *
 * <h2>이 점수는 하한선으로 쓸 수 없다 — 이름 게이트가 따로 있는 이유</h2>
 * 검색 키워드가 {@code "지역명 + 장소명"}이라 주소 일치({@link #ADDRESS_MATCH_SCORE})가 거의 자동으로
 * 붙고, 음식점·카페면 카테고리({@link #CATEGORY_MATCH_SCORE})도 자동이다. 그래서 <b>이름이 하나도
 * 안 맞아도 5점이 나온다.</b> 1-2 실측에서 5~7점 구간의 31%가 오매칭이었던 반면 3점 구간은 표본
 * 전부가 정답이었다 — 점수가 정확도와 단조 관계가 아니다.
 *
 * <p>그래서 {@code KakaoLocalClient}는 <b>이름 일치를 별도 게이트로 먼저 거른 뒤</b> 이 점수로
 * 순위만 매긴다. 이 클래스만 보고 "점수가 높으면 정확하다"고 읽으면 안 된다.
 * 근거: {@code docs/tasks/ai-course-create/BASELINE-ARTIFACT-ANALYSIS.md} 판정 1·2
 *
 * <h2>이름 비교 규칙이 게이트와 다르다 — 의도된 차이다</h2>
 * 게이트({@code PlaceNameNormalizer.similar})는 공백·중점·문장부호를 걷어내고 비교하지만, 이 점수는
 * <b>소문자화만 하고 그대로 비교한다.</b> 두 규칙을 통일하고 싶어지지만 하면 안 된다 —
 * <b>환각률 baseline이 이 계산으로 측정된 값</b>이라, 바꾸는 순간 이전 측정과 비교할 수 없게 된다.
 * 통일이 필요하다면 재측정과 함께여야 한다.
 */
public final class PlaceMatchScorer {

    /** 이름이 서로를 포함할 때. */
    public static final int NAME_MATCH_SCORE = 5;

    /** 지역명이 카카오 주소에 들어 있을 때. <b>검색어에 지역명이 들어가므로 거의 자동으로 붙는다.</b> */
    public static final int ADDRESS_MATCH_SCORE = 3;

    /** 음식점·카페·관광명소 그룹일 때. */
    public static final int CATEGORY_MATCH_SCORE = 2;

    /** 음식점 / 카페 / 관광명소. <b>{@code contains(null)} 이 NPE 이므로 조회 전에 null 을 거른다.</b> */
    private static final Set<String> PREFERRED_CATEGORY_GROUPS = Set.of("FD6", "CE7", "AT4");

    private PlaceMatchScorer() {
    }

    /**
     * 후보 하나의 점수를 매긴다.
     *
     * @param placeName     AI가 준 장소명. 비어 있으면 이름 점수가 붙지 않는다
     * @param placeLocation 검색에 쓴 지역명. 비어 있으면 주소 점수가 붙지 않는다
     * @return 0 ~ 10
     */
    public static int score(Document document, String placeName, String placeLocation) {
        if (document == null) {
            return 0;
        }

        int score = 0;

        String name = document.place_name() != null ? document.place_name() : "";
        String address = bestAddressOf(document);

        // 1) 이름 유사도 (단순 contains 기반)
        if (placeName != null && !placeName.isBlank()) {
            String lowerName = name.toLowerCase();
            String lowerInput = placeName.toLowerCase();
            if (lowerName.contains(lowerInput) || lowerInput.contains(lowerName)) {
                score += NAME_MATCH_SCORE;
            }
        }

        // 2) 주소 유사도 (placeLocation 문자열이 카카오 주소에 포함되면 가점)
        if (placeLocation != null && !placeLocation.isBlank()) {
            if (address.toLowerCase().contains(placeLocation.toLowerCase())) {
                score += ADDRESS_MATCH_SCORE;
            }
        }

        // 3) 카테고리 그룹이 관광/카페/음식점이면 가산점.
        // null 검사를 먼저 한다 — Set.of() 가 만드는 불변 집합은 contains(null) 에서 NPE 를 던진다.
        String groupCode = document.category_group_code();
        if (groupCode != null && PREFERRED_CATEGORY_GROUPS.contains(groupCode)) {
            score += CATEGORY_MATCH_SCORE;
        }

        return score;
    }

    /** 도로명주소를 우선하고 없으면 지번으로 떨어진다. */
    public static String bestAddressOf(Document document) {
        if (document == null) {
            return "";
        }
        String road = document.road_address_name();
        if (road != null && !road.isBlank()) {
            return road;
        }
        String lot = document.address_name();
        return lot == null ? "" : lot;
    }
}
