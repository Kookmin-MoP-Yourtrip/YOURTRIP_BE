package backend.yourtrip.global.naver;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.ai.candidate.StyleTag;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.naver.config.NaverConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link StyleTag}의 <b>검색어 표기가 실제로 후보를 끌어오는지</b> 재는 프로브 (ROADMAP 4-3 보강).
 *
 * <p>4-2는 서술어 매칭이 "작동한다"는 것만 4개 표본으로 확인했다. 그 뒤 4-3에서 검색 가능한 태그를
 * 26개로 늘렸는데 <b>나머지 22개는 표기가 추측</b>이다. 추측으로 채운 사전을 5단계까지 들고 가면,
 * 무효한 검색어가 쿼터만 쓰고 fail-open으로 조용히 무력화되어 <b>"안 되는 줄도 모르는" 상태</b>가 된다.
 *
 * <h2>세 가지를 잰다</h2>
 * <ol>
 *   <li><b>결과 건수</b> — 0건이면 그 표기는 죽은 쿼리다</li>
 *   <li><b>신규 비율</b> — 기본 쿼리에 없던 후보를 몇 개나 끌어오는가. 0이면 풀을 넓히지 못한다</li>
 *   <li><b>상호명 포함률</b> — 결과의 상호명에 그 단어가 들어 있는가. <b>높으면 오히려 나쁘다</b> —
 *       속성 검색이 아니라 이름 검색이라는 뜻이라, 스타일 축으로서는 반쪽이다</li>
 * </ol>
 *
 * <p>판정용이지 회귀 테스트가 아니므로 단언하지 않고 표로 덤프한다. {@code @Tag("benchmark")}.
 *
 * <pre>
 * ./gradlew benchmarkTest --tests "*StyleModifierProbeTest*" --rerun
 * </pre>
 */
@Tag("benchmark")
@DisplayName("스타일 modifier 검색어 실측 (ROADMAP 4-3 보강)")
class StyleModifierProbeTest {

    private static final String BASE_URL = "https://naverapihub.apigw.ntruss.com";
    private static final String AREA = "경주";

    /** 태그가 겨냥하는 슬롯. 관광 계열 태그를 카페로 재면 무효 판정이 나올 수밖에 없다. */
    private static final Map<SlotType, List<StyleTag>> TARGETS = Map.of(
        SlotType.CAFE, List.of(
            StyleTag.NIGHT_VIEW, StyleTag.GREAT_VIEW, StyleTag.ROOFTOP, StyleTag.PANORAMIC_WINDOW,
            StyleTag.HANOK, StyleTag.RETRO, StyleTag.SPACIOUS, StyleTag.COZY, StyleTag.QUIET,
            StyleTag.LIVELY, StyleTag.UNCROWDED, StyleTag.PET_FRIENDLY, StyleTag.MORNING_OPEN),
        SlotType.MEAL, List.of(
            StyleTag.NEAR_STATION, StyleTag.WALKABLE, StyleTag.PARKING_AVAILABLE,
            StyleTag.KID_FRIENDLY, StyleTag.GROUP_FRIENDLY, StyleTag.CHEAP, StyleTag.EXPENSIVE,
            StyleTag.LATE_NIGHT),
        SlotType.ATTRACTION, List.of(
            StyleTag.NATURE, StyleTag.HISTORY, StyleTag.CULTURE, StyleTag.ACTIVITY,
            StyleTag.INDOOR, StyleTag.GREAT_VIEW));

    /** 약한 표기를 대체해 볼 후보. 1차 결과가 나쁜 태그만 재시도한다. */
    private static final Map<StyleTag, List<String>> ALTERNATIVES = Map.of(
        StyleTag.PET_FRIENDLY, List.of("반려동물", "애견"),
        StyleTag.SPACIOUS, List.of("대형", "넓은곳"),
        StyleTag.WALKABLE, List.of("도보5분", "역근처"),
        StyleTag.MORNING_OPEN, List.of("아침식사", "모닝"),
        StyleTag.LATE_NIGHT, List.of("심야", "늦게까지"),
        StyleTag.INDOOR, List.of("실내체험", "우천"),
        StyleTag.KID_FRIENDLY, List.of("키즈", "아이와"),
        StyleTag.LIVELY, List.of("핫플", "번화가"),
        StyleTag.PANORAMIC_WINDOW, List.of("통유리", "창가"),
        StyleTag.UNCROWDED, List.of("한적", "숨은"));

