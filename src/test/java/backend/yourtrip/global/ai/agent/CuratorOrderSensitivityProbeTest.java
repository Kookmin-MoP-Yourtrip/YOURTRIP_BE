package backend.yourtrip.global.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.LlmResponseParser;
import backend.yourtrip.global.ai.LlmRetryExecutor;
import backend.yourtrip.global.ai.candidate.AreaGeocoder;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateRetrievalStage;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.NaverLocalSeedSource;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.candidate.PlaceNameNormalizer;
import backend.yourtrip.global.ai.candidate.TourApiSource;
import backend.yourtrip.global.ai.config.AiLlmProperties;
import backend.yourtrip.global.ai.openai.OpenAiLlmClient;
import backend.yourtrip.global.ai.pipeline.CuratedDay;
import backend.yourtrip.global.ai.pipeline.CuratedPlace;
import backend.yourtrip.global.ai.pipeline.CuratedSlot;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.prompt.PromptLoader;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.config.NaverConfig;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.config.TourApiConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>후보 목록의 순서가 Curator 의 선택을 얼마나 좌우하는가</b> (이슈 #113).
 *
 * <h2>왜 이걸 재야 하는가</h2>
 * 설계는 "목록의 순서 자체가 신호다 — LLM 은 앞쪽을 더 자주 고르는 위치 편향이 있고, 그걸 억누르지
 * 않고 이용한다"고 못 박았고, {@code CandidateOrdering} 의 사전식 정렬과 {@code CandidateListRenderer}
 * 의 "여기서 다시 정렬하지 않는다"가 전부 그 전제 위에 있다. <b>그런데 그 전제는 이 저장소에서
 * 확인된 적이 없다.</b> 실제 선택 228 건에서 index 0~2 가 51% 였지만, 목록을 <b>좋은 것부터 정렬해
 * 넣었으므로</b> "편향이 있다"와 "정렬이 좋다"를 가릴 수 없다 — 두 설명이 같은 데이터를 낳는다.
 *
 * <p><b>순서만 바꿔 같은 질문을 두 번 던지면 그 교락이 풀린다.</b> 목록의 내용은 그대로 두고 순서만
 * 섞었을 때 선택이 그대로면 편향은 약한 것이고, 크게 달라지면 순서가 실제로 결과를 정하고 있다는
 * 뜻이다.
 *
 * <h2>판정 기준 — 결과를 보기 전에 못 박는다</h2>
 * <ol>
 *   <li><b>1순위 일치율</b>이 높으면(대략 80% 이상) 원인은 순서가 아니라 <b>표식</b>이다.
 *       {@code CandidateOrdering} 을 건드리지 않고, `[seed·광역]` 수정으로 이슈를 닫는다</li>
 *   <li>일치율이 낮으면(대략 50% 이하) 순서가 결과를 정하고 있다는 뜻이다. 그러면
 *       <b>`location` 후보를 별도 그룹으로 내리고 거리순으로 세우는 정렬 수정</b>이 필요하고,
 *       설계 문서의 "목록의 순서 자체가 신호다"도 근거를 얻는다</li>
 *   <li>가운데면 <b>판정하지 않는다.</b> 표본이 작아 노이즈와 신호를 가를 수 없다는 뜻이고,
 *       그 사실을 적는 것이 억지 결론보다 낫다</li>
 * </ol>
 *
 * <p><b>보조 지표로 인덱스 분포를 함께 본다.</b> 섞은 목록에서도 여전히 앞쪽이 몰린다면 그것이
 * 위치 편향의 직접 증거다 — 섞은 뒤에는 "앞쪽이 좋다"가 더 이상 성립하지 않기 때문이다.
 *
 * <h2>규모와 비용</h2>
 * <b>Planner 를 부르지 않는다.</b> 관측된 {@code (location, area, anchor)} 삼중항을 픽스처로 박아
 * ({@code AreaQueryStrategyProbeTest} 가 세운 방식) LLM 예산을 전부 Curator 에 쓴다.
 * <b>3권역 × 2회(원본 순서·섞은 순서) = LLM 호출 6회</b>이고, 장소 API 는 후보 공급 1회분이다.
 *
 * <p><b>판정용이지 회귀 테스트가 아니다.</b> 단언은 "이 프로브가 성립하려면 반드시 참이어야 하는
 * 것"에만 걸고, 나머지는 콘솔로 덤프해 사람이 읽는다.
 *
 * <pre>{@code
 * ./gradlew benchmarkTest --tests '*CuratorOrderSensitivityProbeTest*' --rerun
 * }</pre>
 */
@Tag("benchmark")
@DisplayName("후보 목록 순서 민감도 프로브 (이슈 #113)")
class CuratorOrderSensitivityProbeTest {

    /**
     * 관측된 권역. <b>{@code location} 폴백이 실제로 걸렸던 조합을 반드시 포함시킨다</b> — 이슈가
     * 묻는 것이 바로 그 슬롯이라, 안 걸리면 산출물 3이 빈칸이 된다.
     */
    private static final List<Case> OBSERVED = List.of(
        new Case("공주", "송산리고분군·박물관 일대", "무령왕릉"),
        new Case("순천", "원도심·문화의거리 일대", "순천부읍성"),
        new Case("경주", "황리단길·대릉원 일대", "대릉원"));

    /** 관광 계열 하나와 시더 전용 슬롯 둘 — 얇아지는 쪽이 뒤의 둘이다. */
    private static final List<SlotType> PROBE_SLOTS =
        List.of(SlotType.ATTRACTION, SlotType.MEAL, SlotType.CAFE);

    private static final List<KeywordType> KEYWORDS =
        List.of(KeywordType.WALK, KeywordType.COUPLE, KeywordType.HEALING, KeywordType.SENSIBILITY);

    /**
     * 셔플 시드. <b>고정한다</b> — 매 실행마다 다른 순서로 섞으면 전후 비교가 성립하지 않고,
     * "이번 판정이 어떤 목록에서 나왔는지"를 재현할 수 없다.
     */
    private static final long SHUFFLE_SEED = 113L;

    @Test
    @DisplayName("같은 후보 목록을 순서만 섞어 두 번 물어 선택이 달라지는지 본다")
    void probeOrderSensitivity() {
        String openAiKey = env("OPENAI_API_KEY");
        String naverId = env("NAVER_CLIENT_ID");
        String naverSecret = env("NAVER_CLIENT_SECRET");
        String tourKey = env("TOUR_API_KEY");
        String kakaoKey = env("KAKAO_API_KEY");
        assumeTrue(openAiKey != null && naverId != null && naverSecret != null && tourKey != null
            && kakaoKey != null, "OpenAI·네이버·TourAPI·카카오 키가 모두 있어야 실측할 수 있다");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiCourseMetrics metrics = new AiCourseMetrics(registry);
        CuratorAgent curator = curatorAgent(openAiKey, metrics);
        CandidateRetrievalStage retrieval =
            retrievalStage(naverId, naverSecret, tourKey, kakaoKey, metrics);

        // ① 후보 공급 — LLM 은 쓰지 않는다. 권역마다 location 이 다르므로 day 를 묶지 않고
        //    한 권역씩 부른 뒤, 그 결과를 day 1..N 짜리 plan 하나로 다시 엮는다.
        List<PlannerDayPlan> days = new ArrayList<>(OBSERVED.size());
        List<CandidateSlot> slots = new ArrayList<>();
        for (int index = 0; index < OBSERVED.size(); index++) {
            Case one = OBSERVED.get(index);
            int dayNumber = index + 1;
            PlannerDayPlan single = PlannerDayPlan.of(1, one.area(), one.anchor(), PROBE_SLOTS);
            CandidatePool onePool = retrieval.retrieve(one.location(),
                new PlannerPlan("probe", "권역 안에 머무는 하루", List.of(single)), KEYWORDS,
                CourseDeadline.unbounded());

            days.add(PlannerDayPlan.of(dayNumber, one.area(), one.anchor(), PROBE_SLOTS));
            onePool.slots().forEach(slot ->
                slots.add(new CandidateSlot(dayNumber, slot.slotType(), slot.candidates())));
        }
        PlannerPlan plan = new PlannerPlan("순서 민감도 프로브", "권역 안에 머무는 하루", days);
        CandidatePool ordered = new CandidatePool(slots);
        CandidatePool shuffled = shuffle(ordered);

        printPool(ordered);

        // ② Curator 를 두 번 — 목록의 내용은 같고 순서만 다르다.
        List<CuratedDay> fromOrdered =
            curator.curate(plan, ordered, KEYWORDS, CourseDeadline.unbounded());
        List<CuratedDay> fromShuffled =
            curator.curate(plan, shuffled, KEYWORDS, CourseDeadline.unbounded());

        assertThat(fromOrdered).hasSize(days.size());
        assertThat(fromShuffled).hasSize(days.size());

        printAgreement(plan, ordered, shuffled, fromOrdered, fromShuffled);
        printIndexDistribution("원본 순서", fromOrdered);
        printIndexDistribution("섞은 순서", fromShuffled);
        printCityWideSlots(plan, ordered, fromOrdered);
    }

    // ── 셔플 — 운영 코드를 건드리지 않는다 ────────────────────────────────────

    /**
     * 슬롯마다 후보 순서만 뒤집어 섞은 풀. <b>내용은 한 건도 바꾸지 않는다</b> — 그래야 달라진
     * 결과를 순서 하나에 귀속시킬 수 있다.
     */
    private static CandidatePool shuffle(CandidatePool pool) {
        Random random = new Random(SHUFFLE_SEED);
        List<CandidateSlot> shuffled = new ArrayList<>(pool.slots().size());
        for (CandidateSlot slot : pool.slots()) {
            List<PlaceCandidate> candidates = new ArrayList<>(slot.candidates());
            Collections.shuffle(candidates, random);
            shuffled.add(new CandidateSlot(slot.day(), slot.slotType(), candidates));
        }
        return new CandidatePool(shuffled);
    }

    // ── 출력 — 판정은 사람이 한다 ─────────────────────────────────────────────

    private static void printPool(CandidatePool pool) {
        System.out.printf("%n[후보 풀] day·슬롯별 확보 건수 (광역 = location 폴백이 걸린 건수)%n");
        pool.slots().forEach(slot -> {
            long cityWide = slot.candidates().stream()
                .filter(PlaceCandidate::fromCityWideQuery).count();
            System.out.printf("  day %d %-11s %2d건 · 광역 %d건%n",
                slot.day(), slot.slotType(), slot.candidates().size(), cityWide);
        });
    }

    /**
     * <b>이 프로브의 핵심 산출물.</b> 인덱스가 아니라 <b>상호명</b>으로 비교한다 — 섞으면 같은
     * 후보의 번호가 달라지므로 인덱스로 재면 100% 불일치가 나온다.
     */
    private static void printAgreement(PlannerPlan plan, CandidatePool ordered,
        CandidatePool shuffledPool, List<CuratedDay> fromOrdered, List<CuratedDay> fromShuffled) {
        int slotCount = 0;
        int topAgreed = 0;
        int setAgreed = 0;

        System.out.printf("%n[일치] 같은 자리에서 두 순서가 무엇을 골랐는가%n");
        for (int index = 0; index < fromOrdered.size(); index++) {
            CuratedDay left = fromOrdered.get(index);
            CuratedDay right = fromShuffled.get(index);
            System.out.printf("  day %d (%s)%n", left.day(), plan.days().get(index).area());

            for (int position = 0; position < left.slots().size(); position++) {
                CuratedSlot leftSlot = left.slots().get(position);
                CuratedSlot rightSlot = right.slots().get(position);
                if (leftSlot.choices().isEmpty() && rightSlot.choices().isEmpty()) {
                    continue;
                }
                slotCount++;
                boolean topMatch = sameName(firstName(leftSlot), firstName(rightSlot));
                boolean setMatch = sameNameSet(leftSlot, rightSlot);
                topAgreed += topMatch ? 1 : 0;
                setAgreed += setMatch ? 1 : 0;

                System.out.printf("    %-11s %s  1순위 %s · 집합 %s%n", leftSlot.slotType(),
                    "%s | %s".formatted(describe(leftSlot), describe(rightSlot)),
                    topMatch ? "일치" : "불일치", setMatch ? "일치" : "불일치");
            }
        }
        System.out.printf("  → 슬롯 %d개 중 1순위 일치 %d (%.1f%%), 3선택 집합 일치 %d (%.1f%%)%n",
            slotCount, topAgreed, percent(topAgreed, slotCount), setAgreed,
            percent(setAgreed, slotCount));
        System.out.printf("  (판정: 1순위 일치율 80%% 이상이면 원인은 표식, 50%% 이하면 순서다)%n");
        // 목록 길이가 1이면 섞어도 같은 목록이라 일치가 당연하다 — 해석 전에 이걸 봐야 한다.
        System.out.printf("  참고: 후보가 2건 이상인 슬롯 %d개 / 전체 %d개%n",
            countReorderable(ordered), ordered.slots().size());
        System.out.printf("  참고: 섞인 뒤 실제로 1번 항목이 바뀐 슬롯 %d개%n",
            countActuallyReordered(ordered, shuffledPool));
    }

    /**
     * 선택이 목록의 몇 번을 가리켰는지. <b>섞은 쪽에서도 앞쪽이 몰리면 그것이 위치 편향의 직접
     * 증거다</b> — 섞은 목록에서는 "앞쪽이 좋다"가 더 이상 성립하지 않기 때문이다.
     */
    private static void printIndexDistribution(String label, List<CuratedDay> curated) {
        int total = 0;
        int head = 0;
        List<Integer> picked = new ArrayList<>();
        for (CuratedDay day : curated) {
            for (CuratedSlot slot : day.slots()) {
                for (CuratedPlace choice : slot.choices()) {
                    if (choice.listIndex() == null) {
                        continue;   // SUGGESTED — 목록을 가리키지 않는다
                    }
                    total++;
                    picked.add(choice.listIndex());
                    if (choice.listIndex() <= 2) {
                        head++;
                    }
                }
            }
        }
        System.out.printf("%n[인덱스 분포] %s — 목록 참조 %d건 중 #0~#2 가 %d건 (%.1f%%)%n",
            label, total, head, percent(head, total));
        System.out.printf("  고른 번호: %s%n", picked);
    }

    /**
     * <b>{@code location} 폴백이 걸린 슬롯에서 Curator 가 1순위로 무엇을 골랐는가.</b>
     *
     * <p>이슈 체크리스트의 마지막 항목이다 — 표식 수정 전/후로 같은 프로브를 돌려 이 거리를 비교하면
     * "광역 후보가 앞에 서는" 현상이 실제로 줄었는지 알 수 있다.
     */
    private static void printCityWideSlots(PlannerPlan plan, CandidatePool pool,
        List<CuratedDay> curated) {
        System.out.printf("%n[광역 폴백 슬롯] 1순위로 무엇을 골랐는가 — 표식 수정 전후로 비교한다%n");
        boolean any = false;

        for (int index = 0; index < curated.size(); index++) {
            CuratedDay day = curated.get(index);
            for (CuratedSlot slot : day.slots()) {
                CandidateSlot candidates = pool.findOrEmpty(day.day(), slot.slotType());
                boolean hasCityWide = candidates.candidates().stream()
                    .anyMatch(PlaceCandidate::fromCityWideQuery);
                if (!hasCityWide || slot.choices().isEmpty()) {
                    continue;
                }
                any = true;
                PlaceCandidate top = candidates.at(slot.choices().getFirst().listIndex())
                    .orElse(null);
                System.out.printf("  day %d (%s) %-11s 1순위=%s · 거리=%s · 광역=%s%n",
                    day.day(), plan.days().get(index).area(), slot.slotType(),
                    slot.choices().getFirst().placeName(),
                    top == null || top.distanceKm() == null
                        ? "-" : "%.1fkm".formatted(top.distanceKm()),
                    top != null && top.fromCityWideQuery() ? "예" : "아니오");
            }
        }
        if (!any) {
            System.out.printf("  (이번 실행에서는 location 폴백이 걸린 슬롯이 없었다 — "
                + "발동률이 96칸 중 12칸이라 표본에 안 걸릴 수 있다)%n");
        }
    }

    // ── 비교 유틸 ─────────────────────────────────────────────────────────────

    private static String firstName(CuratedSlot slot) {
        return slot.choices().isEmpty() ? null : slot.choices().getFirst().placeName();
    }

    private static boolean sameName(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return PlaceNameNormalizer.similar(left, right);
    }

    /** 순서를 무시하고 <b>같은 세 곳을 골랐는가</b>. 선호 순서만 바뀐 것과 다른 곳을 고른 것을 가른다. */
    private static boolean sameNameSet(CuratedSlot left, CuratedSlot right) {
        if (left.choices().size() != right.choices().size()) {
            return false;
        }
        List<String> remaining = new ArrayList<>(right.choices().stream()
            .map(CuratedPlace::placeName).toList());
        for (CuratedPlace choice : left.choices()) {
            int matched = -1;
            for (int i = 0; i < remaining.size(); i++) {
                if (sameName(choice.placeName(), remaining.get(i))) {
                    matched = i;
                    break;
                }
            }
            if (matched < 0) {
                return false;
            }
            remaining.remove(matched);
        }
        return true;
    }

    /** 후보가 2건 이상이라 섞이는 것이 의미 있는 슬롯 수. */
    private static long countReorderable(CandidatePool pool) {
        return pool.slots().stream().filter(slot -> slot.candidates().size() > 1).count();
    }

    /** 섞은 뒤 실제로 맨 앞 후보가 바뀐 슬롯 수 — 이게 0이면 이 실행의 일치율은 아무 뜻이 없다. */
    private static long countActuallyReordered(CandidatePool ordered, CandidatePool shuffled) {
        long changed = 0;
        for (CandidateSlot slot : ordered.slots()) {
            List<PlaceCandidate> other =
                shuffled.findOrEmpty(slot.day(), slot.slotType()).candidates();
            if (slot.candidates().isEmpty() || other.isEmpty()) {
                continue;
            }
            if (!slot.candidates().getFirst().name().equals(other.getFirst().name())) {
                changed++;
            }
        }
        return changed;
    }

    private static String describe(CuratedSlot slot) {
        if (slot.choices().isEmpty()) {
            return "(비어 있음)";
        }
        StringBuilder line = new StringBuilder();
        for (CuratedPlace choice : slot.choices()) {
            line.append(line.isEmpty() ? "" : " > ").append(choice.placeName());
        }
        return line.toString();
    }

    private static double percent(int part, int whole) {
        return whole == 0 ? 0.0 : part * 100.0 / whole;
    }

    // ── 조립 ──────────────────────────────────────────────────────────────────

    private static CuratorAgent curatorAgent(String openAiKey, AiCourseMetrics metrics) {
        AiLlmProperties properties = probeProperties(openAiKey);
        OpenAiLlmClient llmClient = new OpenAiLlmClient(properties,
            new LlmResponseParser(new ObjectMapper()), new LlmRetryExecutor(properties), metrics,
            OpenAiLlmClient.buildChatModel(properties.openai().baseUrl(), openAiKey,
                properties.timeoutMs()));
        return new CuratorAgent(llmClient, new PromptLoader(), metrics, Runnable::run);
    }

    private static CandidateRetrievalStage retrievalStage(String naverId, String naverSecret,
        String tourKey, String kakaoKey, AiCourseMetrics metrics) {
        KakaoLocalClient kakaoClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient("https://dapi.kakao.com", kakaoKey));
        NaverLocalClient naverClient = new NaverLocalClient(NaverConfig.buildNaverWebClient(
            "https://naverapihub.apigw.ntruss.com", naverId, naverSecret));
        TourApiClient tourClient = new TourApiClient(TourApiConfig.buildTourApiWebClient(
            "https://apis.data.go.kr/B551011/KorService2"), tourKey);

        return new CandidateRetrievalStage(new AreaGeocoder(kakaoClient),
            new NaverLocalSeedSource(naverClient), new TourApiSource(tourClient), metrics,
            Runnable::run);
    }

    /** 운영 설정과 <b>같은 모델·같은 추론 강도</b>. 다르면 여기서 잰 편향을 운영에 옮길 수 없다. */
    private static AiLlmProperties probeProperties(String apiKey) {
        return new AiLlmProperties(
            "openai",
            60_000,
            2,
            new AiLlmProperties.Retry(3, 2, 0.5, 4.0, 0.3),
            Map.of(
                PlannerAgent.AGENT_NAME,
                new AiLlmProperties.Agent("gpt-5.6-luna", null, 2048, null),
                CuratorAgent.AGENT_NAME,
                new AiLlmProperties.Agent("gpt-5.6-luna", null, 4096, "low")),
            new AiLlmProperties.OpenAi(apiKey, "https://api.openai.com"));
    }

    /**
     * Spring 컨텍스트를 쓰지 않으므로 spring-dotenv 가 동작하지 않는다 — 실제 환경변수 → 레포 루트
     * {@code .env} 순으로 찾는다.
     */
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
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** 관측된 {@code (location, area, anchor)} 삼중항. Planner 를 다시 부르지 않기 위한 픽스처다. */
    private record Case(String location, String area, String anchor) {
    }
}
