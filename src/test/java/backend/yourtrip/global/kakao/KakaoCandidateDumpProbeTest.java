package backend.yourtrip.global.kakao;

import static backend.yourtrip.global.benchmark.BenchmarkEnv.loadDotEnv;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.resolve;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.setting;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.sleep;
import static backend.yourtrip.global.benchmark.BenchmarkEnv.text;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.csv;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.readCsv;
import static backend.yourtrip.global.benchmark.HallucinationArtifacts.writeUtf8Bom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.ai.candidate.PlaceNameNormalizer;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 카카오 검색 <b>후보 5건 전수</b>를 덤프하는 일회성 프로브 (이슈 #164).
 *
 * <h2>왜 재채점으로는 부족한가</h2>
 * 환각률 산출물({@code places.csv})에는 <b>선택된 후보만</b> 남는다. 그래서 "부속 POI를 밀어내면
 * 그 자리에 본체가 올라오는가"를 물어볼 수가 없다 — 본체가 애초에 후보 5건 안에 있었는지가
 * 기록되지 않았기 때문이다. 그 값을 모르면 <b>순위 강등이라는 처방 자체의 효과 상한</b>을 모른다.
 *
 * <p>검색을 한 번만 돌려 후보 전건을 남겨 두면, 그 위에서 판별 규칙 후보를 <b>오프라인으로 몇 개든</b>
 * 비교할 수 있다. 규칙마다 재채점을 돌리는 것보다 싸고, 무엇보다 <b>같은 응답 위에서 비교</b>하므로
 * 카카오 DB 드리프트가 규칙 간 차이로 오해되지 않는다.
 *
 * <h2>{@code lookupBestPlace}를 부르지 않는다 — 그게 요점이다</h2>
 * 그 함수는 3점 비교의 전제 조건이라(ROADMAP 8단계) 손대면 before/after가 다른 자로 잰 값이 된다.
 * 이 프로브는 {@link KakaoLocalClient#searchPlace}만 부르고 선택은 하지 않으므로, 프로덕션 판정
 * 경로를 한 글자도 건드리지 않은 채 재료만 모은다.
 *
 * <p>다만 <b>키워드 조립은 반드시 공유한다</b> — {@link KakaoLocalClient#buildKeyword}를 그대로
 * 부른다. 복제하면 이 덤프와 프로덕션이 서로 다른 질문을 던지게 되고, 그 위에서 고른 규칙은
 * 실제로 오는 후보와 무관한 근거가 된다.
 *
 * <p><b>판정용이지 회귀 테스트가 아니다</b>({@code NaverLocalProbeTest}가 만든 선례). 외부 응답에
 * 의존하므로 단언은 "측정이 성립하려면 반드시 참이어야 하는 것"에만 걸고, 나머지는 CSV로 덤프해
 * 사람이 읽는다. 그래서 {@code @Tag("benchmark")}로 일반 빌드에서 제외한다.
 *
 * <pre>
 * CANDIDATE_DUMP_FROM=docs/tasks/ai-course-create/hallucination/artifacts/gemini-20260811/places.csv
 *   ./gradlew benchmarkTest --tests '*KakaoCandidateDumpProbeTest*' --rerun
 * </pre>
 */
@Tag("benchmark")
class KakaoCandidateDumpProbeTest {

    /** {@code lookupBestPlace}가 쓰는 후보 수와 같아야 한다 — 다르면 다른 표본을 보는 셈이다. */
    private static final int CANDIDATE_SIZE = 5;

    /** 키가 죽었거나 쿼터가 끝났으면 수백 건을 헛돌리지 않고 멈춘다. */
    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    @Test
    @DisplayName("환각률 산출물의 장소명으로 카카오 후보 5건을 전수 덤프한다 (LLM 호출 없음)")
    void dumpCandidates() throws IOException {
        String dumpFrom = text("candidateDump.from", "CANDIDATE_DUMP_FROM", "");
        assumeTrue(!dumpFrom.isBlank(), "CANDIDATE_DUMP_FROM 지정 시에만 실행된다");

        Map<String, String> env = loadDotEnv(Path.of(".env"));
        String kakaoKey = resolve(env, "KAKAO_API_KEY");
        assumeTrue(kakaoKey != null, "후보 덤프는 KAKAO_API_KEY 가 필요하다 (.env 또는 환경변수)");

        Path source = Path.of(dumpFrom);
        assertThat(source).as("덤프 원본 CSV").exists();

        KakaoLocalClient kakaoLocalClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));

        // (지역, 장소명) 쌍으로 중복을 걷는다. 같은 장소가 여러 day·요청에 나오는데 검색 결과는
        // 같으므로, 중복 호출은 쿼터만 쓰고 정보를 늘리지 않는다. 원본 행과의 조인은 이 키로 한다.
        List<SearchKey> keys = readSearchKeys(source);
        long delayMs = setting("candidateDump.delayMs", "CANDIDATE_DUMP_DELAY_MS", 200L);
        System.out.printf("%n=== 후보 덤프 시작: %s (고유 키 %d개, 호출 간 지연 %dms) ===%n",
            source, keys.size(), delayMs);

        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", "location", "aiPlaceName", "rank", "candidateName",
            "categoryName", "categoryGroupCode", "address", "placeUrl", "x", "y",
            "score", "gatePass", "containsDirection", "prefixRest", "suffixRest"));

        int consecutiveErrors = 0;
        int searched = 0;
        for (SearchKey key : keys) {
            List<Document> docs = search(kakaoLocalClient, key);
            if (docs == null) {
                consecutiveErrors++;
                assertThat(consecutiveErrors)
                    .as("카카오 호출이 연속 실패한다 — 키·쿼터를 확인하라 (%s)", key)
                    .isLessThan(MAX_CONSECUTIVE_ERRORS);
                continue;
            }
            consecutiveErrors = 0;

            if (docs.isEmpty()) {
                // 무결과도 행으로 남긴다. 빠뜨리면 원본 행과 조인할 때 "검색 안 함"과 구별되지 않는다.
                lines.add(row(key, -1, null));
            }
            for (int rank = 0; rank < docs.size(); rank++) {
                lines.add(row(key, rank, docs.get(rank)));
            }

            if (++searched % 50 == 0) {
                System.out.printf("  ... %d / %d%n", searched, keys.size());
            }
            sleep(delayMs);
        }

        String runTag = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outFile = Path.of("results", "candidate-dump-" + runTag + ".csv");
        writeUtf8Bom(outFile, String.join("\n", lines) + "\n");

        System.out.printf("%n=== 덤프 완료 ===%n  검색 %d / %d 키%n  후보 행 %d개%n  산출물 %s%n",
            searched, keys.size(), lines.size() - 1, outFile);
        System.out.println("  ※ results/ 는 .gitignore 대상이다 — 분석이 끝나면 artifacts/ 로 승격한다");
    }

    /** 검색 1회. 실패는 {@code null}로 돌려 무결과(빈 목록)와 갈라 센다. */
    private static List<Document> search(KakaoLocalClient client, SearchKey key) {
        String keyword = KakaoLocalClient.buildKeyword(key.aiPlaceName(), key.location());
        if (keyword.isBlank()) {
            return List.of();
        }
        try {
            KakaoSearchResponse response = client.searchPlace(keyword, CANDIDATE_SIZE);
            if (response == null || response.documents() == null) {
                return List.of();
            }
            return response.documents();
        } catch (RuntimeException e) {
            System.out.printf("  [검색 실패] %s — %s%n", keyword, e.getMessage());
            return null;
        }
    }

    /**
     * 덤프 1행. <b>판정하지 않고 관측만 적는다</b> — 규칙은 이 CSV 위에서 오프라인으로 고른다.
     *
     * <p>{@code gatePass}·{@code containsDirection}·{@code prefixRest}·{@code suffixRest}는
     * 프로덕션 {@link PlaceNameNormalizer}로 계산한다. 여기서 정규화를 복제하면 덤프가 게이트와
     * 다른 잣대를 쓰게 되어, 분석 결과가 프로덕션에 그대로 옮겨지지 않는다.
     */
    private static String row(SearchKey key, int rank, Document doc) {
        String candidateName = doc == null ? "" : nullToEmpty(doc.place_name());
        String normalizedAi = PlaceNameNormalizer.normalize(key.aiPlaceName());
        String normalizedCandidate = PlaceNameNormalizer.normalize(candidateName);

        String direction = direction(normalizedAi, normalizedCandidate);
        String prefixRest = "";
        String suffixRest = "";
        if ("CANDIDATE_LONGER".equals(direction)) {
            int at = normalizedCandidate.indexOf(normalizedAi);
            prefixRest = normalizedCandidate.substring(0, at);
            suffixRest = normalizedCandidate.substring(at + normalizedAi.length());
        }

        return String.join(",",
            csv(key.location()),
            csv(key.aiPlaceName()),
            String.valueOf(rank),
            csv(candidateName),
            doc == null ? "" : csv(nullToEmpty(doc.category_name())),
            doc == null ? "" : csv(nullToEmpty(doc.category_group_code())),
            doc == null ? "" : csv(PlaceMatchScorer.bestAddressOf(doc)),
            doc == null ? "" : csv(nullToEmpty(doc.place_url())),
            doc == null ? "" : csv(nullToEmpty(doc.x())),
            doc == null ? "" : csv(nullToEmpty(doc.y())),
            doc == null ? "" : String.valueOf(
                PlaceMatchScorer.score(doc, key.aiPlaceName(), key.location())),
            doc == null ? "" : String.valueOf(
                PlaceNameNormalizer.similar(candidateName, key.aiPlaceName())),
            direction,
            csv(prefixRest),
            csv(suffixRest));
    }

    /**
     * 정규화 후 두 이름의 포함 방향. 부속 POI는 <b>후보 쪽이 더 긴</b> 경우에만 성립한다 —
     * 반대 방향(AI 이름이 더 김)은 지점 수식어가 붙은 요청이라 성격이 다르다.
     */
    private static String direction(String normalizedAi, String normalizedCandidate) {
        if (normalizedAi.isEmpty() || normalizedCandidate.isEmpty()) {
            return "";
        }
        if (normalizedAi.equals(normalizedCandidate)) {
            return "EXACT";
        }
        if (normalizedCandidate.contains(normalizedAi)) {
            return "CANDIDATE_LONGER";
        }
        if (normalizedAi.contains(normalizedCandidate)) {
            return "AI_LONGER";
        }
        return "DISJOINT";
    }

    /** 원본 CSV에서 (지역, 장소명) 고유 쌍을 입력 순서대로 뽑는다. */
    private static List<SearchKey> readSearchKeys(Path source) throws IOException {
        List<List<String>> csvRows = readCsv(source);
        Map<String, Integer> col = new HashMap<>();
        List<String> header = csvRows.get(0);
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i), i);
        }
        for (String required : List.of("location", "aiPlaceName")) {
            assertThat(col).as("원본 CSV에 %s 열이 있어야 한다", required).containsKey(required);
        }

        LinkedHashSet<SearchKey> keys = new LinkedHashSet<>();
        for (int i = 1; i < csvRows.size(); i++) {
            List<String> row = csvRows.get(i);
            keys.add(new SearchKey(row.get(col.get("location")), row.get(col.get("aiPlaceName"))));
        }
        return List.copyOf(keys);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record SearchKey(String location, String aiPlaceName) {

        @Override
        public String toString() {
            return location + " " + aiPlaceName;
        }
    }
}
