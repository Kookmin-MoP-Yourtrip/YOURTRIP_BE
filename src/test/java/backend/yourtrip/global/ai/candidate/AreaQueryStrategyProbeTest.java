package backend.yourtrip.global.ai.candidate;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.LlmResponseParser;
import backend.yourtrip.global.ai.LlmRetryExecutor;
import backend.yourtrip.global.ai.agent.PlannerAgent;
import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.openai.OpenAiLlmClient;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.config.NaverConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code area} 표기 전략 실측 (이슈 #110). <b>OpenAI와 네이버·카카오를 실제로 호출한다.</b>
 *
 * <h2>왜 재는가</h2>
 * 6단계 프로브에서 Planner가 내는 자연어 {@code area}(`황리단길·대릉원 일대`)로는 네이버 지역검색이
 * <b>전 슬롯 0건</b>이라는 것이 드러났다. MEAL·CAFE·SHOPPING은 소스가 시더뿐이라 목록이 통째로 비고,
 * Curator는 전량을 {@code SUGGESTED}로 낸다 — 후보 공급 층이 절반만 작동하는 상태다.
 *
 * <p><b>설계의 두 요구가 충돌한다</b>: 후보 공급은 {@code area}를 쿼리 접두사로 쓰고, Planner 설계는
 * {@code area}를 자연어 그대로 두라고 한다(시군구로 바꾸면 day 단위 locality를 잃는다). 어느 쪽을
 * 굽힐지가 아니라 <b>어떤 정규화가 둘을 함께 살리는지</b>를 여기서 정한다.
 *
 * <h2>판정 기준 — 결과를 보기 전에 못 박는다</h2>
 * <ol>
 *   <li><b>0건 비율이 0%인 전략만</b> 후보로 남긴다 — 목록이 비는 것이 이 이슈의 본질이다</li>
 *   <li>그중 <b>거리 중앙값이 작은 쪽</b>을 고른다 — {@code LOCATION}은 건수만으로 이길 수 있는데,
 *       그러면 "그냥 도시명 쓰면 되지 않나"에 답할 수 없다. 거리 축이 그 답을 준다.
 *       <b>기준점을 둘(anchor · area 첫 지명) 두고 양쪽에서 모두 이기는 전략을 찾는다</b> —
 *       기준점 하나만 쓰면 그 이름으로 검색한 전략이 구조적으로 유리하다</li>
 *   <li>동률이면 <b>구현이 단순한 쪽</b> — 순수 문자열 변환 &gt; 스테이지 계약 변경</li>
 * </ol>
 *
 * <p><b>판정용이지 회귀 테스트가 아니다.</b> 단언을 걸지 않고 콘솔 표와 CSV로 덤프해 사람이 읽는다
 * ({@code CandidateRetrievalProbeTest}가 세운 형태).
 *
 * <p>호출 규모: LLM 6회(Planner) · 네이버 216회 · 카카오 24회. 네이버 일일 한도 25,000건의 1% 미만이다.
 *
 * <pre>{@code
 * ./gradlew benchmarkTest --tests '*AreaQueryStrategyProbeTest*' --rerun
 * }</pre>
 */
@Tag("benchmark")
@DisplayName("area 표기 전략 실측 (이슈 #110)")
class AreaQueryStrategyProbeTest {

    private static final Path RESULTS_DIR = Path.of("results");
    private static final Path CSV = RESULTS_DIR.resolve("area-query-strategy.csv");

    /** 하네스 지역 세트에서 유명 3 + 무인지 3. */
    private enum Tier {FAMOUS, MINOR}

    private record Region(String name, Tier tier) {}

    private static final List<Region> REGIONS = List.of(
        new Region("경주", Tier.FAMOUS),
        new Region("부산", Tier.FAMOUS),
        new Region("강릉", Tier.FAMOUS),
        new Region("순천", Tier.MINOR),
        new Region("공주", Tier.MINOR),
        new Region("영주", Tier.MINOR));

    private static final int DAYS = 2;

    /** 시더가 죽은 두 슬롯 + 관광 대조군. 전 슬롯을 돌면 호출이 두 배가 되는데 축은 같다. */
    private static final List<SlotType> SLOTS =
        List.of(SlotType.MEAL, SlotType.CAFE, SlotType.ATTRACTION);

    private static final List<KeywordType> KEYWORDS =
        List.of(KeywordType.COUPLE, KeywordType.HEALING);

    /**
     * 권역명을 쿼리 접두사로 바꾸는 방식들.
     *
     * <p><b>{@code AS_IS}와 {@code LOCATION}이 양 끝의 대조군이다</b> — 전자는 현재 동작이고, 후자는
     * locality를 통째로 버렸을 때의 상한이다. 가운데 넷이 "둘을 함께 살릴 수 있는가"를 묻는다.
     */
    private enum Strategy {
        /** 현재 동작. `황리단길·대릉원 일대` */
        AS_IS,
        /** 구분자 앞부분만. `황리단길 일대` */
        FIRST_SEGMENT,
        /** 앞부분 + 접미어 제거. `황리단길` */
        HEAD_TERM,
        /** Planner 가 낸 랜드마크. `대릉원` */
        ANCHOR,
        /** 도시명 + 랜드마크. `경주 대릉원` */
        LOCATION_ANCHOR,
        /** 도시명만 — locality 를 버린 상한. `경주` */
        LOCATION;

        String prefixOf(String location, PlannerDayPlan day) {
            return switch (this) {
                case AS_IS -> day.area();
                case FIRST_SEGMENT -> firstSegment(day.area());
                case HEAD_TERM -> stripSuffix(firstSegment(day.area()));
                case ANCHOR -> day.anchor();
                case LOCATION_ANCHOR -> location + " " + day.anchor();
                case LOCATION -> location;
            };
        }
    }

    /**
     * 권역명을 잇는 구분자. Planner 프롬프트가 예시로 준 것이 가운뎃점이라 그 변종까지 함께 본다 —
     * 모델이 어느 코드포인트를 쓸지는 우리가 정하지 못한다.
     */
    private static final char[] SEPARATORS = {'·', 'ㆍ', '･', '/', ','};

    /**
     * 권역을 가리키는 접미어. <b>{@code 거리}·{@code 길}은 넣지 않는다</b> — {@code 황리단길}처럼
     * 지명의 일부일 수 있고, 그걸 떼면 검색어가 무너진다.
     */
    private static final List<String> AREA_SUFFIXES = List.of("일대", "방면", "주변", "근처");

    /**
     * 전략 하나의 (권역 × 슬롯) 결과 한 줄.
     *
     * <p><b>거리를 기준점 둘에서 잰다.</b> anchor 하나만 쓰면 {@code ANCHOR} 전략이 구조적으로
     * 유리하다 — 그 이름으로 검색했으니 그 근처가 나오는 것이 당연하다. 같은 이유로 area 첫 지명을
     * 기준점으로 쓰면 {@code FIRST_SEGMENT}가 유리하다. <b>두 기준점에서 모두 이기는 전략이 있으면
     * 그것이 진짜 승자</b>이고, 없으면 편향이 확인된 것이라 판정 기준 3(구현 단순성)으로 넘어간다.
     */
    private record Row(Region region, int day, String area, String anchor, Strategy strategy,
                       SlotType slotType, String query, int count,
                       Double medianFromAnchor, Double medianFromArea) {}

    @Test
    @DisplayName("Planner 가 낸 자연어 area 를 여섯 전략으로 바꿔 확보 건수와 locality 를 잰다")
    void measure() throws IOException {
        String openAiKey = env("OPENAI_API_KEY");
        String naverId = env("NAVER_CLIENT_ID");
        String naverSecret = env("NAVER_CLIENT_SECRET");
        String kakaoKey = env("KAKAO_API_KEY");
        assumeTrue(openAiKey != null && naverId != null && naverSecret != null && kakaoKey != null,
            "OpenAI·네이버·카카오 키가 모두 있어야 실측할 수 있다");

        KakaoLocalClient kakaoClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));
        AreaGeocoder geocoder = new AreaGeocoder(kakaoClient);
        NaverLocalSeedSource seedSource = new NaverLocalSeedSource(new NaverLocalClient(
            NaverConfig.buildNaverWebClient("https://naverapihub.apigw.ntruss.com", naverId,
                naverSecret)));
        PlannerAgent planner = planner(openAiKey);

        List<Row> rows = new ArrayList<>();
        Map<GeocodeOutcome, Integer> areaOnlyGeocode = new EnumMap<>(GeocodeOutcome.class);

        for (Region region : REGIONS) {
            PlannerPlan plan = planner.plan(region.name(), DAYS, KEYWORDS,
                CourseDeadline.unbounded());
            System.out.printf("%n[Planner] %s%n", region.name());

            for (PlannerDayPlan day : plan.days()) {
                System.out.printf("  day %d: area=%s / anchor=%s%n",
                    day.day(), day.area(), day.anchor());

                // 전 전략이 공유하는 기준점 둘. 같은 좌표라야 거리 비교가 성립한다.
                GeocodeResult anchorPoint =
                    geocoder.geocode(region.name(), day.area(), day.anchor());
                // area 첫 지명의 좌표. anchor 기준 거리가 ANCHOR 전략에 기우는 것을 대조한다.
                GeocodeResult areaPoint =
                    geocoder.geocode(region.name(), null, firstSegment(day.area()));

                // anchor 없이 area 만으로 좌표가 잡히는지 — 손대지는 않고 기록만 한다(#110 결정 2).
                GeocodeResult areaOnly = geocoder.geocode(region.name(), day.area(), null);
                areaOnlyGeocode.merge(areaOnly.outcome(), 1, Integer::sum);

                for (Strategy strategy : Strategy.values()) {
                    String prefix = strategy.prefixOf(region.name(), day);
                    if (prefix == null || prefix.isBlank()) {
                        continue;
                    }
                    for (SlotType slotType : SLOTS) {
                        rows.add(measureOne(seedSource, region, day, strategy, slotType, prefix,
                            anchorPoint, areaPoint));
                    }
                }
            }
        }

        printStrategySummary(rows);
        printPerAreaDetail(rows);
        printGeocodeFallback(areaOnlyGeocode);
        writeCsv(rows);
    }

    private static Row measureOne(NaverLocalSeedSource seedSource, Region region,
        PlannerDayPlan day, Strategy strategy, SlotType slotType, String prefix,
        GeocodeResult anchorPoint, GeocodeResult areaPoint) {
        // 후보는 한 번만 받고 거리만 두 기준점으로 각각 계산한다 — 두 번 부르면 같은 표본을
        // 비교하는 것이 아니게 된다.
        // 전략끼리만 비교하는 측정이라 재질의는 끈다 — 켜면 어느 전략이든 도시 전체로
        // 살아나서 "권역명이 검색되는가" 라는 질문 자체가 사라진다.
        CandidateBatch batch = seedSource.fetch(prefix, null, slotType, null,
            latitudeOf(anchorPoint), longitudeOf(anchorPoint));

        return new Row(region, day.day(), day.area(), day.anchor(), strategy, slotType,
            NaverLocalSeedSource.buildQuery(prefix, slotType, null), batch.candidates().size(),
            medianDistance(batch, anchorPoint), medianDistance(batch, areaPoint));
    }

    /** 후보들의 기준점까지 거리 중앙값. 좌표를 못 얻은 기준점이면 빈 값이다. */
    private static Double medianDistance(CandidateBatch batch, GeocodeResult point) {
        if (!point.hasCoordinate()) {
            return null;
        }
        return median(batch.candidates().stream()
            .map(candidate -> backend.yourtrip.global.ai.route.GeoUtils.haversineKm(
                point.latitude(), point.longitude(),
                candidate.latitude(), candidate.longitude()))
            .toList());
    }

    private static Double latitudeOf(GeocodeResult point) {
        return point.hasCoordinate() ? point.latitude() : null;
    }

    private static Double longitudeOf(GeocodeResult point) {
        return point.hasCoordinate() ? point.longitude() : null;
    }

    // ── 출력 — 판정은 사람이 한다 ─────────────────────────────────────────────

    /** <b>이 표가 판정의 근거다.</b> 위의 세 기준을 이 표에 그대로 적용한다. */
    private static void printStrategySummary(List<Row> rows) {
        System.out.printf("%n%n=== 전략별 요약 (권역 %d × 슬롯 %d) ===%n",
            rows.stream().map(row -> row.region().name() + row.day()).distinct().count(),
            SLOTS.size());
        System.out.printf("%-17s %8s %10s %14s %14s%n",
            "전략", "평균건수", "0건비율", "anchor기준", "area기준");

        for (Strategy strategy : Strategy.values()) {
            List<Row> subset = rows.stream().filter(row -> row.strategy() == strategy).toList();
            if (subset.isEmpty()) {
                continue;
            }
            long empty = subset.stream().filter(row -> row.count() == 0).count();
            List<Double> fromAnchor = subset.stream()
                .map(Row::medianFromAnchor).filter(java.util.Objects::nonNull).toList();
            List<Double> fromArea = subset.stream()
                .map(Row::medianFromArea).filter(java.util.Objects::nonNull).toList();

            System.out.printf("%-17s %8.1f %9.0f%% %13s %13s%n",
                strategy,
                subset.stream().mapToInt(Row::count).average().orElse(0.0),
                empty * 100.0 / subset.size(),
                format(median(fromAnchor)),
                format(median(fromArea)));
        }
    }

    /** 슬롯별로 나눠 본다 — 관광 슬롯은 TourAPI 가 메워 결함이 가려지는 곳이라 따로 봐야 한다. */
    private static void printPerAreaDetail(List<Row> rows) {
        System.out.printf("%n=== 슬롯 × 전략 평균 건수 ===%n");
        System.out.printf("%-13s", "슬롯");
        for (Strategy strategy : Strategy.values()) {
            System.out.printf(" %15s", strategy);
        }
        System.out.println();

        for (SlotType slotType : SLOTS) {
            System.out.printf("%-13s", slotType);
            for (Strategy strategy : Strategy.values()) {
                double average = rows.stream()
                    .filter(row -> row.slotType() == slotType && row.strategy() == strategy)
                    .mapToInt(Row::count).average().orElse(0.0);
                System.out.printf(" %15.1f", average);
            }
            System.out.println();
        }
    }

    /**
     * anchor 없이 {@code area}만으로 지오코딩했을 때의 결말.
     *
     * <p>{@code AreaGeocoder}의 캐스케이드 2단계가 같은 결함을 갖는지를 보는 것이고,
     * <b>이번 작업에서 고치지는 않는다</b>(#110 결정 2). 값이 나쁘면 별도 이슈의 근거가 된다.
     */
    private static void printGeocodeFallback(Map<GeocodeOutcome, Integer> outcomes) {
        System.out.printf("%n=== anchor 없이 area 만으로 지오코딩한 결말 (측정만) ===%n");
        outcomes.forEach((outcome, count) -> System.out.printf("  %-20s %d%n", outcome, count));
    }

    private static void writeCsv(List<Row> rows) throws IOException {
        Files.createDirectories(RESULTS_DIR);
        StringBuilder csv = new StringBuilder(
            "region,tier,day,area,anchor,strategy,slot,query,count,median_from_anchor_km,median_from_area_km\n");
        for (Row row : rows) {
            csv.append("%s,%s,%d,%s,%s,%s,%s,%s,%d,%s,%s%n".formatted(
                row.region().name(), row.region().tier(), row.day(),
                quote(row.area()), quote(row.anchor()), row.strategy(), row.slotType(),
                quote(row.query()), row.count(),
                format(row.medianFromAnchor()), format(row.medianFromArea())));
        }
        Files.writeString(CSV, csv.toString(), StandardCharsets.UTF_8);
        System.out.printf("%n결과를 %s 에 남겼다 (%d행)%n", CSV.toAbsolutePath(), rows.size());
    }

    // ── 문자열 전략 — 승자가 정해지면 이 로직이 main 으로 옮겨간다 ────────────

    /** 구분자 앞부분. 구분자가 없으면 원문 그대로. */
    private static String firstSegment(String area) {
        if (area == null || area.isBlank()) {
            return area;
        }
        int cut = -1;
        for (char separator : SEPARATORS) {
            int index = area.indexOf(separator);
            if (index >= 0 && (cut < 0 || index < cut)) {
                cut = index;
            }
        }
        String head = cut < 0 ? area : area.substring(0, cut);
        return head.strip();
    }

    /** 권역 접미어 제거. 접미어만 남으면 원문을 지킨다 — 검색어를 없애는 것보다 낫다. */
    private static String stripSuffix(String area) {
        if (area == null || area.isBlank()) {
            return area;
        }
        String stripped = area.strip();
        for (String suffix : AREA_SUFFIXES) {
            if (stripped.endsWith(suffix) && stripped.length() > suffix.length()) {
                return stripped.substring(0, stripped.length() - suffix.length()).strip();
            }
        }
        return stripped;
    }

    // ── 조립·유틸 ────────────────────────────────────────────────────────────

    private static PlannerAgent planner(String apiKey) {
        AiLlmProperties properties = new AiLlmProperties("openai", 60_000, 2,
            new AiLlmProperties.Retry(3, 2, 0.5, 4.0, 0.3),
            Map.of(PlannerAgent.AGENT_NAME,
                new AiLlmProperties.Agent("gpt-5.6-luna", null, 2048, null)),
            new AiLlmProperties.OpenAi(apiKey, "https://api.openai.com"));

        OpenAiLlmClient llmClient = new OpenAiLlmClient(properties,
            new LlmResponseParser(new ObjectMapper()), new LlmRetryExecutor(properties),
            new AiCourseMetrics(new SimpleMeterRegistry()),
            OpenAiLlmClient.buildChatModel(properties.openai().baseUrl(), apiKey,
                properties.timeoutMs()));
        return new PlannerAgent(llmClient, new PromptLoader(), Runnable::run);
    }

    /** 정렬된 목록의 중앙값. 평균이 아닌 이유는 먼 이상치 하나가 locality 판정을 뒤집어서다. */
    private static Double median(List<Double> sorted) {
        if (sorted.isEmpty()) {
            return null;
        }
        List<Double> ordered = sorted.stream().sorted(Comparator.naturalOrder()).toList();
        int middle = ordered.size() / 2;
        return ordered.size() % 2 == 1
            ? ordered.get(middle)
            : (ordered.get(middle - 1) + ordered.get(middle)) / 2.0;
    }

    private static String format(Double value) {
        return value == null ? "-" : "%.2f".formatted(value);
    }

    private static String quote(String value) {
        return value == null ? "" : '"' + value.replace("\"", "'") + '"';
    }

    private static String env(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Path dotEnv = Path.of(".env");
        if (!Files.exists(dotEnv)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(key + "=")) {
                    String value = trimmed.substring(key.length() + 1).trim();
                    return value.isEmpty() ? null : value;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}
