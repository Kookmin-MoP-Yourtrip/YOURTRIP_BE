package backend.yourtrip.global.ai.prompt;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 사용자 키워드를 프롬프트에 실을 한 줄로 옮긴다 (ROADMAP 6-5).
 *
 * <h2>{@code duration}은 싣지 않는다</h2>
 * {@code KeywordType}에는 {@code ONE_DAY}·{@code TWO_DAYS}·{@code WEEKEND}·{@code LONG} 넷이 있고
 * 기존 프롬프트는 이들을 JSON에 실어 보냈지만, <b>해석 규칙이 한 줄도 없어 아무도 읽지 않는
 * 신호였다</b>(프롬프트가 설명하는 것은 travelMode·companionType·mood·budget뿐이다). 게다가
 * 프롬프트 예시의 {@code "1박2일"}은 실제 label {@code "1박 2일"}과 표기도 어긋나 있었다.
 *
 * <p><b>여행 일수의 정본은 요청의 {@code days}다.</b> 같은 사실을 두 경로로 보내면 어긋났을 때 모델이
 * 어느 쪽을 따를지 통제할 수 없다. 그래서 싣지 않고, 대신 <b>명백한 모순만 관측한다</b>
 * ({@link #durationConflict}) — 보정하지 않는 이유는 고칠 대상이 코스가 아니라 <b>프론트의 입력
 * UX</b>일 수 있기 때문이고, 그 판단에는 "실제로 얼마나 자주 어긋나는가"라는 데이터가 먼저 필요하다.
 *
 * <h2>{@code KeywordType.buildKeywordsJson}을 고치지 않고 새로 만드는 이유</h2>
 * 그 메서드는 환각률 baseline 하네스({@code AiHallucinationBaselineTest})가 만드는 프롬프트의 일부이고,
 * <b>세 측정점을 같은 자로 재려면 글자 하나까지 고정</b>돼야 한다(그 목적으로 {@code buildPrompt}가
 * {@code public static}으로 열려 있다). 여기서 손대면 2-6 측정과의 비교 가능성이 깨진다.
 *
 * <h2>JSON이 아니라 사람이 읽는 줄로 두는 이유</h2>
 * 구조화가 필요한 것은 <b>응답</b>이고 그건 {@code response_format.json_schema}가 강제한다. 입력까지
 * JSON으로 두면 중괄호·따옴표·들여쓰기가 토큰만 더 쓴다.
 */
public final class KeywordRenderer {

    /** 키워드가 하나도 없을 때. 빈 문자열을 넣으면 프롬프트에 이유 없는 빈 줄이 생긴다. */
    private static final String NONE = "지정 없음";

    /** 프롬프트에 실을 카테고리와 그 표기. <b>{@code duration}은 여기 없다</b>(위 참고). */
    private static final Map<String, String> RENDERED_CATEGORIES = Map.of(
        "travelMode", "이동수단",
        "companionType", "동행",
        "mood", "분위기",
        "budget", "예산"
    );

    /** 카테고리 표기 순서. {@code Map}은 순서를 보장하지 않는데 프롬프트는 매번 같아야 한다. */
    private static final List<String> CATEGORY_ORDER =
        List.of("travelMode", "companionType", "mood", "budget");

    /**
     * {@code duration} 키워드가 뜻하는 여행 일수. <b>모호한 것은 넣지 않는다</b> —
     * {@code WEEKEND}("주말")는 1일일 수도 2일일 수도 있어 모순을 판정할 수 없다.
     */
    private static final Map<KeywordType, Integer> EXPECTED_DAYS = expectedDays();

    private KeywordRenderer() {
    }

    /**
     * 프롬프트에 실을 한 줄. 예: {@code 이동수단: 뚜벅이 / 동행: 연인 / 분위기: 힐링, 감성}
     *
     * <p>사용자가 고르지 않은 카테고리는 아예 빼고 "없음"이라고 적지 않는다 — 고르지 않은 것은
     * 제약이 아니라 자유이고, 그걸 굳이 알리면 모델이 없는 제약을 지어낸다.
     */
    public static String render(List<KeywordType> keywords) {
        Set<KeywordType> selected = keywords == null ? Set.of() : Set.copyOf(keywords);
        if (selected.isEmpty()) {
            return NONE;
        }

        List<String> parts = new ArrayList<>(CATEGORY_ORDER.size());
        for (String category : CATEGORY_ORDER) {
            List<String> labels = KeywordType.findByCategory(category).stream()
                .filter(selected::contains)
                .map(KeywordType::getLabel)
                .toList();
            if (!labels.isEmpty()) {
                parts.add(RENDERED_CATEGORIES.get(category) + ": " + String.join(", ", labels));
            }
        }
        return parts.isEmpty() ? NONE : String.join(" / ", parts);
    }

    /**
     * {@code duration} 키워드가 요청의 {@code days}와 명백히 어긋나는가.
     *
     * <p><b>이 값을 근거로 코스를 바꾸지 않는다.</b> 호출부는 로그만 남긴다 — 어긋남이 잦다면
     * 고칠 곳은 코스 생성이 아니라 키워드를 고르는 화면이고, 그 판단에는 빈도 데이터가 먼저다.
     *
     * @return 어긋났을 때 사람이 읽을 설명, 아니면 빈 값
     */
    public static Optional<String> durationConflict(List<KeywordType> keywords, int days) {
        if (keywords == null) {
            return Optional.empty();
        }
        for (KeywordType keyword : keywords) {
            Integer expected = EXPECTED_DAYS.get(keyword);
            if (expected != null && expected != days) {
                return Optional.of("duration 키워드 '%s'(%d일)와 요청 일수 %d일이 어긋난다 — 요청 일수를 따른다"
                    .formatted(keyword.getLabel(), expected, days));
            }
        }
        return Optional.empty();
    }

    private static Map<KeywordType, Integer> expectedDays() {
        Map<KeywordType, Integer> expected = new EnumMap<>(KeywordType.class);
        expected.put(KeywordType.ONE_DAY, 1);
        expected.put(KeywordType.TWO_DAYS, 2);
        return Map.copyOf(expected);
    }
}
