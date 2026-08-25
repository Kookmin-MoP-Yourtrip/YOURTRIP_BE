package backend.yourtrip.global.ai.candidate;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.config.NaverConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 시더 후보의 <b>거리 상한</b>을 정하기 위한 실측 (이슈 #134). <b>네이버·카카오를 실제로 호출한다.</b>
 *
 * <h2>왜 재는가</h2>
 * AI 코스에 목적지에서 100km 넘게 떨어진 장소가 섞인다 — 8단계 E2E에서 83개 중 4개(순천→목포권
 * 102km, 통영→경북권 177km), #135 E2E에서 공주 day3에 칠곡 식당 138km. 마지막 건은 두 식사 사이
 * 이동이 9시간 25분이 되어 하루가 무너졌다.
 *
 * <p>누수 지점은 <b>네이버 시더뿐이다.</b> TourAPI는 좌표+반경 20km 질의라 구조적으로 못 새고
 * ({@code TourApiClient}), 시더만 텍스트 검색이라 지리적 제약이 없다. 그런데 <b>거리로 후보를
 * 탈락시키는 코드가 파이프라인 어디에도 없다</b> — {@code PlaceCandidate#distanceKm}은
 * {@code CandidateOrdering}의 정렬 키와 LLM 표시용으로만 쓰이고,
 * {@code CandidateMatcher#PROXIMITY_THRESHOLD_KM}은 중복 병합 판정용이라 목적이 다르다.
 *
 * <p>상한을 넣으려면 값이 필요하고, 그 값은 <b>정상 후보를 자르지 않으면서 사고를 막는</b> 구간에서
 * 골라야 한다. 이 프로브가 그 구간을 연다.
 *
 * <h2>행 단위가 후보 하나인 이유</h2>
 * {@code AreaQueryStrategyProbeTest}는 (권역 × 슬롯)당 <b>중앙값 한 줄</b>을 낸다. 상한은 분포의
 * <b>꼬리</b>에서 정해지므로 중앙값으로는 고를 수 없다. 그래서 여기서는 후보 하나가 한 행이고,
 * <b>주소를 함께 싣는다</b> — 40km짜리 "정상" 후보가 아무도 못 본 누수인지 넓은 시의 정상 후보인지는
 * 시군구를 사람이 읽어야 갈린다.
 *
 * <h2>표본 세 갈래</h2>
 * <ul>
 *   <li><b>{@code OBSERVED}</b> — 실제 Planner가 낸 (지역, area, anchor) 삼중항 32건.
 *       {@code AreaQueryStrategyProbeTest}에서 그대로 가져왔다(LLM 재호출 없음)</li>
 *   <li><b>{@code WIDE}</b> — 시 면적이 큰 여행지를 <b>도시명 질의로만</b> 보강한다. {@code OBSERVED}는
 *       강릉·경주·공주·부산·순천·영주뿐이라 제주급이 빠져 있는데, 넓은 권역의 위험은
 *       <b>{@code LOCATION} 단계에 집중</b>돼 있고(추가분 거리 중앙값 6.34km) 그 단계는 사용자 입력
 *       지명만 있으면 재현된다. {@code AREA}·{@code ANCHOR}는 본래 밀착(1.20/1.07km)이라 넓은 시에서도
 *       위험이 낮다 — 그래서 Planner를 부르지 않는다</li>
 *   <li><b>{@code INCIDENTS}</b> — 알려진 사고 3건. 질의가 지역 한정자를 잃은 형태가 둘이다:
 *       전국에 중복되는 동네명({@code 원도심}·{@code 도남동})과 보통명사({@code 주말농장})</li>
 * </ul>
 *
 * <h2>판정 기준 — 결과를 보기 전에 못 박는다</h2>
 * <ol>
 *   <li>정상 표본에서 <b>탈락 0건</b>이 되는 최소 cap을 하한 <b>L</b>로 잡는다. 단 표 A에서 사람이
 *       "주소 시군구가 {@code location}과 다르다"고 표시한 행은 <b>미발견 누수</b>이므로 정상 표본에서
 *       빼고 다시 계산한다</li>
 *   <li>사고 3건의 원거리 후보를 <b>전부</b> 차단하는 최대 cap을 상한 <b>U</b>로 잡는다</li>
 *   <li>{@code L < U}이면 <b>로그 스케일 중간</b> {@code ⌈√(L·U)/5⌉×5}를 고른다 — 두 실패 모드(정상
 *       탈락 / 사고 통과)에 같은 <b>비율</b>의 여유를 준다. 산술 중간이 아닌 이유는 두 실패의 비용
 *       비대칭이 배수 축에서 대칭이기 때문이다</li>
 *   <li>고른 cap에서 <b>"필터 후 0건 되는 칸"이 5% 미만</b>이어야 한다. 넘으면 폴백 호출 증가가 지연
 *       예산을 먹으므로 U 쪽으로 한 칸(5km) 올린다</li>
 *   <li><b>{@code L ≥ U}이면 멈춘다.</b> 단일 상수 전제가 깨진 것이다. 이때 {@code SeedScope}별·
 *       {@code GeocodeOutcome}별로 상수를 쪼개는 것은 <b>금지</b>다 — 이슈로 되돌려 다른 축(주소
 *       시군구 일치 검증)을 재설계한다</li>
 * </ol>
 *
 * <p><b>판정용이지 회귀 테스트가 아니다.</b> 단언을 걸지 않고 콘솔 표와 CSV로 덤프해 사람이 읽는다
 * ({@code CandidateRetrievalProbeTest}가 세운 형태).
 *
 * <p><b>{@link SeedDistanceLimit}이 들어간 뒤로는 이 프로브가 필터를 통과한 뒤의 세상을 잰다.</b>
 * 도입 전 측정값(2026-08-25)은 문서에 남아 있고, 표 C의 "정상탈락 0 / 사고차단 0"은 필터가 이미
 * 일했다는 뜻이지 잴 것이 없다는 뜻이 아니다.
 *
 * <p>상한을 다시 정하려면 {@code SeedDistanceLimit.MAX_ANCHOR_DISTANCE_KM}을 크게 올리거나
 * {@code CAP_SWEEP}을 그 위로 넓혀 <b>필터 없는 분포를 복원</b>해야 한다 — 지금 그대로 돌리면
 * 이미 걸러진 표본 위에서 상한을 고르게 되어 순환 논증이 된다.
 *
 * <p>호출 규모: 네이버 250~350회(일일 25,000의 1.4% 미만) · 카카오 ~40회 · <b>LLM 0회</b>.
 *
 * <pre>{@code
 * ./gradlew benchmarkTest --tests '*SeedDistanceCapProbeTest*' --rerun
 * }</pre>
 */
@Tag("benchmark")
@DisplayName("시더 거리 상한 실측 (이슈 #134)")
class SeedDistanceCapProbeTest {

    private static final Path RESULTS_DIR = Path.of("results");
    private static final Path CSV = RESULTS_DIR.resolve("seed-distance-cap.csv");

    /** 표본의 갈래. 정상과 사고를 섞어 집계하면 상한이 양쪽 모두에서 틀린 값이 된다. */
    private enum Kind {NORMAL, WIDE, INCIDENT}

    /**
     * 관측된 (지역, area, anchor) 삼중항. {@code area}·{@code anchor}가 {@code null}이면 도시명
     * 질의만 재는 표본이다({@code WIDE}).
     */
    private record Case(Kind kind, String location, String area, String anchor) {}

    /** {@code AreaQueryStrategyProbeTest#OBSERVED} 그대로. Planner 를 다시 부르지 않으려고 고정했다. */
    private static final List<Case> OBSERVED = List.of(
        new Case(Kind.NORMAL, "강릉", "경포호·경포해변 일대", "경포호"),
        new Case(Kind.NORMAL, "강릉", "경포호·초당동 일대", "경포호"),
        new Case(Kind.NORMAL, "강릉", "안목해변·송정동 일대", "안목해변"),
        new Case(Kind.NORMAL, "강릉", "안목해변·커피거리 일대", "안목해변"),
        new Case(Kind.NORMAL, "강릉", "안목해변·해안 산책로 일대", "안목해변"),
        new Case(Kind.NORMAL, "경주", "교촌·월정교 일대", "월정교"),
        new Case(Kind.NORMAL, "경주", "교촌마을·월정교 일대", "월정교"),
        new Case(Kind.NORMAL, "경주", "보문호·보문관광단지 일대", "보문호"),
        new Case(Kind.NORMAL, "경주", "황리단길·대릉원 일대", "대릉원"),
        new Case(Kind.NORMAL, "공주", "공산성·금강변 일대", "공산성"),
        new Case(Kind.NORMAL, "공주", "무령왕릉·국립공주박물관 일대", "무령왕릉"),
        new Case(Kind.NORMAL, "공주", "무령왕릉·송산리 고분군 일대", "무령왕릉"),
        new Case(Kind.NORMAL, "공주", "무령왕릉·송산리 일대", "무령왕릉"),
        new Case(Kind.NORMAL, "공주", "송산리 고분군·박물관 일대", "무령왕릉"),
        new Case(Kind.NORMAL, "공주", "송산리고분군·국립공주박물관 일대", "무령왕릉"),
        new Case(Kind.NORMAL, "공주", "송산리고분군·박물관 일대", "무령왕릉"),
        new Case(Kind.NORMAL, "공주", "송산리고분군·박물관 일대", "송산리 고분군"),
        new Case(Kind.NORMAL, "부산", "광안리·민락수변공원 일대", "광안대교"),
        new Case(Kind.NORMAL, "부산", "광안리·민락수변공원 일대", "광안리해수욕장"),
        new Case(Kind.NORMAL, "부산", "달맞이길·청사포 일대", "달맞이길"),
        new Case(Kind.NORMAL, "부산", "해운대 해변·달맞이길 일대", "해운대해수욕장"),
        new Case(Kind.NORMAL, "부산", "해운대·동백섬 일대", "동백섬"),
        new Case(Kind.NORMAL, "순천", "순천만국가정원·오천그린광장 일대", "순천만국가정원"),
        new Case(Kind.NORMAL, "순천", "순천만국가정원·오천동 일대", "순천만국가정원"),
        new Case(Kind.NORMAL, "순천", "순천만습지 일대", "순천만습지"),
        new Case(Kind.NORMAL, "순천", "순천만습지·대대동 갈대밭 일대", "순천만습지"),
        new Case(Kind.NORMAL, "순천", "순천만습지·대대동 일대", "순천만습지"),
        new Case(Kind.NORMAL, "영주", "무섬마을 일대", "무섬마을"),
        new Case(Kind.NORMAL, "영주", "부석사·봉황산 방면", "부석사"),
        new Case(Kind.NORMAL, "영주", "선비촌·소수서원 일대", "소수서원"),
        new Case(Kind.NORMAL, "영주", "소수서원·선비촌 일대", "소수서원"));

    /**
     * 시 면적이 큰 여행지 — <b>도시명 질의만</b> 잰다.
     *
     * <p>{@code LOCATION} 단계는 설계상 도시 전역이라 상한에 가장 먼저 걸린다. 이 단계가 무력화되면
     * "0건일 때는 없는 것보다 낫다"고 넣은 마지막 관문이 다시 0건이 된다 — 그 위험이 실재하는지를
     * 여기서 본다. 제주·삼척은 시 경계 안에서만 수십 km가 벌어지는 대표 사례다.
     */
    private static final List<Case> WIDE = List.of(
        new Case(Kind.WIDE, "제주", null, null),
        new Case(Kind.WIDE, "삼척", null, null),
        new Case(Kind.WIDE, "통영", null, null),
        new Case(Kind.WIDE, "서울", null, null));

    /**
     * 알려진 사고 3건.
     *
     * <p>순천 케이스는 {@code OBSERVED}에 있던 것을 사고로 옮겼다 — {@code 원도심·문화의거리 일대}가
     * 정규화되면 {@code 원도심}이 되고, 그 이름은 전국 모든 도시에 있다. 통영의 {@code 도남동}도 같은
     * 형태(법정동명은 시·도를 넘어 중복된다)이고, 공주의 {@code 주말농장}은 아예 지명이 아니다.
     */
    private static final List<Case> INCIDENTS = List.of(
        new Case(Kind.INCIDENT, "순천", "원도심·문화의거리 일대", "순천부읍성"),
        new Case(Kind.INCIDENT, "통영", "도남동·통영항 일대", "통영케이블카"),
        new Case(Kind.INCIDENT, "공주", "주말농장 일대", "무령왕릉"));

    /**
     * 재는 슬롯. <b>{@code ATTRACTION}을 뺄 수 없다</b> — 관광 슬롯은 TourAPI가 메워 결함이 가려지지만
     * <b>시더는 그 슬롯에서도 돈다.</b> 시더 전용 3슬롯으로만 상한을 튜닝하면 표본이 반쪽이 된다.
     */
    private static final List<SlotType> SLOTS =
        List.of(SlotType.MEAL, SlotType.CAFE, SlotType.SHOPPING, SlotType.ATTRACTION);

    /** {@code CandidateRetrievalStage.ANCHOR_FILL_THRESHOLD}와 같은 값이어야 시뮬레이션이 실동작이 된다. */
    private static final int ANCHOR_FILL_THRESHOLD = 3;

    /** {@code CandidateRetrievalStage.LOCATION_FILL_THRESHOLD}와 같은 값. 0건일 때만 탄다는 뜻이다. */
    private static final int LOCATION_FILL_THRESHOLD = 1;

    /** 표 C가 훑을 상한 후보값. 정상 최대(27.1km)와 사고 최소(102km)를 모두 감싸는 범위다. */
    private static final List<Double> CAP_SWEEP =
        List.of(20.0, 25.0, 30.0, 35.0, 40.0, 45.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0);

    /** 표 A에서 사람이 눈으로 훑을 최원거리 표본 수. */
    private static final int FARTHEST_SAMPLE = 20;

    /** 후보 하나 = 한 행. 상한은 분포의 꼬리에서 정해지므로 집계 전 원본이 남아야 한다. */
    private record Row(Kind kind, String location, String area, GeocodeOutcome geocode,
                       SeedScope rung, SlotType slot, String query,
                       String placeName, String placeAddress, double distanceKm) {}

    /** (표본, 슬롯) 한 칸의 단계별 후보. 표 C가 "상한을 뒀다면" 을 여기서 다시 돌린다. */
    private record Cell(Case source, SlotType slot, List<PlaceCandidate> areaRung,
                        List<PlaceCandidate> anchorRung, List<PlaceCandidate> locationRung) {}

    @Test
    @DisplayName("정상·넓은권역·사고 표본의 후보 거리를 전수로 재고 상한 후보값을 훑는다 (LLM 호출 없음)")
    void measureDistanceCap() throws IOException {
        String naverId = env("NAVER_CLIENT_ID");
        String naverSecret = env("NAVER_CLIENT_SECRET");
        String kakaoKey = env("KAKAO_API_KEY");
        assumeTrue(naverId != null && naverSecret != null && kakaoKey != null,
            "네이버·카카오 키가 있어야 실측할 수 있다");

        KakaoLocalClient kakaoClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));
        AreaGeocoder geocoder = new AreaGeocoder(kakaoClient);
        NaverLocalSeedSource seedSource = new NaverLocalSeedSource(new NaverLocalClient(
            NaverConfig.buildNaverWebClient("https://naverapihub.apigw.ntruss.com", naverId,
                naverSecret)), new AiCourseMetrics(new SimpleMeterRegistry()));

        Map<String, List<PlaceCandidate>> cache = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();

        List<Case> all = new ArrayList<>();
        all.addAll(OBSERVED);
        all.addAll(WIDE);
        all.addAll(INCIDENTS);

        for (Case one : all) {
            GeocodeResult point = geocoder.geocode(one.location(), one.area(), one.anchor());
            Double lat = latitudeOf(point);
            Double lon = longitudeOf(point);

            for (SlotType slot : SLOTS) {
                List<PlaceCandidate> areaRung = seedCached(cache, seedSource,
                    AreaQueryNormalizer.toSearchTerm(one.area()), slot, lat, lon);
                List<PlaceCandidate> anchorRung = seedCached(cache, seedSource,
                    AreaQueryNormalizer.toSearchTerm(one.anchor()), slot, lat, lon);
                List<PlaceCandidate> locationRung =
                    seedCached(cache, seedSource, one.location(), slot, lat, lon);

                cells.add(new Cell(one, slot, areaRung, anchorRung, locationRung));
                collect(rows, one, point, SeedScope.AREA, slot,
                    AreaQueryNormalizer.toSearchTerm(one.area()), areaRung);
                collect(rows, one, point, SeedScope.ANCHOR, slot,
                    AreaQueryNormalizer.toSearchTerm(one.anchor()), anchorRung);
                collect(rows, one, point, SeedScope.LOCATION, slot,
                    one.location(), locationRung);
            }
        }

        printDistanceDistribution(rows);
        printIncidents(rows, seedSource, geocoder);
        printCapSweep(rows, cells);
        writeCsv(rows);
    }

    /** 후보 목록을 행으로 편다. <b>거리를 못 구한 후보는 버린다</b> — 상한 판정의 대상이 아니다. */
    private static void collect(List<Row> sink, Case source, GeocodeResult point, SeedScope rung,
        SlotType slot, String query, List<PlaceCandidate> candidates) {
        for (PlaceCandidate candidate : candidates) {
            if (candidate.distanceKm() == null) {
                continue;
            }
            sink.add(new Row(source.kind(), source.location(), source.area(), point.outcome(),
                rung, slot, query, candidate.name(), candidate.address(),
                candidate.distanceKm()));
        }
    }

    // ── 표 A. 정상 후보 거리 분포 ─────────────────────────────────────────────

    /**
     * 단계별 분포와 <b>가장 먼 {@value #FARTHEST_SAMPLE}건</b>.
     *
     * <p>최원거리 목록이 이 표의 핵심이다 — 판정 기준 1이 요구하는 "미발견 누수 골라내기"는 주소를
     * 사람이 읽어야만 되고, 그 판단이 하한 {@code L}을 정한다.
     */
    private static void printDistanceDistribution(List<Row> rows) {
        System.out.printf("%n=== 표 A. 후보 거리 분포 (행 %d개) ===%n", rows.size());
        System.out.printf("%-10s %-10s %6s %8s %8s %8s %8s%n",
            "갈래", "단계", "건수", "중앙값", "p90", "p95", "최대");
        System.out.println("-".repeat(64));

        for (Kind kind : Kind.values()) {
            for (SeedScope rung : SeedScope.values()) {
                List<Double> distances = rows.stream()
                    .filter(row -> row.kind() == kind && row.rung() == rung)
                    .map(Row::distanceKm)
                    .sorted(Comparator.naturalOrder())
                    .toList();
                if (distances.isEmpty()) {
                    continue;
                }
                System.out.printf("%-10s %-10s %6d %8s %8s %8s %8s%n", kind, rung,
                    distances.size(), format(percentile(distances, 0.50)),
                    format(percentile(distances, 0.90)), format(percentile(distances, 0.95)),
                    format(distances.get(distances.size() - 1)));
            }
        }

        System.out.printf("%n--- 정상·넓은권역 표본 중 가장 먼 %d건 ---%n", FARTHEST_SAMPLE);
        System.out.println("  주소 시군구가 여행지와 다르면 '미발견 누수'다 — 판정 기준 1에서 제외한다.");
        System.out.printf("%n%8s %-7s %-9s %-22s %-24s %s%n",
            "거리km", "지역", "단계", "질의", "장소", "주소");
        System.out.println("-".repeat(110));
        rows.stream()
            .filter(row -> row.kind() != Kind.INCIDENT)
            .sorted(Comparator.comparingDouble(Row::distanceKm).reversed())
            .limit(FARTHEST_SAMPLE)
            .forEach(row -> System.out.printf("%8.2f %-7s %-9s %-22s %-24s %s%n",
                row.distanceKm(), row.location(), row.rung(), truncate(row.query(), 22),
                truncate(row.placeName(), 24), row.placeAddress()));
    }

    // ── 표 B. 사고 3건 전수 ───────────────────────────────────────────────────

    /**
     * 사고 질의가 실제로 무엇을 돌려주는지 전수로 본다.
     *
     * <p>뒤이어 <b>폴백을 켠 판</b>을 한 번 더 돌린다. 공주 사건의 핵심은 "먼 후보 5건이 정원을 채워
     * {@code anchor} 단계가 발동하지 않았다"였으므로, 이 판이 <b>필터가 그 정원을 비워 폴백을
     * 되살렸는지</b>를 값으로 보여준다.
     */
    private static void printIncidents(List<Row> rows, NaverLocalSeedSource seedSource,
        AreaGeocoder geocoder) {
        System.out.printf("%n%n=== 표 B. 사고 표본 전수 ===%n");
        System.out.printf("%n%8s %-7s %-9s %-22s %-26s %s%n",
            "거리km", "지역", "단계", "질의", "장소", "주소");
        System.out.println("-".repeat(112));
        rows.stream()
            .filter(row -> row.kind() == Kind.INCIDENT)
            .sorted(Comparator.comparing(Row::location)
                .thenComparing(Row::rung)
                .thenComparing(Comparator.comparingDouble(Row::distanceKm).reversed()))
            .forEach(row -> System.out.printf("%8.2f %-7s %-9s %-22s %-26s %s%n",
                row.distanceKm(), row.location(), row.rung(), truncate(row.query(), 22),
                truncate(row.placeName(), 26), row.placeAddress()));

        System.out.printf("%n--- 폴백을 켠 실제 경로 (SeedDistanceLimit 적용 결과) ---%n");
        System.out.printf("%n%-7s %-9s %6s %8s  %s%n", "지역", "슬롯", "건수", "최대km", "장소");
        System.out.println("-".repeat(96));
        for (Case incident : INCIDENTS) {
            GeocodeResult point =
                geocoder.geocode(incident.location(), incident.area(), incident.anchor());
            List<NaverLocalSeedSource.Fallback> rungs = List.of(
                new NaverLocalSeedSource.Fallback(incident.anchor(), ANCHOR_FILL_THRESHOLD,
                    SeedScope.ANCHOR),
                new NaverLocalSeedSource.Fallback(incident.location(), LOCATION_FILL_THRESHOLD,
                    SeedScope.LOCATION));

            for (SlotType slot : SLOTS) {
                List<PlaceCandidate> actual = seedSource.fetch(incident.area(), rungs, slot, null,
                    latitudeOf(point), longitudeOf(point)).candidates();
                double farthest = actual.stream()
                    .filter(candidate -> candidate.distanceKm() != null)
                    .mapToDouble(PlaceCandidate::distanceKm)
                    .max()
                    .orElse(0.0);
                String names = actual.stream().map(PlaceCandidate::name).limit(3)
                    .reduce((left, right) -> left + ", " + right).orElse("(없음)");
                System.out.printf("%-7s %-9s %6d %8.2f  %s%n",
                    incident.location(), slot, actual.size(), farthest, names);
            }
        }
    }

    // ── 표 C. 상한 후보값 스윕 — 판정표 ───────────────────────────────────────

    /**
     * 상한을 뒀다면 무엇이 떨어지고 폴백이 얼마나 더 도는가.
     *
     * <p><b>폴백 재발동을 세는 것이 이 표의 값어치다.</b> 필터는 {@code merged.size()}를 단조
     * 감소시키므로 단계 발동이 단조 증가하는데, 단계는 {@code fetch()} 안에서 <b>순차</b>로 돌아
     * 지연에 그대로 실린다. 판정 기준 4가 요구하는 "0건 되는 칸 5% 미만"을 여기서 읽는다.
     */
    private static void printCapSweep(List<Row> rows, List<Cell> cells) {
        long baselineAnchor = countAnchorRefills(cells, Double.MAX_VALUE);
        long baselineLocation = countLocationRefills(cells, Double.MAX_VALUE);
        long normalTotal = rows.stream().filter(row -> row.kind() != Kind.INCIDENT).count();
        long incidentTotal = rows.stream().filter(row -> row.kind() == Kind.INCIDENT).count();

        System.out.printf("%n%n=== 표 C. 상한 후보값 스윕 (칸 %d개 = 표본 %d x 슬롯 %d) ===%n",
            cells.size(), OBSERVED.size() + WIDE.size() + INCIDENTS.size(), SLOTS.size());
        System.out.printf("  기준선(상한 없음): anchor 재질의 %d칸 · location 재질의 %d칸%n",
            baselineAnchor, baselineLocation);
        System.out.printf("%n%6s %10s %8s %10s %12s %12s %10s%n",
            "cap", "정상탈락", "탈락률", "사고차단", "3건미만칸", "0건칸", "추가호출");
        System.out.println("-".repeat(76));

        for (double cap : CAP_SWEEP) {
            long normalDropped = rows.stream()
                .filter(row -> row.kind() != Kind.INCIDENT && row.distanceKm() > cap).count();
            long incidentBlocked = rows.stream()
                .filter(row -> row.kind() == Kind.INCIDENT && row.distanceKm() > cap).count();
            long anchorRefills = countAnchorRefills(cells, cap);
            long locationRefills = countLocationRefills(cells, cap);
            long extraCalls = (anchorRefills - baselineAnchor) + (locationRefills - baselineLocation);

            System.out.printf("%6.0f %10d %7.1f%% %10d %12d %12d %10d%n", cap, normalDropped,
                normalTotal == 0 ? 0.0 : normalDropped * 100.0 / normalTotal,
                incidentBlocked, anchorRefills, locationRefills, extraCalls);
        }
        System.out.printf("%n  정상 표본 %d행 · 사고 표본 %d행%n", normalTotal, incidentTotal);
        System.out.println("  '3건미만칸'이 anchor 재질의 발동 수, '0건칸'이 location 재질의 발동 수다.");
    }

    /** 상한을 적용한 뒤 {@code area} 단계가 {@value #ANCHOR_FILL_THRESHOLD}건에 못 미치는 칸 수. */
    private static long countAnchorRefills(List<Cell> cells, double cap) {
        return cells.stream()
            .filter(cell -> merged(cap, cell.areaRung()).size() < ANCHOR_FILL_THRESHOLD)
            .count();
    }

    /** 상한을 적용하고 {@code anchor}까지 모아도 0건인 칸 수 — {@code location} 단계가 도는 자리다. */
    private static long countLocationRefills(List<Cell> cells, double cap) {
        return cells.stream()
            .filter(cell -> merged(cap, cell.areaRung()).size() < ANCHOR_FILL_THRESHOLD)
            .filter(cell -> merged(cap, cell.areaRung(), cell.anchorRung()).size()
                < LOCATION_FILL_THRESHOLD)
            .count();
    }

    /**
     * 상한을 적용해 병합한 결과. 실제 캐스케이드와 같은 순서·같은 중복 제거를 쓴다 — 여기가 어긋나면
     * 표 C의 폴백 수치가 실동작을 예측하지 못한다.
     */
    @SafeVarargs
    private static List<PlaceCandidate> merged(double cap, List<PlaceCandidate>... rungs) {
        List<PlaceCandidate> all = new ArrayList<>();
        for (List<PlaceCandidate> rung : rungs) {
            for (PlaceCandidate candidate : rung) {
                // 거리를 모르는 후보는 상한이 판정하지 않는다 — anchor 좌표가 없을 때의 fail-open과 같다.
                if (candidate.distanceKm() == null || candidate.distanceKm() <= cap) {
                    all.add(candidate);
                }
            }
        }
        return CandidateMerger.dedupeWithinSource(all);
    }

    // ── 조립·유틸 ────────────────────────────────────────────────────────────

    private static List<PlaceCandidate> seedCached(Map<String, List<PlaceCandidate>> cache,
        NaverLocalSeedSource source, String term, SlotType slot, Double lat, Double lon) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        return cache.computeIfAbsent(term + "|" + slot + "|" + lat,
            key -> source.fetch(term, List.of(), slot, null, lat, lon).candidates());
    }

    private static Double latitudeOf(GeocodeResult point) {
        return point.hasCoordinate() ? point.latitude() : null;
    }

    private static Double longitudeOf(GeocodeResult point) {
        return point.hasCoordinate() ? point.longitude() : null;
    }

    /**
     * 오름차순 목록의 백분위. <b>평균을 쓰지 않는 이유는 상한이 꼬리에서 정해지기 때문</b>이고,
     * 중앙값만으로는 그 꼬리가 안 보여 p90·p95를 함께 낸다.
     */
    private static Double percentile(List<Double> sorted, double ratio) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(ratio * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static void writeCsv(List<Row> rows) throws IOException {
        Files.createDirectories(RESULTS_DIR);
        StringBuilder csv = new StringBuilder(
            "kind,location,area,geocode,rung,slot,query,place,address,distance_km\n");
        for (Row row : rows) {
            csv.append("%s,%s,%s,%s,%s,%s,%s,%s,%s,%.3f%n".formatted(
                row.kind(), row.location(), quote(row.area()), row.geocode(), row.rung(),
                row.slot(), quote(row.query()), quote(row.placeName()),
                quote(row.placeAddress()), row.distanceKm()));
        }
        Files.writeString(CSV, csv.toString(), StandardCharsets.UTF_8);
        System.out.printf("%n결과를 %s 에 남겼다 (%d행)%n", CSV.toAbsolutePath(), rows.size());
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "-";
        }
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
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
