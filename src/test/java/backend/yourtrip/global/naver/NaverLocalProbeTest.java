package backend.yourtrip.global.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.ai.route.SlotType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

/**
 * 네이버 지역검색 API <b>실호출 확정</b> 프로브 (ROADMAP 4-2).
 *
 * <p>설계 문서(docs/tasks/ai-course-create/design/지식-신호와-후보-공급.md)가 "착수 전 실호출로
 * 확정"이라고 남긴 가정들을 실제 응답으로 검증한다. <b>결과가 설계를 가르는 항목이 둘</b>이라
 * 4-1 구현보다 먼저 돈다.
 *
 * <ul>
 *   <li><b>{@code mapx}/{@code mapy} 정밀도</b> — 5-10의 300m 임계값이 이 정밀도 위에서만 성립한다</li>
 *   <li><b>서술어 매칭 범위</b> — "루프탑 카페" 같은 수식어가 결과를 실제로 바꾸지 못하면 스타일
 *       축을 retrieval에서 포기해야 하고, 9단계 착수 조건이 하나 켜진다</li>
 * </ul>
 *
 * <p><b>이 프로브는 판정용이지 회귀 테스트가 아니다.</b> 외부 응답에 의존하므로 단언은 "이 설계가
 * 성립하려면 반드시 참이어야 하는 것"에만 걸고, 나머지는 {@code System.out}으로 덤프해 사람이 읽는다.
 * 그래서 {@code @Tag("benchmark")}로 일반 빌드에서 제외한다
 * ({@code SpringAiStructuredOutputVerificationTest}가 만든 선례).
 *
 * <p>Spring 컨텍스트를 쓰지 않으므로 spring-dotenv 가 동작하지 않는다 — 실제 환경변수 → 레포 루트
 * {@code .env} 순으로 키를 찾고, 없으면 {@code assumeTrue}로 스킵한다.
 *
 * <p><b>WebClient를 여기서 직접 조립하는 이유</b>: 프로덕션 조립({@code NaverConfig})은 4-1에서
 * 만든다. 다만 타임아웃 값을 카카오와 같게 두고 <b>지연을 함께 재서</b>, 4-1이 그 값을 감이 아니라
 * 실측으로 고를 수 있게 한다.
 *
 * <pre>
 * ./gradlew benchmarkTest --tests "*NaverLocalProbeTest*" --rerun
 * </pre>
 */
@Tag("benchmark")
@DisplayName("네이버 지역검색 실호출 확정 (ROADMAP 4-2)")
class NaverLocalProbeTest {

    /** NAVER API HUB. 검색 API가 developers.naver.com에서 이관되며 바뀐 주소다. */
    private static final String BASE_URL = "https://naverapihub.apigw.ntruss.com";

    /**
     * <b>경로가 레거시와 다르다.</b> {@code .env.example}과 설계 문서는 레거시 경로
     * {@code /v1/search/local.json}을 적어 두었으나 그 경로는 404다 — 이관되며 확장자가 사라지고
     * 세그먼트 순서가 뒤집혔다({@code /search/v1/local}). 4-2에서 실호출로 확정한 값이다.
     */
    private static final String LOCAL_PATH = "/search/v1/local";

    /** 카카오와 같은 값으로 둔다 — 4-1이 값을 정할 때 이 조건에서 잰 지연이 근거가 된다. */
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebClient naver;
    private final List<Long> latenciesMs = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        String clientId = resolveEnv("NAVER_CLIENT_ID");
        String clientSecret = resolveEnv("NAVER_CLIENT_SECRET");
        assumeTrue(clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank(),
            "이 프로브는 실제 NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 이 필요하다 (.env 또는 환경변수)");

        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            .responseTimeout(RESPONSE_TIMEOUT);