    private NaverLocalClient client;

    @BeforeEach
    void setUp() throws IOException {
        String id = resolveEnv("NAVER_CLIENT_ID");
        String secret = resolveEnv("NAVER_CLIENT_SECRET");
        assumeTrue(id != null && !id.isBlank() && secret != null && !secret.isBlank(),
            "이 프로브는 실제 NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 이 필요하다");
        // 프로덕션 조립을 그대로 쓴다 — 여기서 따로 만들면 타임아웃·헤더가 갈라진다.
        client = new NaverLocalClient(NaverConfig.buildNaverWebClient(BASE_URL, id, secret));
    }

    @Test
    @DisplayName("검색 가능한 태그 전량의 결과 건수·신규 비율·상호명 포함률을 잰다")
    void 스타일_검색어를_전량_측정한다() {
        List<Row> rows = new ArrayList<>();

        for (SlotType slotType : List.of(SlotType.CAFE, SlotType.MEAL, SlotType.ATTRACTION)) {
            String hint = slotType.getSearchHint();
            Set<String> base = namesOf(AREA + " " + hint);
            System.out.printf("%n=== 기본 쿼리 [%s %s] %d건 ===%n  %s%n", AREA, hint, base.size(), base);

            for (StyleTag tag : TARGETS.get(slotType)) {
                // 이전 측정에서 비운 태그는 잴 것이 없다 — orElseThrow 로 두면 여기서 터진다.
                tag.searchTerm().ifPresent(term -> rows.add(measure(slotType, tag, term, base, false)));
            }
        }

        System.out.printf("%n%n=== [4-3보강] 1차 측정 — 사전에 든 표기 ===%n");
        printTable(rows);

        // 1차에서 약했던 태그만 대체 표기로 재시도한다.
        List<Row> retries = new ArrayList<>();
        for (Row row : rows) {
            if (!row.weak() || !ALTERNATIVES.containsKey(row.tag())) {
                continue;
            }
            String hint = row.slotType().getSearchHint();
            Set<String> base = namesOf(AREA + " " + hint);
            for (String alternative : ALTERNATIVES.get(row.tag())) {
                retries.add(measure(row.slotType(), row.tag(), alternative, base, true));
            }
        }

        if (!retries.isEmpty()) {
            System.out.printf("%n%n=== [4-3보강] 2차 측정 — 약한 태그의 대체 표기 ===%n");
            printTable(retries);
        }

        System.out.printf("%n=== 판정 기준 ===%n");
        System.out.println("  죽은 표기   : 결과 0건 또는 신규 0건 → searchTerm 을 비운다");
        System.out.println("  반쪽 표기   : 상호명 포함률이 높다 → 속성이 아니라 이름을 검색한 것이다");
        System.out.println("  쓸 만한 표기: 결과가 있고 신규가 있으며 상호명 포함률이 낮다");
        System.out.printf("%n  총 호출 %d회%n", calls);
    }

