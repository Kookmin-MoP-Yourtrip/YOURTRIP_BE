package backend.yourtrip.global.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.benchmark.BaselineInputSet.KeywordSetSpec;
import backend.yourtrip.global.benchmark.BaselineInputSet.RegionSpec;
import backend.yourtrip.global.benchmark.BaselineInputSet.RegionTier;
import backend.yourtrip.global.benchmark.BaselineInputSet.RequestSpec;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.LlmCall;
import backend.yourtrip.global.ai.LlmResponseParser;
import backend.yourtrip.global.ai.LlmRetryExecutor;
import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.exception.LlmParseException;
import backend.yourtrip.global.ai.exception.LlmTransportException;
import backend.yourtrip.global.ai.exception.LlmTruncatedResponseException;
import backend.yourtrip.global.ai.openai.OpenAiLlmClient;
import backend.yourtrip.global.benchmark.LegacyGeminiCourseDto.DayScheduleDto;
import backend.yourtrip.global.benchmark.LegacyGeminiCourseDto.PlaceDto;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.PlaceLookup;
import backend.yourtrip.global.kakao.PlaceMatchScorer;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AI 코스 생성(단일 LLM 호출) 구조의 **환각률 baseline 측정** 하네스.
 * 멀티 에이전트 파이프라인(docs/tasks/ai-course-create/멀티-에이전트-파이프라인.md) 도입 전 값을 남기는 것이 목적이며,
 * 도입 후 동일한 방법론으로 재측정해 before/after를 비교한다.
 *
 * <p><b>2단계에서 측정 대상 LLM이 Gemini에서 OpenAI로 바뀌었다.</b> 최초 측정(환각률 25.6%,
 * JSON 실패율 16.7%)은 {@code gemini-2.5-flash} 기준이고, 지금 이 하네스는 같은 입력 세트와 같은
 * 판정 로직으로 OpenAI를 잰다 — 아래 "측정 축" 참고.
 *
 * <p><b>판정 로직은 한 줄도 바꾸지 않는다.</b> 값이 비교 가능하려면 같은 자로 재야 하므로,
 * 점수 계산을 복제하지 않고 프로덕션의 {@link PlaceMatchScorer}를 그대로 호출한다. 복제하면 원본과
 * drift가 생겨 비교 근거가 약해진다.
 *
 * <p>예전에는 같은 목적으로 {@code KakaoLocalClient}의 <b>private {@code score()}를 리플렉션으로</b>
 * 불렀다. 복제를 피한다는 목표는 같았지만, 그 방식은 <b>프로덕션의 private 시그니처를 이 하네스의
 * 계약으로 만든다</b> — 이름이나 인자를 바꾸면 컴파일은 통과하고 여기만 런타임에 깨지는데, 이 클래스는
 * {@code @Tag("benchmark")}라 일반 빌드에서 돌지 않아 <b>다음 측정 때까지 아무도 모른다.</b>
 * 점수 계산을 공개 순수 함수로 옮겨 리플렉션 없이 같은 값을 얻는다.
 *
 * <p><b>{@code searchPlace()}가 아니라 {@link KakaoLocalClient#lookupBestPlace}를 쓰는 이유</b>:
 * 프로덕션은 검색 결과에 <b>이름 게이트를 먼저 걸고</b> 통과한 후보 중에서 점수로 순위를 매긴다.
 * 하네스가 검색만 불러 직접 채점하면 게이트를 우회해 <b>옛 매칭 로직을 재현하게 된다</b> — 이름이
 * 안 맞아도 지역·카테고리만 같으면 실존 장소로 매칭하던 시절이다. 환각률 측정의 의의는 LLM의 거짓
 * 응답을 잡는 데 있지 옛 코드를 재현하는 데 있지 않으므로 <b>프로덕션과 같은 판정</b>을 쓴다.
 * <b>결과를 값으로 받는 것도 이 하네스의 요구다</b> — 무결과와 이름 불일치를 갈라야 자동 프록시와
 * 이름 불일치율을 따로 낼 수 있다. 예전 {@code findBestPlace()}는 둘을 똑같이 {@code null}로
 * 뭉갰고, 그래서 여기에 쓸 수 없었다(그 메서드는 호출부가 사라져 이후 제거됐다).
 *
 * <p><b>이 측정이 재는 것은 "환각률"이 아니라 "장소 미확보율"이다.</b> 둘은 상관관계가 있지만
 * 같지 않다 — 실존하는데 카카오에 없거나 게이트가 표기 차이를 못 알아보는 거짓 양성과, 지어낸 이름이
 * 게이트를 통과해 무관한 실제 업소로 실리는 거짓 음성(세탁된 환각)이 섞여 있다. 후자는 자동으로
 * 잡을 수 없어 수동 검증 워크시트로 별도 추정한다.
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
 *
 * <h2>2단계: 측정 축이 둘로 늘었다</h2>
 * <b>LLM 벤더가 Gemini에서 OpenAI로 바뀌므로</b>, 파이프라인 도입 후 값을 before(25.6%)와 그냥
 * 비교하면 "모델 교체"와 "파이프라인 도입"이 섞여 개선폭을 어느 쪽에도 귀속시킬 수 없다. 그래서
 * 중간 측정점을 찍는데, 실제로 바뀌는 변수는 셋이다.
 * <pre>
 *   V1 모델          gemini-2.5-flash          → OpenAI
 *   V2 출력 강제     프롬프트에 스키마를 글로  → response_format.json_schema (디코딩 레벨 강제)
 *   V3 구조          단일 호출                 → 5단계 파이프라인
 * </pre>
 * V2를 켠 판과 끈 판을 <b>둘 다</b> 재면 셋이 각각 분리된다. 덤으로
 * {@code BASELINE-ARTIFACT-ANALYSIS.md} 판정 3의 <b>"파싱 실패의 원인은 절단이라 구조화 출력으로는
 * 안 사라진다"는 추론이 데이터로 검증</b>된다.
 *
 * <p>모델 축이 하나 더 붙는다. {@code gpt-5.6-luna}의 한국어 지역 지식은 0단계에서 확인하지
 * 못했으므로(ROADMAP 0-4), luna와 nano를 각각 재서 <b>Curator 모델을 감이 아니라 데이터로</b>
 * 확정한다.
 *
 * <pre>
 * 축 선택:  BASELINE_MODEL=luna|nano   BASELINE_SCHEMA_MODE=prompt|json_schema
 * 예)      BASELINE_MODEL=nano BASELINE_SCHEMA_MODE=json_schema ./gradlew benchmarkTest ... --rerun
 * </pre>
 *
 * <h2>비교 가능성을 위해 건드리지 않는 것</h2>
 * 입력 세트(지역 10 × 키워드셋 3, 3일 고정), <b>프로덕션 {@link KakaoLocalClient#lookupBestPlace}
 * 호출</b>(이름 게이트·점수·후보 수 5가 자동으로 따라온다), 점수 구간, 층화 추출 시드 42,
 * <b>그리고 프롬프트 95줄 원문</b>. 특히 json_schema 판에서도 프롬프트의
 * 스키마 구간을 빼지 않는다 — 빼면 V2 외에 프롬프트까지 변수가 되어 측정이 무의미해진다
 * (프롬프트 슬림화는 6단계의 일이다). 프롬프트는 {@link LegacyGeminiPrompt#buildPrompt}를
 * 호출한다 — 원래는 {@code GeminiService.buildPrompt}를 직접 불러 drift를 차단했으나, 8-4에서
 * {@code global/gemini}가 삭제되며 원문이 <b>바이트 동일성 검증을 거쳐</b> 하네스 소유로 이관됐다.
 *
 * <p><b>의미 재시도는 끈다</b>({@code semantic-attempts: 1}). 깨진 응답을 한 번 더 물어서 고치면
 * 파싱 실패율이 "재시도 후"의 값이 되는데, Gemini 측정값에는 그런 보정이 없었다.
 */
@Tag("benchmark")
class AiHallucinationBaselineTest {

    /** 여행 일수. 정의와 근거는 {@link BaselineInputSet#TRIP_DAYS}. */
    private static final int TRIP_DAYS = BaselineInputSet.TRIP_DAYS;

    /** RPM 방어용 요청 간 지연. -Dhallucination.delayMs 로 조정한다. */
    private static final long DEFAULT_DELAY_MS = 5_000L;

    /**
     * 측정 대상 모델. 0단계에서 Planner·Curator에 luna, PlaceProfile에 nano를 배정했지만
     * luna의 한국어 지역 지식은 확인하지 못했다 — 여기서 둘을 같은 조건으로 재서 확정한다.
     */
    private static final Map<String, String> MODELS = Map.of(
        "luna", "gpt-5.6-luna",
        "nano", "gpt-5-nano");

    /** 측정 축: 구조화 출력을 켤 것인가. */
    private static final String SCHEMA_MODE_PROMPT = "prompt";
    private static final String SCHEMA_MODE_JSON_SCHEMA = "json_schema";

    /** 이 하네스가 재현하는 것은 파이프라인이 아니라 단일 호출이라, agent 이름도 하나뿐이다. */
    private static final String AGENT_NAME = "baseline";

    /** 프롬프트가 요구하는 응답 모양을 그대로 옮긴 스키마. 루트는 반드시 객체여야 한다(0-3b). */
    private static final String SCHEMA_RESOURCE = "/schemas/single-call-course.json";

    /**
     * 프롬프트 원문이 요구하는 출력 상한과 맞춘다(구 {@code GeminiService.getGenerationConfig}의
     * {@code maxOutputTokens}). 이 값을 바꾸면 절단 발생률이 달라져 측정이 비교 불가능해진다.
     */
    private static final int MAX_OUTPUT_TOKENS = 4096;

    /**
     * <b>온도를 보내지 않는다(null).</b>
     *
     * <p>원래는 Gemini 측정과 같은 조건을 만들려고 프롬프트 원문의 {@code temperature 0.3}을 그대로
     * 쓰려 했으나, 실호출에서 <b>{@code gpt-5.6-luna}·{@code gpt-5-nano} 모두 커스텀 온도를 400으로
     * 거부</b>했다({@code "Only the default (1) value is supported"}).
     *
     * <p><b>이건 측정의 한계로 기록해야 한다.</b> Gemini는 0.3, OpenAI는 기본값 1로 도는 셈이라
     * "모델 교체" 축에 <b>온도 변화가 함께 묶인다.</b> 분리할 방법이 없다 — 모델이 값을 받지 않으므로
     * 이 차이는 모델 선택에 딸려오는 성질이지 우리가 고를 수 있는 변수가 아니다. 온도가 높을수록
     * 환각이 늘어나는 경향을 감안하면 <b>OpenAI 쪽에 불리한 조건</b>이며, 그만큼 이 측정은
     * 보수적인 하한이 된다.
     */
    private static final Double TEMPERATURE = null;

    /** 수동 검증 워크시트에서 점수 구간별로 뽑을 표본 수. */
    private static final int SAMPLES_PER_BAND = 10;

    /** 층화 추출을 재현 가능하게 만드는 고정 시드 — 재분석 시 같은 표본이 나와야 한다. */
    private static final long SAMPLING_SEED = 42L;

    /**
     * 호출이 연속 이 횟수만큼 최종 실패하면 쿼터 소진으로 보고 측정을 조기 중단한다.
     *
     * <p>개별 요청의 429 재시도는 더 이상 여기서 구현하지 않는다 —
     * {@link LlmRetryExecutor}가 프로덕션과 같은 정책으로 처리하고, 그 값은
     * {@link #baselineProperties}가 정한다. 하네스가 자체 재시도를 들고 있으면 프로덕션의
     * 재시도와 곱해져 실제 시도 횟수를 알 수 없게 된다.
     */
    private static final int ABORT_AFTER_CONSECUTIVE_FAILURES = 3;

    /** CSV·콘솔에 남기는 예외 메시지의 최대 길이. */
    private static final int MAX_ERROR_LENGTH = 300;

    private static final Path RESULTS_DIR = Path.of("results");

    // ── 입력 세트: 지역 10개 × 키워드 조합 3개 = 30요청 ──────────────────────────

    // 정의는 BaselineInputSet 이 소유한다 — 이 세트를 쓰는 측정이 둘 이상이고, 하네스마다
    // 사본을 두면 한쪽만 바뀌어도 겉으로는 드러나지 않는다.
    private static final List<RegionSpec> REGIONS = BaselineInputSet.REGIONS;

    private static final List<KeywordSetSpec> KEYWORD_SETS = BaselineInputSet.KEYWORD_SETS;

    // ── 결과 레코드 ────────────────────────────────────────────────────────────

    /**
     * 결과 구간. 앞의 셋은 {@link PlaceLookup}의 변형을 그대로 옮긴 것이고, 뒤의 넷은
     * {@code Found}의 점수를 나눈 것이다. bestScore 는 -3 = 이름 게이트 전멸,
     * -2 = 카카오 API 오류, -1 = 검색 결과 0건, 그 외 0~10.
     */
    private static final String BAND_KAKAO_ERROR = "KAKAO_ERROR";
    private static final String BAND_NO_RESULT = "NO_RESULT";
    private static final String BAND_NAME_MISMATCH = "NAME_MISMATCH";
    private static final String BAND_S0 = "S0";
    private static final String BAND_S1_4 = "S1_4";
    private static final String BAND_S5_7 = "S5_7";
    private static final String BAND_S8_10 = "S8_10";

    private static final List<String> ALL_BANDS = List.of(
        BAND_KAKAO_ERROR, BAND_NO_RESULT, BAND_NAME_MISMATCH,
        BAND_S0, BAND_S1_4, BAND_S5_7, BAND_S8_10);

    /**
     * 자동 프록시가 세는 구간. <b>{@code NO_RESULT} 하나뿐이다.</b>
     *
     * <p>예전에는 {@code S0}·{@code S1_4}도 넣었는데, <b>표기만 다른 장소가 어느 구간에 떨어질지를
     * 카카오의 {@code category_group_code} 부여 여부가 갈랐다</b> — 카테고리 +2가 붙으면 5점
     * ({@code S5_7}, 안 걸림), 안 붙으면 3점({@code S1_4}, 걸림). 실측에서 표기 차이 32건이
     * 15 / 17로 쪼개졌다. {@code NO_RESULT}는 검색 결과가 0건이라 점수 구성 자체가 개입하지 않아
     * 그런 자의성이 없다.
     *
     * <p>{@code NAME_MISMATCH}는 <b>따로 센다</b>(이름 불일치율). 게이트에도 거짓 양성이 있기
     * 때문이다("해운대 해변" → "해운대해수욕장"). 둘의 합은 장소 미확보율로 따로 보고한다.
     */
    private static final Set<String> SUSPECT_BANDS = Set.of(BAND_NO_RESULT);

    /**
     * 장소 1건의 검증 결과.
     *
     * <p>{@code kakaoTotalCount}를 뺐다 — {@link PlaceLookup}은 검색 총건수를 노출하지 않고,
     * 그 값을 얻자고 검색을 두 번 부르면 판정과 기록이 서로 다른 응답을 볼 수 있다. 대신
     * {@code rejectedCandidateName}이 들어왔다: 이름 게이트가 무엇을 걸렀는지 봐야 거짓 양성
     * (같은 곳인데 표기가 다른 경우)을 사후에 가려낼 수 있다.
     */
    private record PlaceRow(
        int requestId, String location, RegionTier tier, String keywordSetId,
        int day, int placeIndex, String aiPlaceName,
        int bestScore, String scoreBand,
        String matchedPlaceName, String matchedCategory, String matchedAddress,
        String matchedPlaceUrl, String rejectedCandidateName
    ) {}

    /**
     * 요청 1건의 결말.
     *
     * <p><b>기존에는 이 넷이 {@code parseSuccess} 하나로 뭉쳐 있었다.</b> 그래서 "파싱 실패율
     * 28.6%"가 실은 호출 실패 16건을 제외한 14건 기준이라는 것이 몇 달 뒤 재분석에서야 드러났고,
     * 전체 30요청 기준으로는 16.7%였다({@code BASELINE-ARTIFACT-ANALYSIS.md} 판정 3).
     * 분모를 명시하려면 분자부터 갈라져 있어야 한다.
     */
    private enum Outcome {
        /** 정상 — 응답을 DTO로 역직렬화했다. */
        OK,
        /** 재시도를 소진하고도 응답을 못 받았다(429/5xx/타임아웃). 파싱 실패율의 분자가 아니다. */
        CALL_FAILED,
        /** 응답이 끝까지 오지 않았다. 구조화 출력으로 막히지 않는 실패라 따로 센다. */
        TRUNCATED,
        /** 응답은 왔는데 스키마를 어겼다. 구조화 출력이 없애야 하는 바로 그 실패다. */
        PARSE_FAILED
    }

    private record RequestOutcome(
        int requestId, String location, RegionTier tier, String keywordSetId,
        Outcome outcome, String failureDetail,
        String finishReason, int responseBytes,
        int dayCount, String placesPerDay, int totalPlaces,
        int duplicatePlaceCount, int startTimeViolationCount
    ) {}

    // ── 측정 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OpenAI 단일 호출 구조의 환각률 baseline을 측정하고 수동 검증 워크시트를 생성한다")
    void measureHallucinationBaseline() throws Exception {
        // 재채점 모드에서는 LLM 측정을 건너뛴다 — --tests 필터가 클래스 단위라 둘 다 매칭되는데,
        // 재채점만 원했는데 LLM 30회가 같이 나가면 비용·한도 사고다.
        assumeTrue(text("baseline.rescoreFrom", "BASELINE_RESCORE_FROM", "").isBlank(),
            "BASELINE_RESCORE_FROM 지정 시 rescoreFromCsv 만 실행된다");

        Map<String, String> env = loadDotEnv(Path.of(".env"));
        String openAiKey = resolve(env, "OPENAI_API_KEY");
        String kakaoKey = resolve(env, "KAKAO_API_KEY");

        assumeTrue(openAiKey != null && kakaoKey != null,
            "이 측정은 실제 OPENAI_API_KEY / KAKAO_API_KEY 가 필요하다 (.env 또는 환경변수)");

        String modelKey = text("baseline.model", "BASELINE_MODEL", "luna");
        String schemaMode = text("baseline.schemaMode", "BASELINE_SCHEMA_MODE", SCHEMA_MODE_PROMPT);
        String model = MODELS.get(modelKey);
        assertThat(model).as("BASELINE_MODEL 은 %s 중 하나여야 한다", MODELS.keySet()).isNotNull();
        assertThat(schemaMode).as("BASELINE_SCHEMA_MODE 는 prompt 또는 json_schema 여야 한다")
            .isIn(SCHEMA_MODE_PROMPT, SCHEMA_MODE_JSON_SCHEMA);

        // 구조화 출력을 끈 판에서는 스키마를 아예 보내지 않는다 — 그래야 Gemini 의
        // responseMimeType(스키마 없는 JSON 모드)과 같은 조건이 되어 모델 교체만 분리된다.
        String responseSchema = SCHEMA_MODE_JSON_SCHEMA.equals(schemaMode) ? loadSchema() : null;

        // 추론 강도. 비워두면 보내지 않는다(모델 기본값). gpt-5-nano 는 기본값으로 두면
        // max-output-tokens 4096을 추론에 다 쓰고 본문을 0바이트로 돌려주므로 낮춰야 한다.
        String reasoningEffort = text("baseline.reasoningEffort", "BASELINE_REASONING_EFFORT", "");

        // MyCourseServiceImpl 이 주입받는 ObjectMapper 는 Spring Boot 자동설정이라 JavaTimeModule 이
        // 등록돼 있다(PlaceDto.startTime 이 LocalTime). 여기서는 수동으로 맞춰준다.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        // 프로덕션 조립을 그대로 호출한다. 여기서 따로 조립하면 타임아웃·재시도·오류 분류가
        // 프로덕션과 갈라져 측정이 실제 동작을 반영하지 못한다
        // (KakaoConfig.buildKakaoWebClient 가 같은 이유로 만들어진 선례다).
        AiLlmProperties llmProperties = baselineProperties(openAiKey, model, reasoningEffort);
        CapturingParser parser = new CapturingParser(objectMapper);
        OpenAiLlmClient llmClient = new OpenAiLlmClient(llmProperties, parser,
            new LlmRetryExecutor(llmProperties),
            // 하네스는 스프링 컨텍스트를 띄우지 않으므로 레지스트리도 직접 만든다. 측정에는
            // 쓰지 않고, 어댑터가 요구하는 협력자를 채우기 위한 것이다.
            new AiCourseMetrics(new SimpleMeterRegistry()),
            OpenAiLlmClient.buildChatModel(llmProperties.openai().baseUrl(), openAiKey,
                llmProperties.timeoutMs()));

        KakaoLocalClient kakaoLocalClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));

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

        // 산출물 이름에 측정 축을 박는다. 네 조합을 순차로 돌리므로 파일명만 보고 어느 판인지
        // 알 수 있어야 하고, results/ 는 .gitignore 대상이라 이름이 유일한 라벨이다.
        String runId = "openai-%s-%s%s".formatted(modelKey, schemaMode,
            reasoningEffort.isBlank() ? "" : "-re" + reasoningEffort);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String runTag = runId + "-" + timestamp;
        Path rawDir = RESULTS_DIR.resolve("raw-" + runTag);
        Files.createDirectories(rawDir);

        System.out.printf("%n=== AI 코스 생성 환각률 baseline 측정 시작 ===%n");
        System.out.printf("모델 %s (%s) / 출력 강제 %s / 추론 강도 %s / 의미 재시도 %d회%n",
            model, modelKey, schemaMode,
            reasoningEffort.isBlank() ? "(모델 기본값)" : reasoningEffort,
            llmProperties.retry().semanticAttempts());
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

            parser.reset();
            LegacyGeminiCourseDto dto;
            try {
                dto = llmClient.generate(new LlmCall<>(AGENT_NAME, null,
                    LegacyGeminiPrompt.buildPrompt(spec.region().name(), TRIP_DAYS,
                        spec.keywordSet().keywords()),
                    LegacyGeminiCourseDto.class, responseSchema));
                consecutiveCallFailures = 0;
            } catch (LlmTruncatedResponseException e) {
                // 구조화 출력으로 막히지 않는 실패다. finishReason 과 수신 길이를 남겨야
                // "출력 상한에 닿았나, 스트림이 끊겼나"를 사후에 가를 수 있다.
                System.out.printf("응답 절단: finishReason=%s, %d바이트%n",
                    e.getFinishReason(), length(e.getPartialText()));
                dumpRaw(rawDir, spec, e.getPartialText());
                outcomes.add(failedRequest(spec, Outcome.TRUNCATED, e.getMessage(),
                    e.getFinishReason(), length(e.getPartialText())));
                consecutiveCallFailures = 0;
                sleep(delayMs);
                continue;
            } catch (LlmParseException e) {
                // 구조화 출력이 없애야 하는 바로 그 실패다.
                System.out.printf("JSON 파싱 실패: %s%n", oneLine(e.getMessage()));
                dumpRaw(rawDir, spec, e.getRawText());
                outcomes.add(failedRequest(spec, Outcome.PARSE_FAILED, e.getMessage(),
                    "", length(e.getRawText())));
                consecutiveCallFailures = 0;
                sleep(delayMs);
                continue;
            } catch (LlmTransportException e) {
                System.out.printf("OpenAI 호출 실패(%d회 시도): %s%n",
                    e.getAttempts(), oneLine(e.getMessage()));
                outcomes.add(failedRequest(spec, Outcome.CALL_FAILED, e.getMessage(), "", 0));
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

            // 성공한 응답의 원본도 남긴다 — Gemini 측정 때 이게 없어 파싱 실패의 원인을 확정하지
            // 못했고, 정상 응답의 크기 분포가 있어야 "386바이트에서 잘렸다"가 이상하다는 판단을
            // 할 수 있다.
            dumpRaw(rawDir, spec, parser.lastRawText());

            List<PlaceRow> rowsForRequest = groundPlaces(spec, dto, kakaoLocalClient);
            placeRows.addAll(rowsForRequest);
            outcomes.add(summarize(spec, dto, rowsForRequest.size(),
                length(parser.lastRawText())));

            System.out.printf("장소 %d개 검증 완료%n", rowsForRequest.size());
            sleep(delayMs);
        }

        writePlaceCsv(runTag, placeRows);
        writeRequestCsv(runTag, outcomes);
        writeManualVerificationCsv(runTag, placeRows);

        report(placeRows, outcomes, runTag);

        assertThat(placeRows).as("측정된 장소 표본이 하나도 없다 — API 키나 네트워크를 확인하라").isNotEmpty();
    }

    // ── 파이프라인 단계 재현 ────────────────────────────────────────────────────

    private List<PlaceRow> groundPlaces(RequestSpec spec, LegacyGeminiCourseDto dto,
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
                rows.add(groundOnePlace(spec, daySchedule.day(), placeIndex++,
                    place.placeName(), kakaoLocalClient));
            }
        }
        return rows;
    }

    /** 이름만 받는다 — 재채점 모드가 {@code PlaceDto} 없이(CSV의 문자열로) 재사용하기 위해서다. */
    private PlaceRow groundOnePlace(RequestSpec spec, int day, int placeIndex, String placeName,
        KakaoLocalClient kakaoLocalClient) {

        String aiPlaceName = placeName == null ? "" : placeName;

        // 실제 호출부(MyCourseServiceImpl:564-565)와 같이 placeLocation 자리에 지역명을 넘긴다.
        // 키워드 조립("{지역} {장소}")과 후보 수(5)는 lookupBestPlace 안에 있다 — 복제하지 않는다.
        String placeLocation = spec.region().name();

        PlaceLookup lookup;
        try {
            lookup = kakaoLocalClient.lookupBestPlace(aiPlaceName, placeLocation);
        } catch (RuntimeException e) {
            // lookupBestPlace 는 실패도 값으로 돌려주므로 여기까지 오는 일은 없어야 한다.
            // 그래도 측정을 멈추지 않기 위해 오류 자체를 하나의 결과로 기록한다.
            return placeRow(spec, day, placeIndex, aiPlaceName, placeLocation,
                -2, BAND_KAKAO_ERROR, null, "");
        }

        return switch (lookup) {
            case PlaceLookup.Failed failed -> placeRow(spec, day, placeIndex, aiPlaceName,
                placeLocation, -2, BAND_KAKAO_ERROR, null, failed.cause().name());

            case PlaceLookup.NoResult ignored -> placeRow(spec, day, placeIndex, aiPlaceName,
                placeLocation, -1, BAND_NO_RESULT, null, "");

            // 검색은 됐는데 이름 게이트를 통과한 후보가 0건이다. 무엇에 걸렸는지 남긴다.
            case PlaceLookup.NameMismatch mismatch -> placeRow(spec, day, placeIndex, aiPlaceName,
                placeLocation, -3, BAND_NAME_MISMATCH, null,
                nullToEmpty(mismatch.bestCandidateName()));

            case PlaceLookup.Found found -> {
                Document doc = found.document();
                int score = PlaceMatchScorer.score(doc, aiPlaceName, placeLocation);
                yield placeRow(spec, day, placeIndex, aiPlaceName, placeLocation,
                    score, bandOf(score), doc, "");
            }
        };
    }

    /** 결과 네 갈래가 같은 열을 채우므로 조립을 한 곳에 모은다. */
    private PlaceRow placeRow(RequestSpec spec, int day, int placeIndex, String aiPlaceName,
        String placeLocation, int bestScore, String scoreBand, Document doc,
        String rejectedCandidateName) {

        return new PlaceRow(spec.requestId(), placeLocation, spec.region().tier(),
            spec.keywordSet().id(), day, placeIndex, aiPlaceName,
            bestScore, scoreBand,
            doc == null ? "" : nullToEmpty(doc.place_name()),
            doc == null ? "" : nullToEmpty(doc.category_name()),
            // 도로명 우선·지번 폴백도 프로덕션 순수 함수를 쓴다.
            doc == null ? "" : PlaceMatchScorer.bestAddressOf(doc),
            doc == null ? "" : nullToEmpty(doc.place_url()),
            rejectedCandidateName);
    }

    /**
     * {@code Found}의 점수를 구간으로 나눈다. <b>게이트를 통과한 후보에만 적용된다</b> —
     * 무결과·이름 불일치·API 오류는 점수 이전에 갈리므로 여기로 오지 않는다.
     *
     * <p>게이트({@code PlaceNameNormalizer.similar})는 정규화 후 비교하는데 점수의 이름 가점은
     * 정규화하지 않으므로, <b>게이트를 통과해도 낮은 구간이 남는다</b>("동궁과 월지" → "동궁과월지"는
     * 통과하지만 +5는 못 받는다). 그래서 이 구간은 정확도 신호가 아니라 <b>층화 추출과 순위</b>의
     * 축이다.
     */
    private static String bandOf(int score) {
        if (score == 0) return BAND_S0;
        if (score <= 4) return BAND_S1_4;
        if (score <= 7) return BAND_S5_7;
        return BAND_S8_10;
    }

    // ── 부가 지표 집계 ─────────────────────────────────────────────────────────

    private RequestOutcome summarize(RequestSpec spec, LegacyGeminiCourseDto dto, int totalPlaces,
        int responseBytes) {
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
            spec.keywordSet().id(), Outcome.OK, "", "stop", responseBytes,
            days.size(), String.join("/", perDay), totalPlaces, duplicates, startTimeViolations);
    }

    private RequestOutcome failedRequest(RequestSpec spec, Outcome outcome, String detail,
        String finishReason, int responseBytes) {
        return new RequestOutcome(spec.requestId(), spec.region().name(), spec.region().tier(),
            spec.keywordSet().id(), outcome, oneLine(detail), nullToEmpty(finishReason),
            responseBytes, 0, "", 0, 0, 0);
    }

    // ── 2단계 측정 배선 ────────────────────────────────────────────────────────

    /**
     * 측정 전용 {@code AiLlmProperties}.
     *
     * <p>Spring 컨텍스트를 띄우지 않으므로 {@code application.yml}이 아니라 여기서 조립한다.
     * <b>프로덕션 값과 일부러 다르게 두는 항목이 둘</b>이고, 둘 다 비교 가능성 때문이다.
     * <ul>
     *   <li>{@code semantic-attempts: 1} — 재시도가 깨진 응답을 고쳐버리면 파싱 실패율이
     *       "재시도 후" 값이 되는데, Gemini 측정에는 그런 보정이 없었다</li>
     *   <li>{@code temperature 0.3} / {@code max-output-tokens 4096} — 프롬프트 원문이 쓰던
     *       구 {@code GeminiService.getGenerationConfig()} 값 그대로다. 모델만 바뀌어야 한다</li>
     * </ul>
     * 전송 재시도는 넉넉히 둔다 — 429로 측정이 중단되면 그날 배치를 다시 돌려야 한다.
     */
    private static AiLlmProperties baselineProperties(String apiKey, String model,
        String reasoningEffort) {
        return new AiLlmProperties(
            "openai",
            60_000,
            1,
            new AiLlmProperties.Retry(4, 1, 2.0, 30.0, 0.3),
            Map.of(AGENT_NAME, new AiLlmProperties.Agent(
                model, TEMPERATURE, MAX_OUTPUT_TOKENS, reasoningEffort)),
            new AiLlmProperties.OpenAi(apiKey, "https://api.openai.com"));
    }

    /**
     * 성공한 응답의 원문을 가로채기 위한 파서.
     *
     * <p>포트는 역직렬화된 DTO만 돌려주므로 성공 시 원문이 남지 않는다. 그런데 <b>정상 응답의 크기
     * 분포가 있어야</b> 절단된 응답이 이상한지 판단할 수 있다 — Gemini 재분석에서 "정상은
     * 1,400~1,660바이트인데 이 건은 386바이트"라는 비교가 원인 규명의 결정적 근거였다.
     * 실패 경로의 원문은 예외가 이미 들고 온다.
     */
    private static final class CapturingParser extends LlmResponseParser {

        private volatile String lastRawText;

        private CapturingParser(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public <T> T parse(String agentName, String rawText, Class<T> responseType) {
            this.lastRawText = rawText;
            return super.parse(agentName, rawText, responseType);
        }

        private void reset() {
            this.lastRawText = null;
        }

        private String lastRawText() {
            return lastRawText;
        }
    }

    private static String loadSchema() throws IOException {
        try (var in = AiHallucinationBaselineTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IOException("스키마 리소스를 찾을 수 없다: " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 성공·실패를 가리지 않고 응답 원문을 남긴다. 원문 없이는 실패 원인을 확정할 수 없다. */
    private static void dumpRaw(Path rawDir, RequestSpec spec, String rawText) throws IOException {
        Files.writeString(rawDir.resolve(String.format("response-%02d-%s-%s.json",
                spec.requestId(), spec.region().name(), spec.keywordSet().id())),
            nullToEmpty(rawText), StandardCharsets.UTF_8);
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    /** CSV·콘솔에 넣기 전에 개행을 없애고 길이를 제한한다 — 예외 메시지가 여러 줄이라 행이 깨진다. */
    private static String oneLine(String s) {
        String flat = nullToEmpty(s).replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
        return flat.length() <= MAX_ERROR_LENGTH ? flat : flat.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    // ── 산출물 ────────────────────────────────────────────────────────────────

    private void writePlaceCsv(String runTag, List<PlaceRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("requestId,location,regionTier,keywordSet,day,placeIndex,aiPlaceName,")
            .append("bestScore,scoreBand,matchedPlaceName,matchedCategory,")
            .append("matchedAddress,matchedPlaceUrl,rejectedCandidateName\n");

        for (PlaceRow r : rows) {
            sb.append(r.requestId()).append(',')
                .append(csv(r.location())).append(',')
                .append(r.tier()).append(',')
                .append(csv(r.keywordSetId())).append(',')
                .append(r.day()).append(',')
                .append(r.placeIndex()).append(',')
                .append(csv(r.aiPlaceName())).append(',')
                .append(r.bestScore()).append(',')
                .append(r.scoreBand()).append(',')
                .append(csv(r.matchedPlaceName())).append(',')
                .append(csv(r.matchedCategory())).append(',')
                .append(csv(r.matchedAddress())).append(',')
                .append(csv(r.matchedPlaceUrl())).append(',')
                .append(csv(r.rejectedCandidateName())).append('\n');
        }
        writeUtf8Bom(RESULTS_DIR.resolve("hallucination-baseline-" + runTag + ".csv"), sb.toString());
    }

    private void writeRequestCsv(String runTag, List<RequestOutcome> outcomes) throws IOException {
        StringBuilder sb = new StringBuilder();
        // outcome 을 4값으로 나눠 남긴다 — 예전의 parseSuccess 한 칸으로는 "파싱 실패율의 분모가
        // 전체 요청인지 호출 성공분인지"를 사후에 복원할 수 없었다.
        sb.append("requestId,location,regionTier,keywordSet,outcome,failureDetail,")
            .append("finishReason,responseBytes,")
            .append("dayCount,placesPerDay,totalPlaces,duplicatePlaceCount,startTimeViolationCount\n");

        for (RequestOutcome o : outcomes) {
            sb.append(o.requestId()).append(',')
                .append(csv(o.location())).append(',')
                .append(o.tier()).append(',')
                .append(csv(o.keywordSetId())).append(',')
                .append(o.outcome()).append(',')
                .append(csv(o.failureDetail())).append(',')
                .append(csv(o.finishReason())).append(',')
                .append(o.responseBytes()).append(',')
                .append(o.dayCount()).append(',')
                .append(csv(o.placesPerDay())).append(',')
                .append(o.totalPlaces()).append(',')
                .append(o.duplicatePlaceCount()).append(',')
                .append(o.startTimeViolationCount()).append('\n');
        }
        writeUtf8Bom(RESULTS_DIR.resolve("hallucination-baseline-" + runTag + "-requests.csv"),
            sb.toString());
    }

    /**
     * 점수 구간별 층화 추출 워크시트. 사람이 matchedPlaceUrl 을 열어 verdict 를 채운다.
     *
     * <p>층화(무작위가 아니라 구간별 균등)로 뽑는 이유: "몇 점부터 실제로 신뢰할 수 있는가"를
     * 데이터로 확인해야 임계값(계획서의 min-kakao-score: 5)이 적절한지까지 판정할 수 있다.
     * 대신 전체 환각률을 추정할 때는 구간별 비율을 전체 비중으로 가중해야 한다.
     */
    private void writeManualVerificationCsv(String runTag, List<PlaceRow> rows) throws IOException {
        Map<String, List<PlaceRow>> byBand = new LinkedHashMap<>();
        for (String band : ALL_BANDS) {
            byBand.put(band, new ArrayList<>());
        }
        for (PlaceRow r : rows) {
            byBand.get(r.scoreBand()).add(r);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# verdict 를 채워주세요: CORRECT | FABRICATED | WRONG_MATCH | UNVERIFIABLE\n")
            .append("#   CORRECT       AI 원안이 실존하고 카카오 매칭도 그 장소가 맞음\n")
            .append("#   FABRICATED    AI 원안이 그 지역에 실존하지 않음 — 매칭 성공 여부는 보지 않는다\n")
            .append("#                 (매칭까지 통과한 '세탁'인지는 scoreBand 로 사후 분해된다)\n")
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
        writeUtf8Bom(RESULTS_DIR.resolve("manual-verification-" + runTag + ".csv"), sb.toString());
    }

    private void report(List<PlaceRow> rows, List<RequestOutcome> outcomes, String runTag) {
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
        long nameMismatch = rows.stream()
            .filter(r -> BAND_NAME_MISMATCH.equals(r.scoreBand())).count();
        System.out.printf("%n=== 자동 지표 (판정자 없이 코드가 계산한다) ===%n");
        System.out.printf("  자동 프록시    NO_RESULT / 전체     = %d / %d = %.1f%%%n",
            suspect, rows.size(), pct(suspect, rows.size()));
        System.out.printf("  이름 불일치율  NAME_MISMATCH / 전체 = %d / %d = %.1f%%%n",
            nameMismatch, rows.size(), pct(nameMismatch, rows.size()));
        System.out.printf("  장소 미확보율  둘의 합              = %d / %d = %.1f%%"
                + "   ← 운영 ai.grounding.match 와 같은 축%n",
            suspect + nameMismatch, rows.size(), pct(suspect + nameMismatch, rows.size()));
        System.out.printf("  ※ KAKAO_ERROR 는 분자에서 빠지고 분모엔 남는다 — 장애 시 값이 희석된다.%n");
        System.out.printf("     지어낸 이름이 게이트를 통과해 실린 '세탁'은 수동 검증으로만 잡는다.%n");

        System.out.printf("%n=== 지역 tier별 자동 프록시 (NO_RESULT 기준) ===%n");
        for (RegionTier tier : RegionTier.values()) {
            List<PlaceRow> tierRows = rows.stream().filter(r -> r.tier() == tier).toList();
            long tierSuspect = tierRows.stream().filter(r -> SUSPECT_BANDS.contains(r.scoreBand())).count();
            System.out.printf("  %-7s %4d / %4d = %.1f%%%n",
                tier, tierSuspect, tierRows.size(), pct(tierSuspect, tierRows.size()));
        }

        // 재채점 모드는 요청 단위 결과가 없다(LLM을 부르지 않았으므로). 장소 지표만 낸다.
        if (outcomes.isEmpty()) {
            return;
        }

        System.out.printf("%n=== 요청 결말 (요청 %d건) ===%n", outcomes.size());

        // 예전에는 이 넷이 한 칸에 뭉쳐 "JSON 파싱/호출 실패"로 나왔고, 그래서 28.6%가 무엇에 대한
        // 비율인지 사후에 복원할 수 없었다. 분자와 분모를 둘 다 명시한다.
        Map<Outcome, Long> byOutcome = new LinkedHashMap<>();
        for (Outcome value : Outcome.values()) {
            byOutcome.put(value, outcomes.stream().filter(o -> o.outcome() == value).count());
        }
        for (Map.Entry<Outcome, Long> e : byOutcome.entrySet()) {
            System.out.printf("  %-14s %3d건 (%5.1f%%)%n",
                e.getKey(), e.getValue(), pct(e.getValue(), outcomes.size()));
        }

        long callSucceeded = outcomes.size() - byOutcome.get(Outcome.CALL_FAILED);
        long unusable = byOutcome.get(Outcome.TRUNCATED) + byOutcome.get(Outcome.PARSE_FAILED);

        System.out.printf("%n=== JSON 실패율 (분모를 반드시 명시한다) ===%n");
        System.out.printf("  전체 요청 기준        (절단+파싱실패) / 전체        = %d / %d = %.1f%%%n",
            unusable, outcomes.size(), pct(unusable, outcomes.size()));
        System.out.printf("  호출 성공분 기준      (절단+파싱실패) / 호출성공    = %d / %d = %.1f%%%n",
            unusable, callSucceeded, pct(unusable, callSucceeded));
        System.out.printf("  ※ Gemini 값 16.7%%(5/30)는 '전체 요청 기준'이다. 28.6%%는 호출 성공분(4/14) 기준이었다.%n");
        System.out.printf("  ※ 절단은 구조화 출력으로 막히지 않는다 — 두 값을 갈라 봐야 그게 보인다.%n");

        List<RequestOutcome> truncated = outcomes.stream()
            .filter(o -> o.outcome() == Outcome.TRUNCATED).toList();
        if (!truncated.isEmpty()) {
            System.out.printf("%n=== 절단 상세 (출력 상한 %d 토큰) ===%n", MAX_OUTPUT_TOKENS);
            for (RequestOutcome o : truncated) {
                System.out.printf("  #%-2d %-4s finishReason=%-14s %,d바이트 수신%n",
                    o.requestId(), o.location(), o.finishReason(), o.responseBytes());
            }
            System.out.printf("  ※ 정상 응답 크기와 비교하라. 훨씬 작으면 상한이 아니라 스트림 중단이다.%n");
        }

        java.util.IntSummaryStatistics okBytes = outcomes.stream()
            .filter(o -> o.outcome() == Outcome.OK)
            .mapToInt(RequestOutcome::responseBytes).summaryStatistics();
        if (okBytes.getCount() > 0) {
            System.out.printf("%n  정상 응답 크기 %,d ~ %,d바이트 (평균 %,.0f)%n",
                okBytes.getMin(), okBytes.getMax(), okBytes.getAverage());
        }

        System.out.printf("%n=== 부가 지표 ===%n");
        long dayMismatch = outcomes.stream()
            .filter(o -> o.outcome() == Outcome.OK && o.dayCount() != TRIP_DAYS).count();
        int duplicates = outcomes.stream().mapToInt(RequestOutcome::duplicatePlaceCount).sum();
        int violations = outcomes.stream().mapToInt(RequestOutcome::startTimeViolationCount).sum();

        System.out.printf("  일수 불일치(≠%d)         %d / %d (%.1f%%)%n",
            TRIP_DAYS, dayMismatch, outcomes.size(), pct(dayMismatch, outcomes.size()));

        // 프롬프트는 "각 day마다 최소 3개, 최대 6개"를 요구한다 — 실제로 지켜지는지 센다
        int totalDays = 0;
        int outOfRangeDays = 0;
        for (RequestOutcome o : outcomes) {
            if (o.outcome() != Outcome.OK || o.placesPerDay().isBlank()) {
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
        System.out.printf("  LLM 원본 응답   results/raw-%s/  ← 성공·실패 모두 남긴다%n", runTag);
        System.out.printf("  장소별 원본     results/hallucination-baseline-%s.csv%n", runTag);
        System.out.printf("  요청별 지표     results/hallucination-baseline-%s-requests.csv%n", runTag);
        System.out.printf("  수동 검증 대상  results/manual-verification-%s.csv  ← verdict 를 채워주세요%n",
            runTag);
        System.out.printf("%n  수동 검증 후 지표 산출:%n");
        System.out.printf("    지어냄률    = Σ_구간 (구간별 FABRICATED 비율 × 구간별 전체 비중)"
            + "   ← 1차 지표%n");
        System.out.printf("    세탁 통과율 = 위 식을 Found 구간(S0~S8_10)에만 적용"
            + "                     ← 지어냈는데 통과한 것%n");
    }

    // ── 재채점 모드 ───────────────────────────────────────────────────────────

    /**
     * 기존 산출물의 장소 목록을 <b>현행 검증 기준으로</b> 다시 채점한다. LLM은 부르지 않는다 —
     * 장소명 389개가 이미 CSV에 있으므로 카카오 검증만 다시 돌리면 된다.
     *
     * <p>별도 스크립트로 만들지 않고 하네스 안에 두는 이유: 검증 로직이 두 벌이 되는 순간,
     * 재채점과 본측정이 서로 다른 판정을 내리는 drift가 생긴다. {@link #groundOnePlace}를
     * 그대로 재사용해 경로를 하나로 유지한다.
     *
     * <pre>
     * BASELINE_RESCORE_FROM=results/merged3-places.csv \
     *   ./gradlew benchmarkTest --tests '*AiHallucinationBaselineTest*' --rerun
     * </pre>
     */
    @Test
    @DisplayName("기존 산출물의 장소 목록을 현행 검증 기준으로 재채점한다 (LLM 호출 없음)")
    void rescoreFromCsv() throws Exception {
        String rescoreFrom = text("baseline.rescoreFrom", "BASELINE_RESCORE_FROM", "");
        assumeTrue(!rescoreFrom.isBlank(), "BASELINE_RESCORE_FROM 지정 시에만 실행된다");

        Map<String, String> env = loadDotEnv(Path.of(".env"));
        String kakaoKey = resolve(env, "KAKAO_API_KEY");
        assumeTrue(kakaoKey != null, "재채점은 KAKAO_API_KEY 가 필요하다 (.env 또는 환경변수)");

        Path source = Path.of(rescoreFrom);
        assertThat(source).as("재채점 원본 CSV").exists();

        KakaoLocalClient kakaoLocalClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));

        Map<String, RegionSpec> regionByName = new HashMap<>();
        for (RegionSpec region : REGIONS) {
            regionByName.put(region.name(), region);
        }
        Map<String, KeywordSetSpec> keywordSetById = new HashMap<>();
        for (KeywordSetSpec keywordSet : KEYWORD_SETS) {
            keywordSetById.put(keywordSet.id(), keywordSet);
        }

        List<List<String>> csv = readCsv(source);
        Map<String, Integer> col = new HashMap<>();
        List<String> header = csv.get(0);
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i), i);
        }
        // 구 스키마(kakaoTotalCount 포함)와 신 스키마를 모두 받기 위해 열은 이름으로 찾는다.
        for (String required : List.of("requestId", "location", "keywordSet",
            "day", "placeIndex", "aiPlaceName")) {
            assertThat(col).as("원본 CSV에 %s 열이 있어야 한다", required).containsKey(required);
        }

        long delayMs = setting("hallucination.delayMs", "HALLUCINATION_DELAY_MS", 200L);
        System.out.printf("%n=== 재채점 시작: %s (%d행, 카카오 호출 간 지연 %dms) ===%n",
            source, csv.size() - 1, delayMs);

        List<PlaceRow> placeRows = new ArrayList<>();
        int consecutiveErrors = 0;
        for (int i = 1; i < csv.size(); i++) {
            List<String> row = csv.get(i);
            String location = row.get(col.get("location"));
            RegionSpec region = regionByName.get(location);
            assertThat(region).as("입력 세트에 없는 지역: %s (행 %d)", location, i + 1).isNotNull();

            RequestSpec spec = new RequestSpec(
                Integer.parseInt(row.get(col.get("requestId"))),
                region,
                keywordSetById.get(row.get(col.get("keywordSet"))));

            PlaceRow scored = groundOnePlace(spec,
                Integer.parseInt(row.get(col.get("day"))),
                Integer.parseInt(row.get(col.get("placeIndex"))),
                row.get(col.get("aiPlaceName")),
                kakaoLocalClient);
            placeRows.add(scored);

            // 키가 죽었거나 쿼터가 끝났으면 389건을 헛돌리지 말고 바로 멈춘다.
            consecutiveErrors = BAND_KAKAO_ERROR.equals(scored.scoreBand())
                ? consecutiveErrors + 1 : 0;
            assertThat(consecutiveErrors)
                .as("카카오 호출이 연속 실패한다 — 키·쿼터를 확인하라 (행 %d)", i + 1)
                .isLessThan(5);

            if (i % 50 == 0) {
                System.out.printf("  ... %d / %d%n", i, csv.size() - 1);
            }
            Thread.sleep(delayMs);
        }

        String runTag = "rescore-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        writePlaceCsv(runTag, placeRows);
        writeManualVerificationCsv(runTag, placeRows);
        report(placeRows, List.of(), runTag);
    }

    /**
     * 큰따옴표 이스케이프를 처리하는 최소 CSV 파서. {@code matchedCategory}에 쉼표가 들어
     * 있어("여행 > 관광,명소") 단순 {@code split(",")}으로는 열이 밀린다. BOM은 걷어낸다.
     */
    private static List<List<String>> readCsv(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString());
                field.setLength(0);
                if (current.size() > 1 || !current.get(0).isBlank()) {
                    rows.add(current);
                }
                current = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private List<RequestSpec> buildInputSet() {
        return BaselineInputSet.buildInputSet();
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

    /** {@link #setting}의 문자열 버전. 측정 축(모델·출력 강제 방식) 선택에 쓴다. */
    private static String text(String systemProperty, String envVar, String defaultValue) {
        String raw = System.getProperty(systemProperty);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(envVar);
        }
        return (raw == null || raw.isBlank()) ? defaultValue : raw.trim();
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
        // 출력 디렉터리를 먼저 만든다. results/ 는 .gitignore 대상이라 clone 직후나 정리 후에는
        // 존재하지 않는데, 없으면 여기서 NoSuchFileException 이 나고 그때는 이미 LLM 호출을
        // 다 끝낸 뒤라 측정 비용만 날린다. raw-* 쪽은 이미 createDirectories 를 부르지만
        // CSV 세 종은 이 메서드가 유일한 통로라 여기 한 곳이면 전부 덮인다.
        Files.createDirectories(path.getParent());

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
