package backend.yourtrip.global.ai.candidate;

import java.util.List;

/**
 * Planner의 권역 라벨을 <b>검색어로 쓸 수 있는 지명</b>으로 줄인다 (이슈 #110).
 *
 * <h2>왜 필요한가</h2>
 * Planner가 내는 {@code area}는 사람이 읽는 라벨이다 — {@code "황리단길·대릉원 일대"}. 설계가 그렇게
 * 요구했고(시군구로 바꾸면 day 단위 locality를 잃는다) Planner는 그대로 동작했다. 그런데 <b>같은
 * 문자열이 네이버 지역검색의 쿼리 접두사로도 쓰인다</b>({@code "{area} {searchHint}"}). 실측 결과
 * <b>가운뎃점으로 두 지명을 붙인 표기에서는 검색이 0건</b>이고, 그러면 소스가 시더뿐인
 * MEAL·CAFE·SHOPPING 슬롯의 후보 목록이 통째로 빈다.
 *
 * <p><b>{@link AreaGeocoder}는 이 함정을 이미 알고 있었다</b> — "가운뎃점·일대가 붙어 검색 결과가
 * 흔들린다"며 Planner에게 {@code anchor}를 따로 내게 한 것이 4-8이다. 시더만 그 통찰을 물려받지
 * 못했고, 이 클래스가 그 자리를 메운다.
 *
 * <h2>실측으로 고른 방식</h2>
 * 여섯 전략을 12권역 × 3슬롯 × 3회로 비교했다({@code AreaQueryStrategyProbeTest}).
 *
 * <table>
 *   <tr><th>전략</th><th>평균 건수</th><th>0건 비율</th><th>거리 중앙값</th></tr>
 *   <tr><td>{@code area} 그대로</td><td>0.1</td><td><b>95%</b></td><td>–</td></tr>
 *   <tr><td><b>이 클래스(앞부분 + 접미어 제거)</b></td><td><b>3.6</b></td><td><b>3.7%</b></td><td>0.6~0.8km</td></tr>
 *   <tr><td>{@code anchor}로 대체</td><td>3.5</td><td>2.7%</td><td>0.5~0.8km</td></tr>
 *   <tr><td>{@code location}만</td><td>4.3</td><td>0%</td><td><b>6.2km</b></td></tr>
 * </table>
 *
 * <p>{@code location}은 건수로는 이기지만 <b>거리가 열 배</b>라 day 단위 권역이 무의미해진다.
 * {@code anchor} 계열과는 통계적으로 가르지 못했고(셋 다 노이즈 범위), 그래서 <b>구현이 단순한
 * 쪽</b>을 골랐다 — 이쪽은 순수 문자열 변환이라 스테이지 계약이 그대로다.
 *
 * <p><b>{@code anchor}를 쓰지 않은 실질적 이유가 하나 더 있다.</b> {@code anchor}는 이미 지오코딩
 * 기준점이라, 시더까지 그것에 매달면 <b>두 소스가 한 점에 의존</b>하게 된다. {@code area}를 살려
 * 쓰면 축이 둘로 남아 한쪽이 틀려도 다른 쪽이 산다.
 */
public final class AreaQueryNormalizer {

    /**
     * 권역명을 잇는 구분자. <b>앞부분만 남긴다.</b>
     *
     * <p>Planner 프롬프트가 예시로 준 것은 가운뎃점({@code ·})뿐이지만 변종을 함께 다룬다 —
     * 어느 코드포인트를 쓸지는 모델이 정하고, 실측에서 {@code 해운대 해변·달맞이길 일대}처럼
     * 표기가 매번 흔들리는 것을 확인했다.
     */
    private static final char[] SEPARATORS = {'·', 'ㆍ', '･', '/', ','};

    /**
     * 권역을 가리키는 접미어. 지명 뒤에 붙으면 떼어낸다.
     *
     * <p><b>{@code 거리}·{@code 길}은 넣지 않는다.</b> {@code 황리단길}·{@code 커피거리}처럼 지명의
     * 일부일 수 있고, 그걸 떼면 검색어가 통째로 무너진다 — 이 규칙의 가장 큰 위험이라
     * {@code AreaQueryNormalizerTest}가 고정한다.
     *
     * <p>이 목록이 실제로 일하는 곳은 <b>구분자가 없는 권역명</b>이다({@code 순천만습지 일대},
     * {@code 무섬마을 일대}). 실측 3회 중 두 번 그런 표기가 나왔고, 접미어 제거가 없으면 그 day의
     * 시더가 그대로 죽는다.
     */
    private static final List<String> AREA_SUFFIXES = List.of("일대", "방면", "주변", "근처");

    private AreaQueryNormalizer() {
    }

    /**
     * 검색어로 쓸 지명. 예: {@code "황리단길·대릉원 일대"} → {@code "황리단길"}
     *
     * <p><b>줄인 결과가 비면 원문을 그대로 돌려준다.</b> 못 알아보는 표기를 만났을 때 검색어를
     * 없애는 것보다 낫다 — 4-4가 "매핑에 없는 분류는 통과시킨다"고 정한 것과 같은 태도다.
     *
     * @param area Planner가 낸 권역 라벨. {@code null}·공백이면 그대로 돌려준다
     */
    public static String toSearchTerm(String area) {
        if (area == null || area.isBlank()) {
            return area;
        }
        String reduced = stripAreaSuffix(firstSegment(area));
        return reduced.isBlank() ? area.strip() : reduced;
    }

    /** 첫 구분자 앞부분. 구분자가 없으면 원문 그대로. */
    private static String firstSegment(String area) {
        int cut = -1;
        for (char separator : SEPARATORS) {
            int index = area.indexOf(separator);
            if (index >= 0 && (cut < 0 || index < cut)) {
                cut = index;
            }
        }
        return (cut < 0 ? area : area.substring(0, cut)).strip();
    }

    /**
     * 권역 접미어 제거.
     *
     * <p><b>접미어만 남는 경우에는 떼지 않는다</b>({@code "일대"} 하나가 통째로 들어온 경우).
     * 남길 지명이 없는데 떼면 빈 검색어가 되고, 그건 원문보다 나쁘다.
     */
    private static String stripAreaSuffix(String segment) {
        for (String suffix : AREA_SUFFIXES) {
            if (segment.endsWith(suffix) && segment.length() > suffix.length()) {
                return segment.substring(0, segment.length() - suffix.length()).strip();
            }
        }
        return segment;
    }
}