    /**
     * 1차·2차에서 <b>1건짜리로 통과한 얇은 표기</b>를 지역 3곳으로 다시 잰다.
     *
     * <p>두 가지를 분리하지 못한 것이 문제였다 — 결과가 1건인 이유가 <b>표기가 나쁜 것</b>인지
     * <b>그 지역에 그런 곳이 적은 것</b>인지. 앞 측정은 경주 한 곳이었다.
     *
     * <p>판정 기준도 올린다. {@code 신규 >= 2}를 넘지 못하면 비운다 — 슬롯당 후보 8~15건을
     * 목표로 하는 설계에서 1건 추가는 쿼리 한 번의 쿼터값을 못 한다.
     */
    @Test
    @DisplayName("얇은 표기를 지역 3곳으로 다시 재 표기 문제와 지역 특성을 분리한다")
    void 얇은_표기를_다지역으로_재측정한다() {
        record Candidate(SlotType slotType, StyleTag tag, List<String> terms) {}
        List<Candidate> candidates = List.of(
            new Candidate(SlotType.CAFE, StyleTag.UNCROWDED,
                List.of("한적", "한적함", "한적한", "숨은", "조용한골목")),
            new Candidate(SlotType.CAFE, StyleTag.COZY,
                List.of("아늑한", "아늑", "아늑함", "소품샵")),
            new Candidate(SlotType.MEAL, StyleTag.EXPENSIVE,
                List.of("고급", "고급스러운", "프리미엄", "파인다이닝")));
        List<String> areas = List.of("경주", "강릉", "부산");

        System.out.printf("%n%n=== [4-3보강] 3차 측정 — 얇은 표기 × 지역 3곳 ===%n");
        System.out.printf("%-14s %-12s %-8s %-6s %-6s %-6s %6s  %s%n",
            "태그", "검색어", "경주", "강릉", "부산", "", "신규합", "판정");

        for (Candidate candidate : candidates) {
            for (String term : candidate.terms()) {
                int total = 0;
                StringBuilder perArea = new StringBuilder();
                for (String area : areas) {
                    Set<String> base = namesOf(area + " " + candidate.slotType().getSearchHint());
                    Set<String> styled = namesOf(
                        area + " " + term + " " + candidate.slotType().getSearchHint());
                    Set<String> fresh = new LinkedHashSet<>(styled);
                    fresh.removeAll(base);
                    total += fresh.size();
                    perArea.append(String.format("%-6s ", styled.size() + "/" + fresh.size()));
                }
                System.out.printf("%-14s %-12s %s%6d  %s%n",
                    candidate.tag().name(), term, perArea, total,
                    total >= 2 * areas.size() ? "쓸만함" : total >= areas.size() ? "경계" : "비운다");
            }
            System.out.println();
        }
        System.out.println("  표기는 결과수/신규수. 판정은 3개 지역 신규 합계 기준");
        System.out.println("    쓸만함: 지역당 평균 2건 이상   경계: 평균 1건대   비운다: 그 미만");
        System.out.printf("%n  총 호출 %d회%n", calls);
    }

    /**
     * <b>표기가 슬롯을 넘나들 때 안전한지</b> 확인한다 (5-9가 쓸 도구).
     *
     * <p>5-8은 modifier를 전 슬롯에 적용하므로 {@code "야경 맛집"} 같은 조합이 생긴다.
     * <b>0건이면 fail-open 이라 무해하지만, 엉뚱한 결과가 나오면 그 슬롯 후보가 오염된다.</b>
     * 그래서 건수만이 아니라 <b>실제 상호명</b>을 찍어 사람이 판정할 수 있게 한다.
     *
     * <p><b>이 프로브가 {@code 소품샵}을 걸러냈다.</b> {@code "강릉 소품샵 맛집"}이
     * {@code "강릉 소품샵 카페"}와 완전히 같은 결과를 줬는데, 원인은 {@code 소품샵}이 스타일이 아니라
     * <b>업종</b>이라 슬롯 힌트와 경쟁했기 때문이다. 업종은 {@code SlotType}의 영역이므로 태그를
     * 되돌렸다. 남은 검증 대상은 {@code 뷰맛집}이다 — 카페 5/5인데 관광명소 0건이었다.
     */
    @Test
    @DisplayName("표기가 다른 슬롯에서 후보를 오염시키지 않는지 확인한다")
    void 표기를_슬롯_교차로_검증한다() {
        List<String> terms = List.of("뷰맛집", "야경");
        List<String> areas = List.of("경주", "부산");

        System.out.printf("%n%n=== [4-3보강] 표기 × 슬롯 교차 검증 ===%n");
        for (String term : terms) {
            for (SlotType slotType : List.of(SlotType.CAFE, SlotType.MEAL, SlotType.ATTRACTION)) {
                for (String area : areas) {
                    Set<String> base = namesOf(area + " " + slotType.getSearchHint());
                    Set<String> styled = namesOf(area + " " + term + " " + slotType.getSearchHint());
                    Set<String> fresh = new LinkedHashSet<>(styled);
                    fresh.removeAll(base);
                    System.out.printf("%n  [%s %s %s] 결과 %d / 신규 %d%n",
                        area, term, slotType.getSearchHint(), styled.size(), fresh.size());
                    System.out.println("    " + (styled.isEmpty() ? "(없음)" : styled));
                }
            }
        }
        System.out.println();
        System.out.println("  읽는 법: 0건이면 fail-open 으로 무해하다.");
        System.out.println("           슬롯이 달라도 결과가 같으면 그 표기는 슬롯 힌트를 밀어낸 것이다.");
        System.out.printf("%n  총 호출 %d회%n", calls);
    }

