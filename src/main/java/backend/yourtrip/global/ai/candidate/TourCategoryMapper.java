package backend.yourtrip.global.ai.candidate;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TourAPI 분류 코드 → {@link StyleTag} 결정론 매핑 (ROADMAP 4-9). <b>순수 함수만 둔다.</b>
 *
 * <h2>왜 필요한가</h2>
 * TourAPI는 모든 항목에 3단계 분류를 붙인다({@code A02} 인문 → {@code A0201} 역사관광지 →
 * {@code A02010800} 사찰). <b>관광지가 "무엇인지"를 코드가 이미 말해주므로</b>, 검색어에 기대지 않고
 * 스타일 태그로 옮길 수 있다. 시더가 modifier 쿼리로 얻는 것(야경·루프탑처럼 코드에 없는 속성)과
 * 보완 관계다.
 *
 * <h2>필터가 아니라 표시다</h2>
 * 여기서 나온 태그로 <b>후보를 자르지 않는다.</b> 후보마다 붙여 Curator 입력에 넣고, 사용자 키워드와
 * 맞으면 목록 정렬에서 앞으로 온다. 그래서 <b>빈 집합이 "버려라"가 아니다</b> — {@code A02040800}
 * 기타처럼 성격을 말할 수 없는 코드가 실제로 있고, 그런 후보도 거리순으로는 정당한 후보다.
 *
 * <h2>검색어가 없는 태그도 쓴다 — 4-3과 다른 점이다</h2>
 * {@link StyleModifierDictionary}는 태그를 <b>쿼리</b>로 만들기 때문에 검색어가 없는 태그를
 * 걸러내지만, 여기서는 태그가 <b>표시</b>라서 검색어가 필요 없다. {@code LIVELY}(시끌벅적)처럼
 * 4-3 실측에서 검색어를 비운 태그가 여기서는 그대로 쓰인다.
 *
 * <h2>세 층을 합집합한다</h2>
 * {@code cat1} ∪ {@code cat2} ∪ {@code cat3}의 규칙을 모두 더한다. <b>가장 구체적인 것 하나만
 * 택하지 않는 이유</b>는, 사찰이 여전히 역사관광지이기 때문이다 — 구체 규칙이 이기게 하면 상위
 * 성격을 적어 주지 않은 코드에서 조용히 {@code 역사}가 빠진다. 합집합이면 <b>새 {@code cat3}가
 * 생겨도 상위 성격을 자동으로 물려받는다.</b>
 *
 * <p>상위 층에는 <b>모든 하위에 참인 태그만</b> 둔다. {@code A0202}(휴양관광지)에 상위 태그가 없는
 * 것이 그 예다 — 온천과 테마공원이 같은 부모 아래 있어서 공통으로 참인 성격이 없다.
 *
 * <h2>어떤 코드를 담았는가</h2>
 * 공식 코드표는 {@code cat3} 153개인데(4-7에서 {@code categoryCode2}로 받아 왔다), 이 사전은
 * <b>{@code contentTypeId} 12·14·28이 실제로 돌려주는 {@code A01}·{@code A02}·{@code A03}</b>만
 * 담는다. {@code A04} 쇼핑·{@code A05} 음식·{@code B02} 숙박은 우리가 부르지 않는
 * {@code contentTypeId}의 것이라 넣어도 죽은 코드가 된다. 사전의 키가 공식 코드표 안에 있는지는
 * {@code TourCategoryMapperTest}가 코드표 원본과 대조해 강제한다.
 */
public final class TourCategoryMapper {

    private TourCategoryMapper() {
    }

    private static final Map<String, Set<StyleTag>> RULES = buildRules();

    /**
     * 분류 코드를 스타일 태그로 옮긴다.
     *
     * @param cat3 소분류 코드({@code A02010800}). {@code cat1}·{@code cat2}는 여기서 잘라 쓴다
     * @return 불변 집합. <b>빈 집합은 "성격을 말할 수 없다"는 뜻이지 "버려라"가 아니다</b>
     */
    public static Set<StyleTag> styleTagsOf(String cat3) {
        if (cat3 == null || cat3.isBlank()) {
            return Set.of();
        }
        String code = cat3.trim().toUpperCase(Locale.ROOT);

        Set<StyleTag> tags = EnumSet.noneOf(StyleTag.class);
        addRule(tags, prefix(code, 3));
        addRule(tags, prefix(code, 5));
        addRule(tags, code);
        return tags.isEmpty() ? Set.of() : Set.copyOf(tags);
    }

