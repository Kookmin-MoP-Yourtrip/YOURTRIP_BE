package backend.yourtrip.global.tour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.tour.config.TourApiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

/**
 * 한국관광공사 TourAPI <b>실호출 확정</b> 프로브 (ROADMAP 4-7).
 *
 * <p>{@code NaverLocalProbeTest}(4-2)와 같은 역할이고 같은 관례를 따른다 — 판정용이지 회귀
 * 테스트가 아니라서 단언은 "이 설계가 성립하려면 반드시 참이어야 하는 것"에만 걸고, 나머지는
 * {@code System.out}으로 덤프해 사람이 읽는다. {@code @Tag("benchmark")}로 일반 빌드에서 제외한다.
 *
 * <p><b>결과가 설계를 가르는 항목이 둘</b>이다.
 * <ul>
 *   <li><b>분류체계</b> — 4-9의 {@code cat3} → 스타일 태그 사전이 통째로 이걸 전제한다.
 *       {@code KorService1} 중지(2025-08-10) 이후 {@code cat1~3}이 살아 있는지, 새 체계
 *       ({@code lclsSystm})로 갈아탔는지가 사전의 입력을 결정한다</li>
 *   <li><b>무인지 지역 커버리지</b> — 4-10이 측정한 대로 무인지 지역일수록 파라메트릭이 약하다.
 *       거기서 TourAPI가 비면 이 소스를 채택한 이유 자체가 사라진다</li>
 * </ul>
 *
 * <p><b>{@code serviceKey} 이중 인코딩</b>도 여기서 확정한다. 공공데이터포털은 키를 Encoding /
 * Decoding 두 형태로 발급하는데, Encoding 키(`%2B` 등을 포함)를 {@code UriBuilder.queryParam}에
 * 넣으면 {@code %}가 다시 {@code %25}로 인코딩돼 <b>인증이 통째로 실패한다.</b> 4-2에서 겪은
 * "테스트는 전부 통과하는데 실호출만 죽는" 결함과 같은 부류라 구현 전에 못 박는다.
 *
 * <pre>
 * ./gradlew benchmarkTest --tests "*TourApiProbeTest*" --rerun
 * </pre>
 */
@Tag("benchmark")
@DisplayName("TourAPI 실호출 확정 (ROADMAP 4-7)")
class TourApiProbeTest {

    /** {@code KorService1}은 2025-08-10 부로 중지됐다. */
    private static final String BASE_URL = "https://apis.data.go.kr/B551011/KorService2";
    private static final String LOCATION_BASED = "/locationBasedList2";

    /** 카카오·네이버와 같은 값으로 둔다 — 4-7이 값을 정할 때 이 조건에서 잰 지연이 근거가 된다. */
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    /** 설계가 정한 값. 튜닝 파라미터가 아니라 <b>최대 고정 울타리</b>이고 실질 필터는 거리순 + cap이다. */
    private static final int RADIUS_METERS = 20_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 4-10의 티어 구분과 같은 지역 세트. 좌표는 각 권역의 대표 지점이다. */
    private static final List<Region> REGIONS = List.of(
        new Region("경주 황리단길", "FAMOUS", 129.2094, 35.8347),
        new Region("부산 해운대", "FAMOUS", 129.1603, 35.1631),
        new Region("삼척 죽서루", "MINOR", 129.1656, 37.4450),
        new Region("공주 공산성", "MINOR", 127.1190, 36.4650),
        new Region("영주 부석사", "MINOR", 128.6870, 36.9930));

    private String serviceKey;
    private WebClient tour;
    private final List<Long> latenciesMs = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        serviceKey = resolveEnv("TOUR_API_KEY");
        assumeTrue(serviceKey != null && !serviceKey.isBlank(),
            "이 프로브는 실제 TOUR_API_KEY 가 필요하다 (.env 또는 환경변수)");

        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            .responseTimeout(RESPONSE_TIMEOUT);

