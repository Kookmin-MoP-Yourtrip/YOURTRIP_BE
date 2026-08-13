package backend.yourtrip.global.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.gemini.dto.GeminiCourseDto;
import backend.yourtrip.global.gemini.dto.GeminiCourseDto.DayScheduleDto;
import backend.yourtrip.global.gemini.dto.GeminiCourseDto.PlaceDto;
import backend.yourtrip.global.gemini.service.GeminiService;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.genai.Client;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AI 코스 생성(단일 Gemini 호출) 구조의 **환각률 baseline 측정** 하네스.
 * 멀티 에이전트 파이프라인(docs/tasks/TASK-AI-MULTI-AGENT.md) 도입 전 before 값을 남기는 것이 목적이며,
 * 도입 후 동일한 방법론으로 재측정해 before/after를 비교한다.
 *
 * <p><b>프로덕션 코드를 한 줄도 바꾸지 않는다.</b> before 값이 의미를 가지려면 현재 코드가 실제로 하는
 * 동작 그대로를 재야 하므로, {@code KakaoLocalClient.score()}가 private이지만 복제하지 않고
 * {@link ReflectionTestUtils#invokeMethod}로 원본을 그대로 호출한다(SigningBenchmarkTest가 만든 선례).
 * 복제하면 원본과 drift가 생겨 after 측정과의 비교 근거가 약해진다.
 *
 * <p><b>{@code findBestPlace()}가 아니라 {@code searchPlace()} + 리플렉션 {@code score()}를 쓰는 이유</b>:
 * {@code findBestPlace()}는 매칭된 Document만 돌려주고 점수를 버린다. 점수 없이는 hit과 below_threshold를
 * 구분할 수 없어 환각률 자체를 계산할 수 없다.
 *
 * <p><b>이 측정이 재는 것은 "환각률"이 아니라 "카카오 매칭 실패율"이다.</b> 둘은 상관관계가 있지만
 * 같지 않다 — 실존하는데 매칭에 실패하는 거짓 양성과, 지어낸 이름이 무관한 실제 업소와 우연히 매칭되는
 * 거짓 음성(세탁된 환각)이 섞여 있다. 후자는 자동으로 잡을 수 없어 수동 검증 워크시트로 별도 추정한다.
 *
 * <p>Spring 컨텍스트를 쓰지 않으므로 spring-dotenv가 동작하지 않는다 — 레포 루트 {@code .env}를 직접 파싱한다.
 * 일반 빌드(`./gradlew test`)에서는 실행되지 않는다(build.gradle의 {@code excludeTags 'benchmark'}).
 *
 * <p><b>{@code --rerun}을 반드시 붙일 것.</b> 소스가 안 바뀌면 Gradle이 이 태스크를 UP-TO-DATE로
 * 판단해 테스트를 실행하지 않고 성공으로 끝낸다 — 결과가 외부 API 응답에 달려 매번 달라진다는 것을
 * Gradle은 알 수 없기 때문이다. 정상 실행이면 30요청에 7~9분이 걸리므로 소요 시간으로도 확인된다.
 *
 * <p>LLM API의 일일 한도가 측정 규모의 상한이므로 <b>배치로 나눠 측정할 수 있다.</b>
 * {@code requestId}는 전체 입력 세트 기준으로 고정이라, 배치가 달라도 결과 CSV를 그대로 합칠 수 있다.
 *
 * <pre>
 * 전체 측정:   ./gradlew benchmarkTest --tests '*AiHallucinationBaselineTest*' --rerun
 * 이어서 측정: HALLUCINATION_REQUEST_FROM=15 ./gradlew ... --rerun   (#15부터 끝까지)
 * 배치 측정:   HALLUCINATION_REQUEST_FROM=15 HALLUCINATION_REQUEST_LIMIT=16 ./gradlew ... --rerun
 * 스모크:      HALLUCINATION_REQUEST_LIMIT=2 ./gradlew ... --rerun
 * 요청 간 지연: HALLUCINATION_DELAY_MS=8000 (기본 5000)
 * </pre>
 */
@Tag("benchmark")
class AiHallucinationBaselineTest {

    /** 여행 일수. 일수까지 변수로 두면 표본이 흩어져 지역·키워드 효과를 못 본다. */
    private static final int TRIP_DAYS = 3;

    /** Gemini 무료 티어 RPM 방어용 요청 간 지연. -Dhallucination.delayMs 로 조정한다. */
    private static final long DEFAULT_DELAY_MS = 5_000L;

    /** 수동 검증 워크시트에서 점수 구간별로 뽑을 표본 수. */
    private static final int SAMPLES_PER_BAND = 10;

    /** 층화 추출을 재현 가능하게 만드는 고정 시드 — 재분석 시 같은 표본이 나와야 한다. */
    private static final long SAMPLING_SEED = 42L;

    /**
     * 429 재시도 횟수와 기본 백오프. 관찰된 429가 RPD(일일)인지 RPM(분당)인지 확정하지 못했으므로
     * (짧은 백오프로 풀리는 사례가 실제로 있었다), 개별 요청 단위로도 충분히 버티게 여유를 둔다.
     * 10s→20s→40s→80s(최대 150초 대기) — 이 정도로도 안 풀리면 그 요청은 포기하고, 배치 단위
     * 재시도(바깥의 실행 스크립트에서 시간 간격을 두고 재실행)로 넘긴다.
     */
    private static final int MAX_CALL_RETRIES = 4;
    private static final long RETRY_BASE_BACKOFF_MS = 10_000L;

    /** 호출이 연속 이 횟수만큼 최종 실패하면 쿼터 소진으로 보고 측정을 조기 중단한다. */
    private static final int ABORT_AFTER_CONSECUTIVE_FAILURES = 3;

    /** CSV·콘솔에 남기는 예외 메시지의 최대 길이. */
    private static final int MAX_ERROR_LENGTH = 300;

    private static final Path RESULTS_DIR = Path.of("results");

    // ── 입력 세트: 지역 10개 × 키워드 조합 3개 = 30요청 ──────────────────────────

    private enum RegionTier {FAMOUS, MINOR}

    private record RegionSpec(String name, RegionTier tier) {}

    private static final List<RegionSpec> REGIONS = List.of(
        new RegionSpec("경주", RegionTier.FAMOUS),
        new RegionSpec("부산", RegionTier.FAMOUS),
        new RegionSpec("제주", RegionTier.FAMOUS),
        new RegionSpec("서울", RegionTier.FAMOUS),
        new RegionSpec("강릉", RegionTier.FAMOUS),
        new RegionSpec("순천", RegionTier.MINOR),
        new RegionSpec("영주", RegionTier.MINOR),
        new RegionSpec("공주", RegionTier.MINOR),
        new RegionSpec("통영", RegionTier.MINOR),
        new RegionSpec("삼척", RegionTier.MINOR)
    );

    private record KeywordSetSpec(String id, List<KeywordType> keywords) {}

    /**
     * duration 카테고리(ONE_DAY 등)는 넣지 않는다 — 실제 일수(3일)와 모순되는데,
     * 그건 이미 알려진 별개 결함이라 환각 측정에 노이즈만 더한다.
     */
    private static final List<KeywordSetSpec> KEYWORD_SETS = List.of(
        new KeywordSetSpec("A", List.of(KeywordType.WALK, KeywordType.COUPLE,
            KeywordType.HEALING, KeywordType.SENSIBILITY, KeywordType.COST_EFFECTIVE)),
        new KeywordSetSpec("B", List.of(KeywordType.CAR, KeywordType.FAMILY,
            KeywordType.NATURE, KeywordType.NORMAL)),
        new KeywordSetSpec("C", List.of(KeywordType.WALK, KeywordType.FRIENDS,
            KeywordType.FOOD, KeywordType.ACTIVITY, KeywordType.PREMIUM))
    );

    // ── 결과 레코드 ────────────────────────────────────────────────────────────

    /** 점수 구간. bestScore -2 = 카카오 API 오류, -1 = 검색 결과 0건, 그 외 0~10. */
    private static final String BAND_KAKAO_ERROR = "KAKAO_ERROR";
    private static final String BAND_NO_RESULT = "NO_RESULT";
    private static final String BAND_S0 = "S0";
    private static final String BAND_S1_4 = "S1_4";
    private static final String BAND_S5_7 = "S5_7";
    private static final String BAND_S8_10 = "S8_10";

    private static final List<String> ALL_BANDS = List.of(
        BAND_KAKAO_ERROR, BAND_NO_RESULT, BAND_S0, BAND_S1_4, BAND_S5_7, BAND_S8_10);

    /** 자동 프록시가 "환각 의심"으로 분류하는 구간. */
    private static final Set<String> SUSPECT_BANDS = Set.of(BAND_NO_RESULT, BAND_S0, BAND_S1_4);

    private record PlaceRow(
        int requestId, String location, RegionTier tier, String keywordSetId,
        int day, int placeIndex, String aiPlaceName,
        int kakaoTotalCount, int bestScore, String scoreBand,
        String matchedPlaceName, String matchedCategory, String matchedAddress, String matchedPlaceUrl
    ) {}

    private record RequestOutcome(
        int requestId, String location, RegionTier tier, String keywordSetId,
        boolean parseSuccess, String parseError,
        int dayCount, String placesPerDay, int totalPlaces,
        int duplicatePlaceCount, int startTimeViolationCount
    ) {}

    // ── 측정 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("현재 단일 Gemini 호출 구조의 환각률 baseline을 측정하고 수동 검증 워크시트를 생성한다")
    void measureHallucinationBaseline() throws Exception {
        Map<String, String> env = loadDotEnv(Path.of(".env"));
        String geminiKey = resolve(env, "GEMINI_API_KEY");
        String kakaoKey = resolve(env, "KAKAO_API_KEY");

        assumeTrue(geminiKey != null && kakaoKey != null,
            "이 측정은 실제 GEMINI_API_KEY / KAKAO_API_KEY 가 필요하다 (.env 또는 환경변수)");

        // 프로덕션 빈 구성과 동일하게 조립한다 (GeminiConfig / KakaoConfig 참고).
        // WebClient는 KakaoConfig의 팩토리를 그대로 호출한다 — 여기서 따로 조립하면
        // 타임아웃·커넥션 풀 설정이 프로덕션과 갈라져 측정이 실제 동작을 반영하지 못한다.
        GeminiService geminiService = new GeminiService(Client.builder().apiKey(geminiKey).build());
        KakaoLocalClient kakaoLocalClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));

        // MyCourseServiceImpl 이 주입받는 ObjectMapper 는 Spring Boot 자동설정이라 JavaTimeModule 이
        // 등록돼 있다(PlaceDto.startTime 이 LocalTime). 여기서는 수동으로 맞춰준다.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        List<RequestSpec> fullInputSet = buildInputSet();
        // LLM API의 일일 한도가 측정 규모의 상한이라, 전체 입력 세트를 여러 배치로 나눠 돌린 뒤
        // 결과 CSV를 합칠 수 있어야 한다. requestId 는 전체 세트 기준으로 고정이므로 배치가 달라도
        // 병합 시 충돌하지 않는다.
        int from = (int) setting("hallucination.requestFrom", "HALLUCINATION_REQUEST_FROM", 1);
        int limit = (int) setting("hallucination.requestLimit", "HALLUCINATION_REQUEST_LIMIT",
            fullInputSet.size());
        long delayMs = setting("hallucination.delayMs", "HALLUCINATION_DELAY_MS", DEFAULT_DELAY_MS);

        int startIndex = Math.min(Math.max(0, from - 1), fullInputSet.size());
        int endIndex = Math.min(startIndex + limit, fullInputSet.size());
        List<RequestSpec> inputSet = fullInputSet.subList(startIndex, endIndex);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path rawDir = RESULTS_DIR.resolve("raw-" + timestamp);
        Files.createDirectories(rawDir);

        System.out.printf("%n=== AI 코스 생성 환각률 baseline 측정 시작 ===%n");
        System.out.printf("요청 #%d~#%d (%d건 / 전체 %d건), 일수 %d, 요청 간 지연 %,dms%n%n",
            startIndex + 1, endIndex, inputSet.size(), fullInputSet.size(), TRIP_DAYS, delayMs);

        List<PlaceRow> placeRows = new ArrayList<>();
        List<RequestOutcome> outcomes = new ArrayList<>();
        int consecutiveCallFailures = 0;
        int processed = 0;

        for (RequestSpec spec : inputSet) {
            processed++;
            System.out.printf("[%2d/%2d] #%-2d %s (%s) / 키워드셋 %s ... ",
                processed, inputSet.size(), spec.requestId(), spec.region().name(),
                spec.region().tier(), spec.keywordSet().id());

            String json;
            try {
                json = callWithRetry(geminiService, spec);
                consecutiveCallFailures = 0;
            } catch (Exception e) {
                System.out.printf("Gemini 호출 실패: %s%n", oneLine(e.toString()));
                outcomes.add(failedRequest(spec, "GEMINI_CALL_FAILED: " + e));
                consecutiveCallFailures++;

                // 쿼터가 소진되면 남은 요청을 전부 태워도 실패만 쌓인다 — 조기 중단해 시간을 아끼고
                // "어디까지 측정됐는지"를 명확히 남긴다. 다음 배치는 HALLUCINATION_REQUEST_FROM 으로
                // 중단 지점부터 이어서 돌리면 된다.
                if (consecutiveCallFailures >= ABORT_AFTER_CONSECUTIVE_FAILURES) {
                    System.out.printf("%n!! 호출이 연속 %d회 실패해 측정을 조기 중단한다 "
                            + "(남은 %d건 미실행). 쿼터가 회복되면 "
                            + "HALLUCINATION_REQUEST_FROM=%d 으로 이어서 측정하라.%n%n",
                        consecutiveCallFailures,
                        inputSet.size() - processed,
                        spec.requestId() - consecutiveCallFailures + 1);
                    break;
                }
                sleep(delayMs);
                continue;
            }

            // 파싱 성공/실패와 무관하게 원본 응답을 남긴다 — 파싱 실패의 실제 원인(예: trailing comma)을
            // 사후에 확인하려면 원본이 반드시 있어야 한다. 2026-08-11 실측에서 이게 없어 28.6%의
            // 파싱 실패 원인을 확정하지 못했다.
            Files.writeString(rawDir.resolve(String.format("response-%02d-%s-%s.json",
                spec.requestId(), spec.region().name(), spec.keywordSet().id())), json,
                StandardCharsets.UTF_8);

            GeminiCourseDto dto;
            try {
                dto = objectMapper.readValue(json, GeminiCourseDto.class);
            } catch (Exception e) {
                // 파싱 실패 자체가 품질 지표다 — 현재 프롬프트의 JSON 예시에 trailing comma 가 있다
                System.out.printf("JSON 파싱 실패: %s%n", oneLine(e.getMessage()));
                outcomes.add(failedRequest(spec, "JSON_PARSE_FAILED: " + e.getMessage()));
                sleep(delayMs);
                continue;
            }

            List<PlaceRow> rowsForRequest = groundPlaces(spec, dto, kakaoLocalClient);
            placeRows.addAll(rowsForRequest);
            outcomes.add(summarize(spec, dto, rowsForRequest.size()));

            System.out.printf("장소 %d개 검증 완료%n", rowsForRequest.size());
            sleep(delayMs);
        }

        writePlaceCsv(timestamp, placeRows);
        writeRequestCsv(timestamp, outcomes);
        writeManualVerificationCsv(timestamp, placeRows);

        report(placeRows, outcomes, timestamp);

        assertThat(placeRows).as("측정된 장소 표본이 하나도 없다 — API 키나 네트워크를 확인하라").isNotEmpty();
    }

    // ── 파이프라인 단계 재현 ────────────────────────────────────────────────────

    private List<PlaceRow> groundPlaces(RequestSpec spec, GeminiCourseDto dto,
        KakaoLocalClient kakaoLocalClient) {

        List<PlaceRow> rows = new ArrayList<>();
        if (dto.daySchedules() == null) {
            return rows;
        }

        for (DayScheduleDto daySchedule : dto.daySchedules()) {
            if (daySchedule.places() == null) {
                continue;
            }
            int placeIndex = 0;
            for (PlaceDto place : daySchedule.places()) {
                rows.add(groundOnePlace(spec, daySchedule.day(), placeIndex++, place, kakaoLocalClient));
            }
        }
        return rows;
    }

    private PlaceRow groundOnePlace(RequestSpec spec, int day, int placeIndex, PlaceDto place,
        KakaoLocalClient kakaoLocalClient) {

        String aiPlaceName = place.placeName() == null ? "" : place.placeName();

        // KakaoLocalClient.findBestPlace(39행)와 동일한 키워드 조립.
        // 실제 호출부(MyCourseServiceImpl:564-565)는 placeLocation 자리에 request.location() 을 넘긴다.
        String placeLocation = spec.region().name();
        String keyword = placeLocation + " " + aiPlaceName;

        KakaoSearchResponse response;
        try {
            response = kakaoLocalClient.searchPlace(keyword, 5);
        } catch (Exception e) {
            // findBestPlace 는 WebClientResponseException 을 BusinessException 으로 바꾸지만,
            // 여기서는 측정을 계속하기 위해 오류 자체를 하나의 결과로 기록한다.
            return new PlaceRow(spec.requestId(), placeLocation, spec.region().tier(),
                spec.keywordSet().id(), day, placeIndex, aiPlaceName,
                -1, -2, BAND_KAKAO_ERROR, "", "", "", "");
        }

        List<Document> docs = response == null ? null : response.documents();
        int totalCount = (response == null || response.meta() == null) ? -1 : response.meta().total_count();

        if (docs == null || docs.isEmpty()) {
            return new PlaceRow(spec.requestId(), placeLocation, spec.region().tier(),
                spec.keywordSet().id(), day, placeIndex, aiPlaceName,
                totalCount, -1, BAND_NO_RESULT, "", "", "", "");
        }

        // private score() 를 리플렉션으로 원본 그대로 재사용한다 (로직 복제 금지)
        Document best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Document doc : docs) {
            int score = invokeScore(kakaoLocalClient, doc, aiPlaceName, placeLocation);
            if (score > bestScore) {
                bestScore = score;
                best = doc;
            }
        }

        String address = (best.road_address_name() != null && !best.road_address_name().isBlank())
            ? best.road_address_name()
            : nullToEmpty(best.address_name());

        return new PlaceRow(spec.requestId(), placeLocation, spec.region().tier(),
            spec.keywordSet().id(), day, placeIndex, aiPlaceName,
            totalCount, bestScore, bandOf(bestScore),
            nullToEmpty(best.place_name()), nullToEmpty(best.category_name()),
            address, nullToEmpty(best.place_url()));
    }

    private int invokeScore(KakaoLocalClient client, Document doc, String placeName, String placeLocation) {
        Integer score = ReflectionTestUtils.invokeMethod(client, "score", doc, placeName, placeLocation);
        return score == null ? 0 : score;
    }

    private static String bandOf(int score) {
        if (score <= -2) return BAND_KAKAO_ERROR;
        if (score == -1) return BAND_NO_RESULT;
        if (score == 0) return BAND_S0;
        if (score <= 4) return BAND_S1_4;
        if (score <= 7) return BAND_S5_7;
        return BAND_S8_10;
    }

    // ── 부가 지표 집계 ─────────────────────────────────────────────────────────

    private RequestOutcome summarize(RequestSpec spec, GeminiCourseDto dto, int totalPlaces) {
        List<DayScheduleDto> days = dto.daySchedules() == null ? List.of() : dto.daySchedules();

        List<String> perDay = new ArrayList<>();
        int startTimeViolations = 0;
        Set<String> seen = new HashSet<>();
        int duplicates = 0;

        for (DayScheduleDto day : days) {
            List<PlaceDto> places = day.places() == null ? List.of() : day.places();
            perDay.add(String.valueOf(places.size()));

            LocalTime previous = null;
            for (PlaceDto place : places) {
                if (place.placeName() != null && !seen.add(place.placeName().trim())) {
                    duplicates++;
                }
                LocalTime current = place.startTime();
                if (previous != null && current != null && !current.isAfter(previous)) {
                    startTimeViolations++;
                }
                if (current != null) {
                    previous = current;
                }
            }
        }

        return new RequestOutcome(spec.requestId(), spec.region().name(), spec.region().tier(),
            spec.keywordSet().id(), true, "",
            days.size(), String.join("/", perDay), totalPlaces, duplicates, startTimeViolations);
    }

    private RequestOutcome failedRequest(RequestSpec spec, String error) {
        return new RequestOutcome(spec.requestId(), spec.region().name(), spec.region().tier(),
            spec.keywordSet().id(), false, oneLine(error), 0, "", 0, 0, 0);
    }

    /**
     * 429(RPM 초과)는 잠깐 기다리면 풀리므로 지수 백오프로 재시도한다. 다만 일일 한도 소진이면
     * 재시도해도 소용없어서, 최종 실패는 호출부의 조기 중단 로직이 받는다.
     */
    private String callWithRetry(GeminiService geminiService, RequestSpec spec) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_CALL_RETRIES; attempt++) {
            try {
                return geminiService.generateAICourse(
                    spec.region().name(), TRIP_DAYS, spec.keywordSet().keywords());
            } catch (RuntimeException e) {
                last = e;
                boolean retriable = String.valueOf(e.getMessage()).contains("429");
                if (!retriable || attempt == MAX_CALL_RETRIES) {
                    break;
                }
                long backoff = RETRY_BASE_BACKOFF_MS * (1L << attempt);   // 10s, 20s, 40s
                System.out.printf("%n    429 — %,dms 후 재시도 (%d/%d) ... ",
                    backoff, attempt + 1, MAX_CALL_RETRIES);
                sleep(backoff);
            }
        }
        throw last;
    }

    /** CSV·콘솔에 넣기 전에 개행을 없애고 길이를 제한한다 — 예외 메시지가 여러 줄이라 행이 깨진다. */
    private static String oneLine(String s) {
        String flat = nullToEmpty(s).replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
        return flat.length() <= MAX_ERROR_LENGTH ? flat : flat.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    // ── 산출물 ────────────────────────────────────────────────────────────────

    private void writePlaceCsv(String timestamp, List<PlaceRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("requestId,location,regionTier,keywordSet,day,placeIndex,aiPlaceName,")
            .append("kakaoTotalCount,bestScore,scoreBand,matchedPlaceName,matchedCategory,")
            .append("matchedAddress,matchedPlaceUrl\n");

        for (PlaceRow r : rows) {
            sb.append(r.requestId()).append(',')
                .append(csv(r.location())).append(',')
                .append(r.tier()).append(',')
                .append(csv(r.keywordSetId())).append(',')
                .append(r.day()).append(',')
                .append(r.placeIndex()).append(',')
                .append(csv(r.aiPlaceName())).append(',')
                .append(r.kakaoTotalCount()).append(',')
                .append(r.bestScore()).append(',')
                .append(r.scoreBand()).append(',')
                .append(csv(r.matchedPlaceName())).append(',')
                .append(csv(r.matchedCategory())).append(',')
                .append(csv(r.matchedAddress())).append(',')
                .append(csv(r.matchedPlaceUrl())).append('\n');
        }
        writeUtf8Bom(RESULTS_DIR.resolve("hallucination-baseline-" + timestamp + ".csv"), sb.toString());
    }

    private void writeRequestCsv(String timestamp, List<RequestOutcome> outcomes) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("requestId,location,regionTier,keywordSet,parseSuccess,parseError,")
            .append("dayCount,placesPerDay,totalPlaces,duplicatePlaceCount,startTimeViolationCount\n");

        for (RequestOutcome o : outcomes) {
            sb.append(o.requestId()).append(',')
                .append(csv(o.location())).append(',')
                .append(o.tier()).append(',')
                .append(csv(o.keywordSetId())).append(',')
                .append(o.parseSuccess()).append(',')
                .append(csv(o.parseError())).append(',')
                .append(o.dayCount()).append(',')
                .append(csv(o.placesPerDay())).append(',')
                .append(o.totalPlaces()).append(',')
                .append(o.duplicatePlaceCount()).append(',')
                .append(o.startTimeViolationCount()).append('\n');
        }
        writeUtf8Bom(RESULTS_DIR.resolve("hallucination-baseline-" + timestamp + "-requests.csv"),
            sb.toString());
    }

    /**
     * 점수 구간별 층화 추출 워크시트. 사람이 matchedPlaceUrl 을 열어 verdict 를 채운다.
     *
     * <p>층화(무작위가 아니라 구간별 균등)로 뽑는 이유: "몇 점부터 실제로 신뢰할 수 있는가"를
     * 데이터로 확인해야 임계값(계획서의 min-kakao-score: 5)이 적절한지까지 판정할 수 있다.
     * 대신 전체 환각률을 추정할 때는 구간별 비율을 전체 비중으로 가중해야 한다.
     */
    private void writeManualVerificationCsv(String timestamp, List<PlaceRow> rows) throws IOException {
        Map<String, List<PlaceRow>> byBand = new LinkedHashMap<>();
        for (String band : ALL_BANDS) {
            byBand.put(band, new ArrayList<>());
        }
        for (PlaceRow r : rows) {
            byBand.get(r.scoreBand()).add(r);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# verdict 를 채워주세요: CORRECT | LAUNDERED | WRONG_MATCH | UNVERIFIABLE\n")
            .append("#   CORRECT       AI 원안이 실존하고 카카오 매칭도 그 장소가 맞음\n")
            .append("#   LAUNDERED     AI 원안이 그 지역에 실존하지 않는데 무관한 장소로 매칭됨 (세탁된 환각)\n")
            .append("#   WRONG_MATCH   AI 원안은 실존하는데 카카오가 엉뚱한 것을 매칭\n")
            .append("#   UNVERIFIABLE  판단 불가\n")
            .append("scoreBand,bestScore,requestId,day,location,aiPlaceName,matchedPlaceName,")
            .append("matchedAddress,matchedPlaceUrl,verdict,note\n");

        Random random = new Random(SAMPLING_SEED);
        for (Map.Entry<String, List<PlaceRow>> entry : byBand.entrySet()) {
            List<PlaceRow> pool = new ArrayList<>(entry.getValue());
            Collections.shuffle(pool, random);
            for (PlaceRow r : pool.subList(0, Math.min(SAMPLES_PER_BAND, pool.size()))) {
                sb.append(r.scoreBand()).append(',')
                    .append(r.bestScore()).append(',')
                    .append(r.requestId()).append(',')   // 같은 장소명이 여러 번 나올 때 출처 구분용
                    .append(r.day()).append(',')
                    .append(csv(r.location())).append(',')
                    .append(csv(r.aiPlaceName())).append(',')
                    .append(csv(r.matchedPlaceName())).append(',')
                    .append(csv(r.matchedAddress())).append(',')
                    .append(csv(r.matchedPlaceUrl())).append(',')
                    .append(',')   // verdict — 사람이 채운다
                    .append('\n'); // note
            }
        }
        writeUtf8Bom(RESULTS_DIR.resolve("manual-verification-" + timestamp + ".csv"), sb.toString());
    }

    private void report(List<PlaceRow> rows, List<RequestOutcome> outcomes, String timestamp) {
        System.out.printf("%n=== 점수 구간 분포 (장소 표본 %,d개) ===%n", rows.size());

        Map<String, Integer> bandCounts = new LinkedHashMap<>();
        for (String band : ALL_BANDS) {
            bandCounts.put(band, 0);
        }
        for (PlaceRow r : rows) {
            bandCounts.merge(r.scoreBand(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : bandCounts.entrySet()) {
            System.out.printf("  %-12s %4d건 (%5.1f%%)%n",
                e.getKey(), e.getValue(), pct(e.getValue(), rows.size()));
        }

        long suspect = rows.stream().filter(r -> SUSPECT_BANDS.contains(r.scoreBand())).count();
        System.out.printf("%n=== 자동 프록시 환각률 ===%n");
        System.out.printf("  (NO_RESULT + S0 + S1_4) / 전체 = %d / %d = %.1f%%%n",
            suspect, rows.size(), pct(suspect, rows.size()));
        System.out.printf("  ※ 이 값은 '매칭 실패율'이며 실제 환각률의 하한 추정치다.%n");
        System.out.printf("     S5_7/S8_10 안에 섞인 '세탁된 환각'은 수동 검증으로만 잡을 수 있다.%n");

        System.out.printf("%n=== 지역 tier별 자동 프록시 환각률 ===%n");
        for (RegionTier tier : RegionTier.values()) {
            List<PlaceRow> tierRows = rows.stream().filter(r -> r.tier() == tier).toList();
            long tierSuspect = tierRows.stream().filter(r -> SUSPECT_BANDS.contains(r.scoreBand())).count();
            System.out.printf("  %-7s %4d / %4d = %.1f%%%n",
                tier, tierSuspect, tierRows.size(), pct(tierSuspect, tierRows.size()));
        }

        System.out.printf("%n=== 부가 지표 (요청 %d건) ===%n", outcomes.size());
        long parseFailed = outcomes.stream().filter(o -> !o.parseSuccess()).count();
        long dayMismatch = outcomes.stream()
            .filter(o -> o.parseSuccess() && o.dayCount() != TRIP_DAYS).count();
        int duplicates = outcomes.stream().mapToInt(RequestOutcome::duplicatePlaceCount).sum();
        int violations = outcomes.stream().mapToInt(RequestOutcome::startTimeViolationCount).sum();

        System.out.printf("  JSON 파싱/호출 실패      %d / %d (%.1f%%)%n",
            parseFailed, outcomes.size(), pct(parseFailed, outcomes.size()));
        System.out.printf("  일수 불일치(≠%d)         %d / %d (%.1f%%)%n",
            TRIP_DAYS, dayMismatch, outcomes.size(), pct(dayMismatch, outcomes.size()));

        // 프롬프트는 "각 day마다 최소 3개, 최대 6개"를 요구한다 — 실제로 지켜지는지 센다
        int totalDays = 0;
        int outOfRangeDays = 0;
        for (RequestOutcome o : outcomes) {
            if (!o.parseSuccess() || o.placesPerDay().isBlank()) {
                continue;
            }
            for (String count : o.placesPerDay().split("/")) {
                totalDays++;
                int n = Integer.parseInt(count);
                if (n < 3 || n > 6) {
                    outOfRangeDays++;
                }
            }
        }
        System.out.printf("  day당 장소 수 3~6 위반   %d / %d day (%.1f%%)%n",
            outOfRangeDays, totalDays, pct(outOfRangeDays, totalDays));
        System.out.printf("  중복 장소                %d건 (전체 장소 대비 %.1f%%)%n",
            duplicates, pct(duplicates, rows.size()));
        System.out.printf("  startTime 오름차순 위반  %d건 (전체 장소 대비 %.1f%%)%n",
            violations, pct(violations, rows.size()));

        System.out.printf("%n=== 산출물 ===%n");
        System.out.printf("  Gemini 원본 응답 results/raw-%s/  ← 파싱 실패 원인 확인용%n", timestamp);
        System.out.printf("  장소별 원본     results/hallucination-baseline-%s.csv%n", timestamp);
        System.out.printf("  요청별 지표     results/hallucination-baseline-%s-requests.csv%n", timestamp);
        System.out.printf("  수동 검증 대상  results/manual-verification-%s.csv  ← verdict 를 채워주세요%n",
            timestamp);
        System.out.printf("%n  수동 검증 후 전체 환각률 추정:%n");
        System.out.printf("    세탁된 환각률 = Σ_구간 (구간별 LAUNDERED 비율 × 구간별 전체 비중)%n");
        System.out.printf("    전체 환각률   = 자동 프록시 환각률 + 세탁된 환각률%n");
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private record RequestSpec(int requestId, RegionSpec region, KeywordSetSpec keywordSet) {}

    private List<RequestSpec> buildInputSet() {
        List<RequestSpec> specs = new ArrayList<>();
        int id = 1;
        for (RegionSpec region : REGIONS) {
            for (KeywordSetSpec keywordSet : KEYWORD_SETS) {
                specs.add(new RequestSpec(id++, region, keywordSet));
            }
        }
        return specs;
    }

    /** Spring 컨텍스트가 없어 spring-dotenv 가 동작하지 않으므로 .env 를 직접 읽는다. */
    private static Map<String, String> loadDotEnv(Path path) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return env;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) {
                env.put(key, value);
            }
        }
        return env;
    }

    /**
     * 실행 파라미터를 시스템 프로퍼티 → 환경변수 → 기본값 순으로 읽는다.
     *
     * <p>Gradle의 {@code Test} task는 {@code -D}로 준 시스템 프로퍼티를 테스트 JVM에 자동 전달하지
     * 않지만(전달하려면 build.gradle에 {@code systemProperties} 설정이 필요하다) 환경변수는 자식
     * 프로세스에 상속된다. build.gradle을 건드리지 않고 조절할 수 있도록 둘 다 지원한다.
     */
    private static long setting(String systemProperty, String envVar, long defaultValue) {
        String raw = System.getProperty(systemProperty);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(envVar);
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 실제 OS 환경변수가 있으면 그것을 우선한다 — spring-dotenv 와 동일한 우선순위. */
    private static String resolve(Map<String, String> dotEnv, String key) {
        String fromOs = System.getenv(key);
        if (fromOs != null && !fromOs.isBlank()) {
            return fromOs;
        }
        return dotEnv.get(key);
    }

    private static void writeUtf8Bom(Path path, String content) throws IOException {
        // Excel(Windows)이 UTF-8 CSV의 한글을 깨뜨리지 않도록 BOM을 붙인다 — 수동 검증 워크시트를
        // 사람이 스프레드시트로 열기 때문이다.
        Files.writeString(path, "﻿" + content, StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        String s = nullToEmpty(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static double pct(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