    /**
     * 이 사전이 키로 쓰는 분류 코드 전부.
     *
     * <p><b>테스트가 공식 코드표와 대조하기 위해 있다.</b> 코드를 한 글자 잘못 적으면 그 규칙은
     * 영원히 매칭되지 않고 <b>아무 오류도 내지 않는다</b> — 조용히 죽는 규칙이라 사람이 알아채기
     * 어렵다. {@code src/test/resources/tour/cat-codes.tsv}(4-7에서 API로 받아 온 원본)와 대조해
     * 존재하지 않는 코드를 잡는다.
     */
    static Set<String> mappedCodes() {
        return RULES.keySet();
    }

    private static void addRule(Set<StyleTag> target, String code) {
        Set<StyleTag> rule = code == null ? null : RULES.get(code);
        if (rule != null) {
            target.addAll(rule);
        }
    }

    /** @return 코드가 짧으면 {@code null} — 잘못된 입력에 부분 매칭이 걸리지 않게 한다 */
    private static String prefix(String code, int length) {
        return code.length() < length ? null : code.substring(0, length);
    }

    private static Map<String, Set<StyleTag>> buildRules() {
        Map<String, Set<StyleTag>> rules = new HashMap<>();

        // ── cat1: 모든 하위에 참인 것만 ──────────────────────────────────────
        put(rules, "A01", StyleTag.NATURE);                       // 자연
        put(rules, "A03", StyleTag.ACTIVITY);                     // 레포츠
        // A02(인문)에는 상위 태그가 없다 — 역사·문화시설·체험이 성격을 공유하지 않는다.

        // ── cat2 ────────────────────────────────────────────────────────────
        put(rules, "A0201", StyleTag.HISTORY);                    // 역사관광지
        put(rules, "A0203", StyleTag.ACTIVITY);                   // 체험관광지
        put(rules, "A0206", StyleTag.CULTURE, StyleTag.INDOOR);   // 문화시설
        put(rules, "A0207", StyleTag.LIVELY);                     // 축제
        put(rules, "A0208", StyleTag.CULTURE, StyleTag.INDOOR);   // 공연/행사
        put(rules, "A0303", StyleTag.NATURE);                     // 수상 레포츠
        put(rules, "A0304", StyleTag.GREAT_VIEW);                 // 항공 레포츠
        // A0202(휴양관광지)·A0204(산업관광지)·A0205(건축/조형물)는 하위가 서로 달라 비운다.

        // ── cat3: 자연 ──────────────────────────────────────────────────────
        put(rules, "A01010400", StyleTag.ACTIVITY, StyleTag.GREAT_VIEW);       // 산
        put(rules, "A01010500", StyleTag.QUIET);                               // 자연생태관광지
        put(rules, "A01010600", StyleTag.QUIET, StyleTag.UNCROWDED);           // 자연휴양림
        put(rules, "A01010700", StyleTag.QUIET);                               // 수목원
        put(rules, "A01010800", StyleTag.QUIET, StyleTag.UNCROWDED);           // 폭포
        put(rules, "A01010900", StyleTag.QUIET, StyleTag.UNCROWDED);           // 계곡
        put(rules, "A01011000", StyleTag.QUIET, StyleTag.UNCROWDED);           // 약수터
        put(rules, "A01011100", StyleTag.GREAT_VIEW);                          // 해안절경
        put(rules, "A01011200", StyleTag.GREAT_VIEW);                          // 해수욕장
        put(rules, "A01011300", StyleTag.GREAT_VIEW);                          // 섬
        put(rules, "A01011400", StyleTag.GREAT_VIEW);                          // 항구/포구
        put(rules, "A01011600", StyleTag.GREAT_VIEW);                          // 등대
        put(rules, "A01011700", StyleTag.QUIET);                               // 호수
        put(rules, "A01011800", StyleTag.QUIET);                               // 강
        put(rules, "A01011900", StyleTag.INDOOR);                              // 동굴
        put(rules, "A01020200", StyleTag.GREAT_VIEW);                          // 기암괴석

        // ── cat3: 역사관광지 ────────────────────────────────────────────────
        put(rules, "A02010100", StyleTag.HANOK);                               // 고궁
        put(rules, "A02010400", StyleTag.HANOK, StyleTag.QUIET);               // 고택
        put(rules, "A02010500", StyleTag.HANOK, StyleTag.QUIET);               // 생가
        put(rules, "A02010600", StyleTag.HANOK);                               // 민속마을
        put(rules, "A02010700", StyleTag.QUIET);                               // 유적지/사적지
        put(rules, "A02010800", StyleTag.HANOK, StyleTag.QUIET);               // 사찰
        put(rules, "A02010900", StyleTag.QUIET);                               // 종교성지

        // ── cat3: 휴양관광지 (상위가 비어 있어 여기서만 성격이 정해진다) ────
        put(rules, "A02020300", StyleTag.QUIET, StyleTag.INDOOR);              // 온천/욕장/스파
        put(rules, "A02020400", StyleTag.INDOOR);                              // 이색찜질방
        put(rules, "A02020500", StyleTag.ACTIVITY);                            // 헬스투어
        put(rules, "A02020600",                                                // 테마공원
            StyleTag.KID_FRIENDLY, StyleTag.ACTIVITY, StyleTag.LIVELY);
        put(rules, "A02020700", StyleTag.NATURE, StyleTag.WALKABLE);           // 공원
        put(rules, "A02020800", StyleTag.ACTIVITY, StyleTag.GREAT_VIEW);       // 유람선/잠수함관광

        // ── cat3: 체험관광지 ────────────────────────────────────────────────
        put(rules, "A02030100", StyleTag.KID_FRIENDLY);                        // 농.산.어촌 체험
        put(rules, "A02030200", StyleTag.KID_FRIENDLY, StyleTag.HISTORY);      // 전통체험
        put(rules, "A02030300", StyleTag.QUIET, StyleTag.HANOK);               // 산사체험
        put(rules, "A02030600", StyleTag.LIVELY, StyleTag.WALKABLE);           // 이색거리

        // ── cat3: 건축/조형물 (상위가 비어 있다) ────────────────────────────
        put(rules, "A02050100", StyleTag.GREAT_VIEW, StyleTag.NIGHT_VIEW);     // 다리/대교
        put(rules, "A02050200", StyleTag.GREAT_VIEW, StyleTag.NIGHT_VIEW);     // 기념탑/기념비/전망대
        put(rules, "A02050300", StyleTag.NIGHT_VIEW);                          // 분수
        put(rules, "A02050600", StyleTag.GREAT_VIEW);                          // 유명건물

        // ── cat3: 문화시설 ──────────────────────────────────────────────────
        put(rules, "A02060900", StyleTag.QUIET);                               // 도서관

        // ── cat3: 레포츠 ────────────────────────────────────────────────────
        put(rules, "A03020500", StyleTag.NATURE);                              // 자전거하이킹
        put(rules, "A03021000", StyleTag.INDOOR, StyleTag.LATE_NIGHT);         // 카지노
        put(rules, "A03021200", StyleTag.NATURE);                              // 스키/스노보드
        put(rules, "A03021700", StyleTag.NATURE, StyleTag.UNCROWDED);          // 야영장,오토캠핑장
        put(rules, "A03021800", StyleTag.NATURE);                              // 암벽등반
        put(rules, "A03022200", StyleTag.NATURE);                              // MTB
        put(rules, "A03022700", StyleTag.NATURE);                              // 트래킹
        put(rules, "A03030500", StyleTag.QUIET);                               // 민물낚시
        put(rules, "A03030600", StyleTag.QUIET);                               // 바다낚시

        return Map.copyOf(rules);
    }

    private static void put(Map<String, Set<StyleTag>> rules, String code, StyleTag... tags) {
        rules.put(code, Set.of(tags));
    }
}
