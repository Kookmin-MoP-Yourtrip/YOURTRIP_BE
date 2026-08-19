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
                String term = tag.searchTerm().orElseThrow();
                rows.add(measure(slotType, tag, term, base, false));
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