    // ── 측정 ────────────────────────────────────────────────────────────────

    private record Row(SlotType slotType, StyleTag tag, String term, int found, int fresh,
                       int nameHits, boolean alternative) {

        /** 풀을 넓히지 못하면 약한 표기다 — 결과가 있어도 전부 기본 쿼리와 겹치면 의미가 없다. */
        boolean weak() {
            return found == 0 || fresh == 0;
        }

        String verdict() {
            if (found == 0) {
                return "죽음(0건)";
            }
            if (fresh == 0) {
                return "무효(신규 0)";
            }
            if (nameHits >= fresh) {
                return "반쪽(이름검색)";
            }
            return "양호";
        }
    }

    private int calls;

    private Row measure(SlotType slotType, StyleTag tag, String term, Set<String> base,
        boolean alternative) {
        Set<String> styled = namesOf(AREA + " " + term + " " + slotType.getSearchHint());
        Set<String> fresh = new LinkedHashSet<>(styled);
        fresh.removeAll(base);
        int nameHits = (int) fresh.stream()
            .filter(name -> name.replace(" ", "").toLowerCase(Locale.ROOT)
                .contains(term.replace(" ", "").toLowerCase(Locale.ROOT)))
            .count();
        return new Row(slotType, tag, term, styled.size(), fresh.size(), nameHits, alternative);
    }

    private Set<String> namesOf(String query) {
        calls++;
        NaverLocalResult result = client.search(query, NaverLocalClient.MAX_DISPLAY);
        if (result instanceof NaverLocalResult.Found found) {
            Set<String> names = new LinkedHashSet<>();
            found.places().forEach(place -> names.add(place.name()));
            return names;
        }
        if (result instanceof NaverLocalResult.Failed failed) {
            System.out.printf("  !! 호출 실패 [%s] %s%n", query, failed.cause());
        }
        return Set.of();
    }

    private static void printTable(List<Row> rows) {
        System.out.printf("%-12s %-14s %-10s %5s %5s %6s  %s%n",
            "슬롯", "태그", "검색어", "결과", "신규", "이름일치", "판정");
        for (Row row : rows) {
            System.out.printf("%-12s %-14s %-10s %5d %5d %6d  %s%n",
                row.slotType(), row.tag().name(), row.term(),
                row.found(), row.fresh(), row.nameHits(), row.verdict());
        }
    }

    /** Spring 컨텍스트가 없어 spring-dotenv 가 동작하지 않으므로 환경변수 → .env 순으로 찾는다. */
    private static String resolveEnv(String key) throws IOException {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        Path dotEnv = Path.of(".env");
        if (!Files.exists(dotEnv)) {
            return null;
        }
        for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0 && trimmed.substring(0, eq).trim().equals(key)) {
                return trimmed.substring(eq + 1).trim();
            }
        }
        return null;
    }
}