        tour = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }

    // ── 1. 경로·인증·응답 형식 ────────────────────────────────────────────────

    @Test
    @DisplayName("경로와 인증이 살아 있고 응답 필드 형식이 설계 가정과 맞는다")
    void 경로와_응답_형식을_확정한다() {
        Probe probe = locationBased(REGIONS.get(0), 12, "E", 5);

        System.out.println("\n=== [4-7a] 원본 응답(앞 1500자) ===\n"
            + (probe.body() == null ? "(없음)" : probe.body().substring(
                0, Math.min(1500, probe.body().length()))));
        assertThat(probe.failure())
            .as("경로·인증이 살아 있어야 4-7·4-9·4-5가 성립한다. 실패하면 여기서 멈춘다")
            .isNull();
        assertThat(resultCode(probe))
            .as("resultCode 0000 이 아니면 본문에 오류가 담겨 온 것이다(HTTP 는 200 이다)")
            .isEqualTo("0000");

        List<JsonNode> items = items(probe);
        assertThat(items).as("결과가 0건이면 아래 형식 확정을 할 수 없다").isNotEmpty();

        System.out.println("\n=== [4-7a] 필드 형식 ===");
        System.out.printf("  totalCount=%s numOfRows=%s pageNo=%s%n",
            probe.json().at("/response/body/totalCount").asText(),
            probe.json().at("/response/body/numOfRows").asText(),
            probe.json().at("/response/body/pageNo").asText());

        for (JsonNode item : items) {
            System.out.printf("  title=%s | contenttypeid=%s | dist=%sm%n",
                item.path("title").asText(), item.path("contenttypeid").asText(),
                item.path("dist").asText());
            System.out.printf("      mapx=%s mapy=%s  (자릿수 %d/%d)%n",
                item.path("mapx").asText(), item.path("mapy").asText(),
                item.path("mapx").asText().length(), item.path("mapy").asText().length());
            System.out.printf("      addr1=%s | contentid=%s%n",
                item.path("addr1").asText(), item.path("contentid").asText());
        }

        JsonNode first = items.get(0);
        double mapx = first.path("mapx").asDouble();
        double mapy = first.path("mapy").asDouble();
        System.out.println("\n=== [4-7a] 판정 재료 ===");
        System.out.printf("  좌표가 평문 십진 도인가: mapx=%.6f mapy=%.6f -> %s%n", mapx, mapy,
            (mapx > 124 && mapx < 132 && mapy > 33 && mapy < 39)
                ? "예 (네이버의 1e7 정수 문자열과 다르다 — 변환하면 안 된다)"
                : "아니오 (변환 규칙을 다시 정해야 한다)");
        System.out.println("  dist 필드 존재: " + first.has("dist")
            + "  (있으면 거리 계산을 우리가 하지 않아도 된다)");
        System.out.println("  지연: " + latenciesMs.get(0) + "ms");
    }

    // ── 2. 분류체계 (최우선) ──────────────────────────────────────────────────

    @Test
    @DisplayName("cat1~3이 살아 있는지, 새 체계로 갈아탔는지를 채움률로 가른다 — 4-9의 전제다")
    void 분류체계를_확정한다() {
        Map<String, Integer> cat3Counts = new LinkedHashMap<>();
        int total = 0;
        int cat3Filled = 0;
        int lclsFilled = 0;

        for (Region region : REGIONS) {
            for (int contentTypeId : List.of(12, 14, 28)) {
                Probe probe = locationBased(region, contentTypeId, "E", 30);
                for (JsonNode item : items(probe)) {
                    total++;
                    String cat3 = item.path("cat3").asText();
                    String lcls = item.path("lclsSystm3").asText();
                    if (!cat3.isBlank()) {
                        cat3Filled++;
                        cat3Counts.merge(cat3, 1, Integer::sum);
                    }
                    if (!lcls.isBlank()) {
                        lclsFilled++;
                    }
                }
            }
        }

        System.out.println("\n=== [4-7b] 분류체계 채움률 ===");
        System.out.printf("  표본 %d건%n", total);
        System.out.printf("  cat3 채움:        %d건 (%.1f%%)%n", cat3Filled, pct(cat3Filled, total));
        System.out.printf("  lclsSystm3 채움:  %d건 (%.1f%%)%n", lclsFilled, pct(lclsFilled, total));

        System.out.println("\n=== [4-7b] 실제로 등장한 cat3 코드 (빈도순) ===");
        cat3Counts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> System.out.printf("  %s  %d건%n", e.getKey(), e.getValue()));
        System.out.println("  고유 cat3 코드 수: " + cat3Counts.size()
            + "  (4-9 사전은 이 목록에 있는 값만 담는다)");

        assertThat(total).as("표본이 없으면 판정 자체가 불가능하다").isPositive();
        assertThat(cat3Filled)
            .as("cat3 가 전부 비어 있으면 4-9를 lclsSystm 기반으로 다시 설계해야 한다")
            .isPositive();
    }

    // ── 3. arrange 거리순 · 반경 · 상한 ───────────────────────────────────────

    @Test
    @DisplayName("arrange=E가 실제로 거리 오름차순이고 반경 안에서만 준다")
    void 거리순_정렬을_확정한다() {
        Probe probe = locationBased(REGIONS.get(0), 12, "E", 50);
        List<JsonNode> items = items(probe);
        assertThat(items).isNotEmpty();

        double previous = -1;
        boolean ascending = true;
        double maxDist = 0;
        for (JsonNode item : items) {
            double dist = item.path("dist").asDouble();
            ascending &= dist >= previous;
            previous = dist;
            maxDist = Math.max(maxDist, dist);
        }

        System.out.println("\n=== [4-7c] 거리순 ===");
        System.out.printf("  건수=%d  최원거리=%.0fm  (요청 반경 %dm)%n",
            items.size(), maxDist, RADIUS_METERS);
        System.out.println("  오름차순인가: " + ascending);
        System.out.printf("  numOfRows=50 요청 -> 실제 %d건, totalCount=%s%n",
            items.size(), probe.json().at("/response/body/totalCount").asText());

        assertThat(ascending)
            .as("거리순이 아니면 '반경 최대 + 거리순 + cap' 이라는 설계의 실질 필터가 무너진다")
            .isTrue();
        assertThat(maxDist)
            .as("반경 밖 항목이 오면 울타리가 동작하지 않는 것이다")
            .isLessThanOrEqualTo(RADIUS_METERS);
    }

    // ── 4. 무인지 지역 커버리지 ───────────────────────────────────────────────

    @Test
    @DisplayName("무인지 지역에서도 관광 후보가 나온다 — 이 소스를 채택한 이유 자체다")
    void 무인지_커버리지를_확정한다() {
        System.out.println("\n=== [4-7d] 지역 × contentTypeId 별 건수 ===");
        System.out.printf("  %-14s %-8s %8s %8s %8s%n", "지역", "티어", "12관광", "14문화", "28레포츠");

        for (Region region : REGIONS) {
            int[] counts = new int[3];
            int index = 0;
            for (int contentTypeId : List.of(12, 14, 28)) {
                counts[index++] = totalCount(locationBased(region, contentTypeId, "E", 10));
            }
            System.out.printf("  %-14s %-8s %8d %8d %8d%n",
                region.name(), region.tier(), counts[0], counts[1], counts[2]);
        }

        System.out.println("\n  (0에 가까운 칸이 있으면 그 슬롯은 시더 + 파라메트릭만으로 간다)");
    }

    @Test
    @DisplayName("상권형 명소가 등록돼 있는지 본다 — 없으면 그 축은 시더가 전담한다")
    void 상권형_명소_등록_여부를_본다() {
        Probe probe = locationBased(REGIONS.get(0), 12, "E", 50);

        System.out.println("\n=== [4-7e] 경주 황리단길 반경 20km, 가까운 20건 ===");
        List<JsonNode> items = items(probe);
        items.stream().limit(20).forEach(item ->
            System.out.printf("  %6sm  %s  (cat3=%s)%n",
                item.path("dist").asText().split("\\.")[0],
                item.path("title").asText(), item.path("cat3").asText()));

        boolean hasStreet = items.stream().anyMatch(item -> {
            String title = item.path("title").asText();
            return title.contains("거리") || title.contains("단길") || title.contains("시장");
        });
        System.out.println("\n  거리·단길·시장류 등록: " + hasStreet
            + "  (false면 설계가 예상한 대로 상업 POI 는 시더 몫이다)");
    }

    // ── 5. 실패 응답 형태 (classify 구현 근거) ────────────────────────────────

    @Test
    @DisplayName("키가 틀리면 어떻게 실패하는지 본다 — HTTP 상태가 아니라 본문일 수 있다")
    void 실패_응답_형태를_확정한다() {
        Probe probe = call(uri(REGIONS.get(0), 12, "E", 5, "INVALID-SERVICE-KEY"));

        System.out.println("\n=== [4-7f] 잘못된 키 응답 ===");
        System.out.println("  transport 실패: " + probe.failure());
        System.out.println("  본문(앞 600자): "
            + (probe.body() == null ? "(없음)"
                : probe.body().substring(0, Math.min(600, probe.body().length()))));
        System.out.println("\n  판정: HTTP 200 + 본문 오류코드라면 classify() 는 상태코드가 아니라"
            + " 본문 resultCode 를 봐야 한다");
    }

    // ── 6. serviceKey 이중 인코딩 ─────────────────────────────────────────────

    @Test
    @DisplayName("Encoding 키를 queryParam에 넣으면 인증이 깨진다 — 4-7 구현의 급소다")
    void 이중_인코딩을_확인한다() {
        boolean preEncoded = serviceKey.matches(".*%[0-9A-Fa-f]{2}.*");
        System.out.println("\n=== [4-7g] serviceKey 형태 ===");
        System.out.println("  발급 형태: " + (preEncoded ? "Encoding (%XX 포함)" : "Decoding (평문)"));

        // UriBuilder 기본 인코딩을 그대로 태운다 — 프로덕션에서 흔히 쓰는 조립 방식이다.
        Probe viaBuilder = call(builderUri(REGIONS.get(0)));
        // 이미 인코딩된 문자열을 URI 로 그대로 넘긴다.
        Probe viaRawUri = call(uri(REGIONS.get(0), 12, "E", 5, serviceKey));

        System.out.println("  queryParam 조립 -> resultCode=" + resultCode(viaBuilder)
            + ", failure=" + viaBuilder.failure());
        System.out.println("  raw URI 조립    -> resultCode=" + resultCode(viaRawUri)
            + ", failure=" + viaRawUri.failure());
        System.out.println("\n  판정: 둘이 다르면 4-7은 인코딩 모드를 명시적으로 다뤄야 한다");

        assertThat(resultCode(viaRawUri))
            .as("이미 인코딩된 키를 그대로 보내는 경로는 반드시 살아 있어야 한다")
            .isEqualTo("0000");
    }

    // ── 7. 프로덕션 조립으로 실제 API 를 부른다 ───────────────────────────────

    @Test
    @DisplayName("TourApiClient 가 실제 API 에서 동작한다 — 스텁만 통과하는 상태를 다시 만들지 않는다")
    void 프로덕션_클라이언트로_실호출한다() {
        // 판정 11: 4-1은 WireMock 12개가 전부 통과하는 동안 실호출이 100% 실패였다.
        // 스텁의 Content-Type 하나가 실제와 달랐기 때문이고, 그 결함은 프로덕션 조립을
        // 실제 API 에 붙여 보는 테스트가 없어서 늦게 발견됐다.
        TourApiClient client = new TourApiClient(
            TourApiConfig.buildTourApiWebClient(BASE_URL), serviceKey);
        Region region = REGIONS.get(0);

        TourApiResult result = client.search(
            region.latitude(), region.longitude(), 12, 10);

        System.out.println("\n=== [4-7h] 프로덕션 클라이언트 실호출 ===");
        System.out.println("  결과 타입: " + result.getClass().getSimpleName());
        if (result instanceof TourApiResult.Found found) {
            found.places().stream().limit(5).forEach(place ->
                System.out.printf("  %6.0fm  %s  (cat3=%s, %.6f/%.6f)%n",
                    place.distanceMeters(), place.title(), place.cat3(),
                    place.latitude(), place.longitude()));
        } else if (result instanceof TourApiResult.Failed failed) {
            System.out.println("  실패: " + failed.cause() + " / " + failed.detail());
        }

        assertThat(result)
            .as("프로덕션 조립으로 실패하면 스텁이 실제와 다른 것이다")
            .isInstanceOf(TourApiResult.Found.class);
        TourApiResult.Found found = (TourApiResult.Found) result;
        assertThat(found.places()).allSatisfy(place -> {
            assertThat(place.hasCoordinates()).isTrue();
            assertThat(place.cat3()).isNotBlank();
            assertThat(place.distanceMeters()).isNotNull();
        });
    }

    @Test
    @DisplayName("관광지가 없는 좌표에서 Empty 를 준다 — 0건이 실패로 둔갑하지 않는다")
    void 프로덕션_클라이언트가_0건을_Empty로_돌려준다() {
        TourApiClient client = new TourApiClient(
            TourApiConfig.buildTourApiWebClient(BASE_URL), serviceKey);

        // 서해 먼바다. 반경을 최소로 줘 등록 항목이 없게 만든다.
        TourApiResult result = client.search(33.0, 125.0, 12, 10);

        System.out.println("\n=== [4-7i] 0건 응답 ===");
        System.out.println("  결과 타입: " + result.getClass().getSimpleName()
            + "  (Failed 라면 items:\"\" 흡수가 동작하지 않는 것이다)");

        assertThat(result).isNotInstanceOf(TourApiResult.Failed.class);
    }

    // ── 호출 헬퍼 ─────────────────────────────────────────────────────────────

    private Probe locationBased(Region region, int contentTypeId, String arrange, int numOfRows) {
        return call(uri(region, contentTypeId, arrange, numOfRows, serviceKey));
    }

    private URI uri(Region region, int contentTypeId, String arrange, int numOfRows, String key) {
        return URI.create(BASE_URL + LOCATION_BASED
            + "?serviceKey=" + key
            + "&MobileOS=ETC&MobileApp=YOURTRIP&_type=json"
            + "&mapX=" + region.longitude()
            + "&mapY=" + region.latitude()
            + "&radius=" + RADIUS_METERS
            + "&contentTypeId=" + contentTypeId
            + "&arrange=" + arrange
            + "&numOfRows=" + numOfRows
            + "&pageNo=1");
    }

    /** {@code UriBuilder}로 조립한다 — 여기서 {@code %}가 {@code %25}로 한 번 더 인코딩된다. */
    private URI builderUri(Region region) {
        return org.springframework.web.util.UriComponentsBuilder.fromUriString(
                BASE_URL + LOCATION_BASED)
            .queryParam("serviceKey", serviceKey)
            .queryParam("MobileOS", "ETC")
            .queryParam("MobileApp", "YOURTRIP")
            .queryParam("_type", "json")
            .queryParam("mapX", region.longitude())
            .queryParam("mapY", region.latitude())
            .queryParam("radius", RADIUS_METERS)
            .queryParam("contentTypeId", 12)
            .queryParam("arrange", "E")
            .queryParam("numOfRows", 5)
            .queryParam("pageNo", 1)
            .encode()
            .build()
            .toUri();
    }

    private Probe call(URI target) {
        long began = System.nanoTime();
        try {
            String body = tour.get().uri(target).retrieve().bodyToMono(String.class).block();
            latenciesMs.add((System.nanoTime() - began) / 1_000_000);
            JsonNode json = null;
            try {
                json = MAPPER.readTree(body);
            } catch (Exception ignored) {
                // XML 오류 응답일 수 있다. body 는 그대로 남겨 사람이 읽는다.
            }
            return new Probe(body, json, null);
        } catch (WebClientResponseException e) {
            latenciesMs.add((System.nanoTime() - began) / 1_000_000);
            return new Probe(e.getResponseBodyAsString(), null,
                e.getStatusCode() + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            latenciesMs.add((System.nanoTime() - began) / 1_000_000);
            return new Probe(null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String resultCode(Probe probe) {
        if (probe.json() == null) {
            return "(JSON 아님)";
        }
        return probe.json().at("/response/header/resultCode").asText();
    }

    private static List<JsonNode> items(Probe probe) {
        List<JsonNode> result = new ArrayList<>();
        if (probe.json() == null) {
            return result;
        }
        JsonNode item = probe.json().at("/response/body/items/item");
        if (item.isArray()) {
            item.forEach(result::add);
        } else if (item.isObject()) {
            // 1건이면 배열이 아니라 객체로 오는지 확인하기 위한 분기다.
            result.add(item);
        }
        return result;
    }

    private static int totalCount(Probe probe) {
        if (probe.json() == null) {
            return -1;
        }
        return probe.json().at("/response/body/totalCount").asInt(-1);
    }

    private static double pct(int part, int whole) {
        return whole == 0 ? 0 : part * 100.0 / whole;
    }

    private record Region(String name, String tier, double longitude, double latitude) {

    }

    private record Probe(String body, JsonNode json, String failure) {

    }

    /** Spring 컨텍스트가 없어 spring-dotenv 가 동작하지 않으므로 실제 환경변수 → .env 순으로 찾는다. */
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
