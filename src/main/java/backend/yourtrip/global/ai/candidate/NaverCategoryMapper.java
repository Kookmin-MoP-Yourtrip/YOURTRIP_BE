package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 네이버 지역검색 {@code category} → {@link SlotType} 매핑 (ROADMAP 4-4). <b>순수 함수만 둔다.</b>
 *
 * <h2>왜 필요한가</h2>
 * SEEDED 후보는 카카오를 거치지 않으므로 {@code category_group_code}가 없다. 5-3이 슬롯별 카테고리
 * 하드 제약("점심 슬롯에 호프집 금지")을 걸려면 네이버 분류 문자열에서 같은 판단을 뽑아내야 한다.
 *
 * <h2>설계의 "최상위 분류를 매핑한다"는 규칙은 실제 응답과 맞지 않는다 ★</h2>
 * 4-2 실호출이 이런 값을 돌려줬다.
 *
 * <pre>
 *   음식점&gt;카페,디저트      ← 카페인데 최상위는 "음식점"이다
 *   카페,디저트&gt;베이커리
 *   브런치카페              ← 구분자가 아예 없다
 * </pre>
 *
 * <b>최상위만 보면 카페가 MEAL로 분류되고</b>, 5-3의 하드 제약에서 CAFE 슬롯의 정당한 후보가
 * 탈락한다. 그래서 규칙을 뒤집어 <b>가장 구체적인 분류부터 훑고 먼저 맞는 것을 택한다.</b>
 * {@code 음식점>한식>국밥}처럼 뒤쪽이 매핑에 없으면 자연히 앞쪽("음식점" → MEAL)으로 떨어지므로,
 * 설계가 의도한 동작은 그대로 보존된다.
 *
 * <h2>매핑에 없으면 버리지 않는다</h2>
 * {@link Optional#empty()}가 "모르겠다"는 표시다. 설계 원칙 그대로 <b>통과시키되 표시</b>하는 것이고,
 * 하드 드롭이 아니다 — 분류 체계는 우리가 통제하지 못하는 외부 값이라, 모르는 값을 만났을 때
 * 후보를 버리면 네이버가 분류를 하나 추가하는 날 조용히 후보가 사라진다.
 */
public final class NaverCategoryMapper {

    private static final String SEPARATOR = ">";

    /**
     * 분류 토큰 → 슬롯. <b>순서가 곧 우선순위다</b> — 한 토큰이 여러 규칙에 걸릴 때 앞의 것이 이긴다.
     * {@code 카페}를 {@code 음식점}보다 앞에 두는 것이 이 표의 핵심이다.
     *
     * <p><b>한 글자 토큰을 넣지 않는다.</b> 판정이 부분 문자열 매칭이라 한 글자는 무관한 분류를
     * 대량으로 삼킨다 — 실제로 {@code 차}(찻집 의도)를 넣었더니 {@code 교통>주차장}이 CAFE로
     * 판정됐다. 세차장·기차역도 같은 경로로 걸린다. 의도한 뜻을 담으려면 {@code 찻집}처럼 두 글자
     * 이상으로 적는다.
     */
    private static final List<Rule> RULES = List.of(
        // 카페 계열을 가장 먼저 본다 — "음식점>카페,디저트"에서 음식점이 이기면 안 된다.
        new Rule(SlotType.CAFE, Set.of("카페", "디저트", "베이커리", "제과", "빙수", "찻집")),
        // 관광 계열. 네이버 분류는 전망대·산책로를 따로 두지 않으므로 한 덩어리로 받는다
        // (그 구분은 아래 isCompatibleWith 가 담당한다).
        new Rule(SlotType.ATTRACTION, Set.of("관광", "명소", "명승", "유적", "문화", "예술",
            "박물관", "미술관", "공원", "전망", "자연")),
        new Rule(SlotType.EXPERIENCE, Set.of("레저", "스포츠", "체험", "테마파크", "놀이")),
        new Rule(SlotType.SHOPPING, Set.of("쇼핑", "유통", "백화점", "시장", "아울렛", "면세점")),
        // 음식점은 가장 마지막이다. 카페 계열을 먼저 걸러낸 뒤에 남는 것만 MEAL 이 된다.
        new Rule(SlotType.MEAL, Set.of("음식점", "한식", "중식", "일식", "양식", "분식", "뷔페",
            "food", "술집", "요리주점", "호프", "포장마차", "이자카야"))
    );

    private record Rule(SlotType slotType, Set<String> tokens) {

        boolean matches(String segment) {
            return tokens.stream().anyMatch(segment::contains);
        }
    }

    private NaverCategoryMapper() {
    }

    /**
     * 분류 문자열에서 슬롯 타입을 뽑는다.
     *
     * <p><b>가장 구체적인 세그먼트부터 훑는다</b> — {@code 음식점>카페,디저트}는 CAFE,
     * {@code 음식점>한식>국밥}은 MEAL이 된다. 구분자가 없으면 문자열 전체를 하나의 세그먼트로 본다
     * ({@code 브런치카페} → CAFE).
     *
     * @return 판단할 수 없으면 {@link Optional#empty()}. <b>이것은 "버려라"가 아니라 "모르겠다"다</b>
     */
    public static Optional<SlotType> toSlotType(String category) {
        if (category == null || category.isBlank()) {
            return Optional.empty();
        }

        String[] segments = category.split(SEPARATOR);
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i].trim().toLowerCase(Locale.ROOT);
            if (segment.isEmpty()) {
                continue;
            }
            for (Rule rule : RULES) {
                if (rule.matches(segment)) {
                    return Optional.of(rule.slotType());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 이 분류가 해당 슬롯에 놓여도 되는가. <b>5-3의 하드 제약이 쓸 판정이다.</b>
     *
     * <p><b>동등 비교가 아닌 이유</b>: 네이버 분류는 {@code ATTRACTION}·{@code VIEWPOINT}·{@code STROLL}을
     * 구분하지 않는다. 셋 다 {@code 관광,명소} 아래로 들어오므로 동등 비교를 하면 전망대 슬롯의
     * 정당한 후보가 전부 탈락한다. 카카오 쪽도 같은 사정이라 {@code SlotType}의
     * {@code allowedCategoryCodes}가 셋에 {@code AT4}를 공유시켜 둔 것과 같은 구조다.
     *
     * <p><b>모르는 분류는 통과시킨다</b>({@code true}) — 위 "매핑에 없으면 버리지 않는다" 참고.
     */
    public static boolean isCompatibleWith(String category, SlotType slotType) {
        Optional<SlotType> mapped = toSlotType(category);
        if (mapped.isEmpty()) {
            return true;
        }
        return groupOf(mapped.get()) == groupOf(slotType);
    }

    /**
     * 술집 계열인가. <b>MEAL 이면서도 점심에는 곤란한 분류</b>를 가려낸다.
     *
     * <p>설계가 카카오 쪽에 요구한 규칙이 그대로 여기에도 필요하다 — *"MEAL 슬롯 ← FD6 필수. 단
     * {@code category_name}이 '술집' 계열이면 감점(점심에 호프집 방지)"*. {@link #toSlotType}이
     * 이들을 MEAL로 매핑하는 것은 맞다(저녁 슬롯에는 정당한 후보다). 시간대에 따른 감점은 슬롯
     * 배치를 아는 5-3이 하므로, 이 클래스는 <b>판정 재료만 제공하고 정책은 갖지 않는다.</b>
     */
    public static boolean isBarLike(String category) {
        if (category == null) {
            return false;
        }
        String normalized = category.toLowerCase(Locale.ROOT);
        return BAR_TOKENS.stream().anyMatch(normalized::contains);
    }

    private static final Set<String> BAR_TOKENS =
        Set.of("술집", "요리주점", "호프", "바(bar)", "포장마차", "이자카야", "와인바", "칵테일");

    /** 네이버 분류가 실제로 가를 수 있는 단위. 이보다 잘게 나누면 거짓 탈락이 생긴다. */
    private static Group groupOf(SlotType slotType) {
        return switch (slotType) {
            case MEAL -> Group.MEAL;
            case CAFE -> Group.CAFE;
            case SHOPPING -> Group.SHOPPING;
            case EXPERIENCE -> Group.EXPERIENCE;
            case ATTRACTION, VIEWPOINT, STROLL -> Group.SIGHT;
        };
    }

    private enum Group {
        MEAL, CAFE, SIGHT, EXPERIENCE, SHOPPING
    }
}