        naver = WebClient.builder()
            .baseUrl(BASE_URL)
            // 구 방식(X-Naver-Client-Id / X-Naver-Client-Secret)이 아니다. API HUB 이관으로 바뀌었다.
            .defaultHeader("X-NCP-APIGW-API-KEY-ID", clientId)
            .defaultHeader("X-NCP-APIGW-API-KEY", clientSecret)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }

    // ── 1. 경로·인증·응답 형식 ────────────────────────────────────────────────

    @Test
    @DisplayName("경로와 인증 헤더가 살아 있고, 응답 필드의 형식이 설계 가정과 맞는다")
    void 경로와_응답_형식을_확정한다() {
        Probe probe = call("경주 황리단길 카페", 5, "comment");

        System.out.println("\n=== [4-2a] 원본 응답 ===\n" + probe.body());
        assertThat(probe.failure())
            .as("경로·인증이 살아 있어야 4단계 전체가 성립한다. 실패하면 여기서 멈춘다")
            .isNull();

        JsonNode items = probe.json().path("items");
        assertThat(items.isArray()).as("items 배열이 있어야 한다").isTrue();
        assertThat(items.size()).as("결과가 0건이면 아래 형식 확정을 할 수 없다").isPositive();

        System.out.println("\n=== [4-2a] 필드 형식 ===");
        System.out.printf("  total=%s  start=%s  display=%s%n",
            probe.json().path("total").asText(), probe.json().path("start").asText(),
            probe.json().path("display").asText());

        boolean anyBoldTag = false;
        for (JsonNode item : items) {
            String title = item.path("title").asText();
            String mapx = item.path("mapx").asText();
            String mapy = item.path("mapy").asText();
            anyBoldTag |= title.contains("<b>") || title.contains("</b>");

            System.out.printf("  title=%s | category=%s | mapx=%s mapy=%s%n",
                title, item.path("category").asText(), mapx, mapy);
            System.out.printf("      roadAddress=%s | address=%s%n",
                item.path("roadAddress").asText(), item.path("address").asText());
            System.out.printf("      link=%s | description=%s | telephone=%s%n",
                item.path("link").asText(), item.path("description").asText(),
                item.path("telephone").asText());
            System.out.printf("      -> mapx 자릿수=%d, mapy 자릿수=%d, 1e7 나눈 값=(%.7f, %.7f)%n",
                mapx.length(), mapy.length(),
                parseOrNaN(mapy) / 1e7, parseOrNaN(mapx) / 1e7);
        }

        System.out.println("\n=== [4-2a] 판정 재료 ===");
        System.out.println("  <b> 태그 등장: " + anyBoldTag + "  (true면 4-1에서 스트립 필수)");
        System.out.println("  지연: " + latenciesMs.get(0) + "ms  (카카오와 같은 응답 제한 3초 기준)");
        System.out.println("  total == display 인가: "
            + probe.json().path("total").asInt() + " vs " + probe.json().path("display").asInt()
            + "  (같으면 total 은 전체 매칭 수가 아니라 반환 건수다 — 9단계 인기도에 쓸 수 없다)");
    }

    @Test
    @DisplayName("display 상한과 start 고정 제약을 확인한다 — 풀을 넓히는 유일한 축이 쿼리 변주인 근거")
    void display와_start_제약을_확인한다() {
        Probe five = call("경주 황리단길 카페", 5, "comment");
        Probe ten = call("경주 황리단길 카페", 10, "comment");

        System.out.println("\n=== [4-2b] display 제약 ===");
        System.out.printf("  display=5  -> items %d건 (실패: %s)%n", five.itemCount(), five.failure());
        System.out.printf("  display=10 -> items %d건 (실패: %s)%n", ten.itemCount(), ten.failure());
        System.out.println("  ※ 10을 요청해도 5건이면 설계의 '5건 하드 실링'이 확인된 것이다");

        // start 는 "거부"가 아니라 "무시"다. 건수만 보면 페이징이 되는 것처럼 보이므로
        // 반드시 항목까지 비교해야 한다 — 그러지 않으면 5-8이 페이징으로 풀을 넓히려다
        // 같은 5건을 중복으로 받는다.
        List<String> firstPage = titlesOf(five);
        System.out.println("  start 별 반환 항목:");
        for (int start : new int[] {1, 2, 6, 11}) {
            Probe paged = callWithStart("경주 황리단길 카페", 5, "comment", start);
            List<String> titles = titlesOf(paged);
            System.out.printf("    start=%-3d 응답 start=%s  %d건  1페이지와 동일: %s%n",
                start,
                paged.json() == null ? "-" : paged.json().path("start").asText(),
                titles.size(), titles.equals(firstPage));
        }
        System.out.println("  ※ '1페이지와 동일: true'가 반복되면 start 가 무시되는 것이다 —"
            + " 6위 이하를 못 받는다는 설계 결론은 같지만 근거가 '거부'가 아니라 '무시'다");
    }

    @Test
    @DisplayName("sort=comment 가 실제로 다른 순서를 준다 — 인기 축 시딩의 전제")
    void sort_comment가_유효한지_확인한다() {
        List<String> byComment = titlesOf(call("경주 황리단길 카페", 5, "comment"));
        List<String> byRandom = titlesOf(call("경주 황리단길 카페", 5, "random"));

        System.out.println("\n=== [4-2c] 정렬 축 ===");
        System.out.println("  sort=comment: " + byComment);
        System.out.println("  sort=random : " + byRandom);
        System.out.println("  ※ 두 목록이 완전히 같으면 sort 파라미터가 무시되고 있다는 뜻이다");

        assertThat(byComment)
            .as("sort=comment 가 결과를 주지 못하면 인기 축 시딩이라는 이 설계의 전제가 무너진다")
            .isNotEmpty();
    }

    // ── 2. 서술어 매칭 — 설계를 가르는 항목 ──────────────────────────────────

    @Test
    @DisplayName("스타일 수식어가 결과를 실제로 바꾼다 — 아니면 스타일 축을 retrieval에서 포기해야 한다")
    void 서술어_매칭_범위를_확정한다() {
        List<String[]> pairs = List.of(
            new String[] {"경주 황리단길 카페", "경주 황리단길 루프탑 카페"},
            new String[] {"경주 황리단길 카페", "경주 황리단길 조용한 카페"},
            new String[] {"강릉 안목해변 카페", "강릉 안목해변 야경 카페"},
            new String[] {"공주 맛집", "공주 주차 맛집"}
        );

        System.out.println("\n=== [4-2d] 서술어 매칭 ===");
        for (String[] pair : pairs) {
            Set<String> base = new LinkedHashSet<>(titlesOf(call(pair[0], 5, "comment")));
            Set<String> styled = new LinkedHashSet<>(titlesOf(call(pair[1], 5, "comment")));

            Set<String> overlap = new LinkedHashSet<>(base);
            overlap.retainAll(styled);
            Set<String> fresh = new LinkedHashSet<>(styled);
            fresh.removeAll(base);

            System.out.printf("%n  기본 [%s] %d건 / 스타일 [%s] %d건 -> 겹침 %d, 신규 %d%n",
                pair[0], base.size(), pair[1], styled.size(), overlap.size(), fresh.size());
            System.out.println("      기본  : " + base);
            System.out.println("      스타일: " + styled);
        }

        System.out.println("\n  읽는 법");
        System.out.println("    신규 0건이 반복되면 -> 상호명·카테고리만 매칭한다는 뜻. 스타일 축을 retrieval에서");
        System.out.println("                          포기하고 Curator 선별에만 맡긴다(9단계 착수 조건이 켜진다)");
        System.out.println("    스타일 결과가 0건   -> modifier가 결과를 좁히는 방향. 합집합이라 풀은 안 줄지만");
        System.out.println("                          fail-open으로 조용히 무력화된다");
        System.out.println("    신규가 유의미하다   -> 설계대로 modifier 쿼리를 채택한다");
    }

    // ── 3. 무인지 지역 커버리지 — 4-10이 확인한 공백을 실제로 메우는가 ──────

    @Test
    @DisplayName("무인지 지역에서도 시더가 후보를 준다 — 4-10이 확인한 공백을 메우는 것이 이 층의 목적이다")
    void 무인지_지역_커버리지를_확인한다() {
        List<String> regions = List.of("삼척", "공주", "영주");
        List<SlotType> slots = List.of(SlotType.MEAL, SlotType.CAFE, SlotType.ATTRACTION);

        System.out.println("\n=== [4-2e] 무인지 지역 커버리지 ===");
        int empty = 0;
        int total = 0;
        for (String region : regions) {
            for (SlotType slot : slots) {
                String query = region + " " + slot.getSearchHint();
                List<String> titles = titlesOf(call(query, 5, "comment"));
                total++;
                if (titles.isEmpty()) {
                    empty++;
                }
                System.out.printf("  %-4s %-11s %d건  %s%n",
                    region, slot.name(), titles.size(),
                    titles.isEmpty() ? "(없음)" : titles.get(0));
            }
        }
        System.out.printf("%n  빈 결과 %d/%d — 설계는 '0건인 지역은 실제로 그 업종이 없는 곳'이라고 본다.%n",
            empty, total);
        System.out.println("  이 비율이 높으면 5-9에서 '0건일 때만 카카오' 폴백을 붙이는 근거가 된다.");
        System.out.printf("%n  지연 %d회 평균 %.0fms / 최대 %dms%n",
            latenciesMs.size(),
            latenciesMs.stream().mapToLong(Long::longValue).average().orElse(0),
            latenciesMs.stream().mapToLong(Long::longValue).max().orElse(0));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** 프로브 1회의 결과. 실패를 예외가 아니라 값으로 담는다 — 한 쿼리가 죽어도 나머지를 봐야 한다. */
    private record Probe(String body, JsonNode json, String failure) {

        int itemCount() {
            return json == null ? 0 : json.path("items").size();
        }
    }

    private Probe call(String query, int display, String sort) {
        return callWithStart(query, display, sort, 1);
    }

    private Probe callWithStart(String query, int display, String sort, int start) {
        long began = System.nanoTime();
        try {
            String body = naver.get()
                .uri(uriBuilder -> uriBuilder
                    .path(LOCAL_PATH)
                    .queryParam("query", query)
                    .queryParam("display", display)
                    .queryParam("start", start)
                    .queryParam("sort", sort)
                    .build())
                .retrieve()
                .bodyToMono(String.class)
                .block();
            latenciesMs.add((System.nanoTime() - began) / 1_000_000);
            return new Probe(body, MAPPER.readTree(body), null);
        } catch (WebClientResponseException e) {
            latenciesMs.add((System.nanoTime() - began) / 1_000_000);
            return new Probe(e.getResponseBodyAsString(), null,
                e.getStatusCode() + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            latenciesMs.add((System.nanoTime() - began) / 1_000_000);
            return new Probe(null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static List<String> titlesOf(Probe probe) {
        List<String> titles = new ArrayList<>();
        if (probe.json() == null) {
            return titles;
        }
        for (JsonNode item : probe.json().path("items")) {
            titles.add(item.path("title").asText().replaceAll("</?b>", ""));
        }
        return titles;
    }

    private static double parseOrNaN(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
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
