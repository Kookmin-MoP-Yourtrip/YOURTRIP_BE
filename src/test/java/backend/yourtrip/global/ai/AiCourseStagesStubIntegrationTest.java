package backend.yourtrip.global.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.candidate.AreaGeocoder;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateRetrievalStage;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.NaverLocalSeedSource;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.candidate.TourApiSource;
import backend.yourtrip.global.ai.grounding.GroundedDay;
import backend.yourtrip.global.ai.grounding.GroundedPlace;
import backend.yourtrip.global.ai.grounding.GroundingStage;
import backend.yourtrip.global.ai.grounding.PlaceUrlEnricher;
import backend.yourtrip.global.ai.pipeline.CuratedDay;
import backend.yourtrip.global.ai.pipeline.CuratedPlace;
import backend.yourtrip.global.ai.pipeline.CuratedSlot;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.config.KakaoConfig;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.config.NaverConfig;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.config.TourApiConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 세 스테이지를 실제 클라이언트와 함께 돌리는 스텁 통합 테스트 (ROADMAP 5-7).
 *
 * <p><b>단위 테스트는 클라이언트를 목으로 바꿔 치우므로 "응답이 실제로 해석되는가"를 못 본다.</b>
 * 여기서는 0-6이 들여온 WireMock으로 네이버·TourAPI·카카오를 한 서버에 세 경로로 세우고,
 * 스테이지가 <b>부분 장애에서 어떻게 degrade하는지</b>를 본다 — 그게 이 파이프라인 설계의 핵심이라
 * 목으로 재현한 것과 실제 응답으로 재현한 것을 둘 다 갖는 값이 있다.
 *
 * <p><b>Content-Type을 실제 값 그대로 쓴다.</b> 4단계 판정 11에서 스텁이 실제와 다른
 * {@code Content-Type}을 쓰는 동안 클라이언트가 전 호출 실패였는데도 테스트는 통과한 사건이
 * 있었다 — 네이버는 {@code text/plain}, TourAPI·카카오는 {@code application/json}이다.
 * 그래서 성공 경로에서는 "예외가 없었다"가 아니라 <b>건수</b>를 단언한다.
 */
@DisplayName("AI 코스 스테이지 스텁 통합 (ROADMAP 5-7)")
class AiCourseStagesStubIntegrationTest {

    private static final String NAVER_PATH = "/search/v1/local";
    private static final String TOUR_PATH = "/locationBasedList2";
    private static final String KAKAO_PATH = "/v2/local/search/keyword.json";

    /** 4-7 실호출 좌표 — 천마총. 카카오 지오코딩 스텁이 이 값을 돌려준다. */
    private static final String ANCHOR_X = "129.2104983997";
    private static final String ANCHOR_Y = "35.8386877792";

    private WireMockServer wireMock;
    private SimpleMeterRegistry meterRegistry;
    private CandidateRetrievalStage retrievalStage;
    private GroundingStage groundingStage;
    private PlaceUrlEnricher urlEnricher;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        NaverLocalClient naverClient = new NaverLocalClient(
            NaverConfig.buildNaverWebClient(wireMock.baseUrl(), "id", "secret"));
        TourApiClient tourClient = new TourApiClient(
            TourApiConfig.buildTourApiWebClient(wireMock.baseUrl()), "test-key");
        KakaoLocalClient kakaoClient = new KakaoLocalClient(
            KakaoConfig.buildKakaoWebClient(wireMock.baseUrl(), "test-key"));

        meterRegistry = new SimpleMeterRegistry();
        AiCourseMetrics metrics = new AiCourseMetrics(meterRegistry);
        retrievalStage = new CandidateRetrievalStage(new AreaGeocoder(kakaoClient),
            new NaverLocalSeedSource(naverClient), new TourApiSource(tourClient), metrics,
            Runnable::run);
        groundingStage = new GroundingStage(kakaoClient, metrics, Runnable::run);
        urlEnricher = new PlaceUrlEnricher(kakaoClient, metrics, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    // ── 시나리오 ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 경로")
    class HappyPath {

        @Test
        @DisplayName("두 소스의 응답이 실제로 해석돼 한 목록으로 합쳐진다")
        void mergesBothSources() {
            stubKakaoDocuments(kakaoDocument("대릉원", "AT4", ANCHOR_X, ANCHOR_Y));
            stubNaver(naverBody(naverItem("황리단길", "관광,명소>거리", "1292104983", "358386877")));
            stubTour(tourBody(tourItem("골굴사", "A02010800", "129.4", "35.9", "5000.0")));

            CandidatePool pool = retrieve(SlotType.ATTRACTION);
            List<PlaceCandidate> candidates =
                pool.findOrEmpty(1, SlotType.ATTRACTION).candidates();

            // 성공 경로가 실제로 성공했음을 건수로 단언한다 — "예외가 없었다"는 판정 11에서
            // 전 호출 실패를 통과시켰던 바로 그 기준이다.
            assertThat(candidates).extracting(PlaceCandidate::name)
                .containsExactly("황리단길", "골굴사");
            assertThat(candidates.get(0).seeded()).isTrue();
            assertThat(candidates.get(1).official()).isTrue();
            // TourAPI 가 준 dist(m)가 km 로 환산돼 실린다.
            assertThat(candidates.get(1).distanceKm()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("목록 후보는 카카오를 부르지 않고 좌표를 승계한다")
        void inheritedCandidatesDoNotCallKakao() {
            stubKakaoDocuments(kakaoDocument("대릉원", "AT4", ANCHOR_X, ANCHOR_Y));
            stubNaver(naverBody(naverItem("황리단길", "관광,명소>거리", "1292104983", "358386877")));
            stubTour(emptyTourBody());

            CandidatePool pool = retrieve(SlotType.ATTRACTION);
            int kakaoCallsAfterRetrieval = kakaoCallCount();

            List<GroundedDay> days = groundingStage.ground("경주",
                List.of(curated(SlotType.ATTRACTION, fromList(0, "황리단길"))), pool,
                CourseDeadline.unbounded());

            assertThat(kakaoCallCount()).isEqualTo(kakaoCallsAfterRetrieval);
            GroundedPlace place = days.get(0).slots().get(0).preferred().orElseThrow();
            assertThat(place.source()).isEqualTo(CandidateSourceType.SEEDED);
            assertThat(place.latitude()).isEqualTo(35.8386877);
        }
    }

    @Nested
    @DisplayName("부분 장애 — degrade, don't fail")
    class PartialFailure {

        @Test
        @DisplayName("네이버만 죽으면 관광 슬롯은 TourAPI 만으로 채워진다")
        void naverDownLeavesTourApi() {
            stubKakaoDocuments(kakaoDocument("대릉원", "AT4", ANCHOR_X, ANCHOR_Y));
            stubNaverStatus(500);
            stubTour(tourBody(tourItem("골굴사", "A02010800", "129.4", "35.9", "5000.0")));

            assertThat(retrieve(SlotType.ATTRACTION).findOrEmpty(1, SlotType.ATTRACTION)
                .candidates()).extracting(PlaceCandidate::name).containsExactly("골굴사");
        }

        @Test
        @DisplayName("TourAPI 만 죽으면 시더만으로 채워진다")
        void tourApiDownLeavesSeeder() {
            stubKakaoDocuments(kakaoDocument("대릉원", "AT4", ANCHOR_X, ANCHOR_Y));
            stubNaver(naverBody(naverItem("황리단길", "관광,명소>거리", "1292104983", "358386877")));
            stubTourStatus(503);

            assertThat(retrieve(SlotType.ATTRACTION).findOrEmpty(1, SlotType.ATTRACTION)
                .candidates()).extracting(PlaceCandidate::name).containsExactly("황리단길");
        }

        @Test
        @DisplayName("지오코딩이 무결과면 TourAPI 를 아예 부르지 않는다 — 시더는 그대로 돈다")
        void geocodeNoResultSkipsTourApi() {
            stubKakaoDocuments();   // documents: []
            stubNaver(naverBody(naverItem("황리단길", "관광,명소>거리", "1292104983", "358386877")));
            stubTour(tourBody(tourItem("골굴사", "A02010800", "129.4", "35.9", "5000.0")));

            CandidatePool pool = retrieve(SlotType.ATTRACTION);

            assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(TOUR_PATH)))).isEmpty();
            assertThat(pool.findOrEmpty(1, SlotType.ATTRACTION).candidates())
                .extracting(PlaceCandidate::name).containsExactly("황리단길");
        }

        @Test
        @DisplayName("두 소스가 다 죽어도 예외가 아니라 빈 풀이다 — 초안 구조로 degrade")
        void bothSourcesDownYieldsEmptyPool() {
            stubKakaoDocuments(kakaoDocument("대릉원", "AT4", ANCHOR_X, ANCHOR_Y));
            stubNaverStatus(500);
            stubTourStatus(500);

            CandidatePool pool = retrieve(SlotType.ATTRACTION);

            assertThat(pool.isEmpty()).isTrue();
            assertThat(pool.slots()).hasSize(1);
        }

        @Test
        @DisplayName("네이버 429 는 EMPTY 가 아니라 FAILED 로 남는다 — 쿼터와 데이터 부족은 다르다")
        void quotaExceededIsNotEmpty() {
            stubKakaoDocuments(kakaoDocument("대릉원", "AT4", ANCHOR_X, ANCHOR_Y));
            stubNaverStatus(429);
            stubTour(emptyTourBody());

            retrieve(SlotType.ATTRACTION);

            // 힐링 키워드의 modifier 2개 + 기본 쿼리 = 3회가 전부 429 다.
            assertThat(counter(AiCourseMetrics.CANDIDATE_RETRIEVAL, "source",
                AiCourseMetrics.SOURCE_NAVER_LOCAL, "result", "failed")).isEqualTo(3.0);
            assertThat(counter(AiCourseMetrics.CANDIDATE_RETRIEVAL, "source",
                AiCourseMetrics.SOURCE_NAVER_LOCAL, "result", "empty")).isZero();
        }
    }

    @Nested
    @DisplayName("그라운딩 — 환각의 종류를 가른다")
    class Grounding {

        @Test
        @DisplayName("카카오에 아무것도 없으면 no_result — 순수 환각")
        void pureHallucinationIsNoResult() {
            stubKakaoDocuments();

            groundingStage.ground("경주",
                List.of(curated(SlotType.ATTRACTION, suggested("있을리없는집"))),
                CandidatePool.empty(), CourseDeadline.unbounded());

            assertThat(counter(AiCourseMetrics.GROUNDING_MATCH,
                "result", "no_result", "source", "suggested")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("비슷한 게 오면 name_mismatch — 세탁 위험 구간이라 따로 센다")
        void launderingRiskIsNameMismatch() {
            stubKakaoDocuments(kakaoDocument("전혀다른가게", "AT4", ANCHOR_X, ANCHOR_Y));

            List<GroundedDay> days = groundingStage.ground("경주",
                List.of(curated(SlotType.ATTRACTION, suggested("있을리없는집"))),
                CandidatePool.empty(), CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).isEmpty()).isTrue();
            assertThat(counter(AiCourseMetrics.GROUNDING_MATCH,
                "result", "name_mismatch", "source", "suggested")).isEqualTo(1.0);
            assertThat(counter(AiCourseMetrics.GROUNDING_MATCH,
                "result", "no_result", "source", "suggested")).isZero();
        }

        @Test
        @DisplayName("카카오가 429 를 주면 그 후보만 탈락하고 차순위가 올라온다")
        void quotaFailureDropsOnlyThatCandidate() {
            stubKakaoStatus(429);

            List<GroundedDay> days = groundingStage.ground("경주",
                List.of(curated(SlotType.ATTRACTION, suggested("황남빵"), suggested("십원빵"))),
                CandidatePool.empty(), CourseDeadline.unbounded());

            assertThat(days.get(0).slots().get(0).isEmpty()).isTrue();
            assertThat(counter(AiCourseMetrics.GROUNDING_MATCH,
                "result", "failed", "source", "suggested")).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("URL 보강")
    class UrlEnrichment {

        @Test
        @DisplayName("이름이 맞고 좌표도 가까우면 URL 이 붙는다")
        void attachesUrl() {
            stubKakaoDocuments(kakaoDocument("황리단길", "AT4", ANCHOR_X, ANCHOR_Y));

            List<GroundedPlace> enriched = urlEnricher.enrich("경주",
                List.of(placeWithoutUrl("황리단길", 35.8386877792, 129.2104983997)),
                CourseDeadline.unbounded());

            assertThat(enriched.get(0).placeUrl()).isEqualTo("http://place.map.kakao.com/1");
        }

        @Test
        @DisplayName("좌표가 1km 밖이면 URL 을 비운다 — 엉뚱한 URL 은 URL 없음보다 나쁘다")
        void rejectsDistantSameName() {
            // 같은 상호명의 다른 지점. 이름 게이트만으로는 못 거른다.
            stubKakaoDocuments(kakaoDocument("황리단길", "AT4", "129.2300000", "35.8386877"));

            List<GroundedPlace> enriched = urlEnricher.enrich("경주",
                List.of(placeWithoutUrl("황리단길", 35.8386877792, 129.2104983997)),
                CourseDeadline.unbounded());

            assertThat(enriched.get(0).placeUrl()).isNull();
            assertThat(counter(AiCourseMetrics.PLACE_URL, "result", "too_far")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("예산이 소진되면 카카오를 한 번도 부르지 않는다")
        void skipsWhenBudgetExhausted() {
            stubKakaoDocuments(kakaoDocument("황리단길", "AT4", ANCHOR_X, ANCHOR_Y));

            urlEnricher.enrich("경주",
                List.of(placeWithoutUrl("황리단길", 35.8386877792, 129.2104983997)),
                CourseDeadline.startingNow(Duration.ZERO));

            assertThat(kakaoCallCount()).isZero();
            assertThat(counter(AiCourseMetrics.PLACE_URL, "result", "skipped")).isEqualTo(1.0);
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private CandidatePool retrieve(SlotType slotType) {
        return retrievalStage.retrieve("경주",
            new PlannerPlan("경주 1일", "고도", List.of(
                new PlannerDayPlan(1, "황리단길 일대", "대릉원", List.of(slotType)))),
            List.of(KeywordType.HEALING), CourseDeadline.unbounded());
    }

    private static CuratedDay curated(SlotType slotType, CuratedPlace... choices) {
        return new CuratedDay(1, List.of(new CuratedSlot(slotType, List.of(choices))));
    }

    private static CuratedPlace fromList(int listIndex, String name) {
        return new CuratedPlace(CandidateSourceType.SEEDED, listIndex, name);
    }

    private static CuratedPlace suggested(String name) {
        return new CuratedPlace(CandidateSourceType.SUGGESTED, null, name);
    }

    private static GroundedPlace placeWithoutUrl(String name, double latitude, double longitude) {
        return new GroundedPlace(name, SlotType.ATTRACTION, latitude, longitude,
            "경북 경주시 포석로 1080", null, CandidateSourceType.SEEDED, null);
    }

    private double counter(String name, String... tags) {
        return meterRegistry.get(name).tags(tags).counter().count();
    }

    private int kakaoCallCount() {
        return wireMock.findAll(getRequestedFor(urlPathEqualTo(KAKAO_PATH))).size();
    }

    // ── 스텁 ──────────────────────────────────────────────────────────────────

    /** 네이버는 JSON을 {@code text/plain}으로 준다 — 4단계 판정 11의 교훈이다. */
    private void stubNaver(String body) {
        wireMock.stubFor(get(urlPathEqualTo(NAVER_PATH)).willReturn(aResponse()
            .withStatus(200).withHeader("Content-Type", "text/plain;charset=UTF-8")
            .withBody(body)));
    }

    private void stubNaverStatus(int status) {
        wireMock.stubFor(get(urlPathEqualTo(NAVER_PATH)).willReturn(aResponse()
            .withStatus(status).withHeader("Content-Type", "application/json;charset=UTF-8")
            .withBody("{\"errorCode\":\"" + status + "\"}")));
    }

    private void stubTour(String body) {
        wireMock.stubFor(get(urlPathEqualTo(TOUR_PATH)).willReturn(aResponse()
            .withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubTourStatus(int status) {
        wireMock.stubFor(get(urlPathEqualTo(TOUR_PATH))
            .willReturn(aResponse().withStatus(status)));
    }

    private void stubKakaoDocuments(String... documentsJson) {
        wireMock.stubFor(get(urlPathEqualTo(KAKAO_PATH)).willReturn(aResponse()
            .withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"documents\":[" + String.join(",", documentsJson)
                + "],\"meta\":{\"total_count\":" + documentsJson.length + "}}")));
    }

    private void stubKakaoStatus(int status) {
        wireMock.stubFor(get(urlPathEqualTo(KAKAO_PATH))
            .willReturn(aResponse().withStatus(status)));
    }

    private static String kakaoDocument(String placeName, String groupCode, String x, String y) {
        return """
            {
              "id": "1", "place_name": "%s", "category_name": "여행 > 관광명소",
              "category_group_code": "%s", "address_name": "경북 경주시 황남동",
              "road_address_name": "경북 경주시 포석로 1080",
              "x": "%s", "y": "%s", "place_url": "http://place.map.kakao.com/1"
            }
            """.formatted(placeName, groupCode, x, y);
    }

    private static String naverBody(String... itemsJson) {
        return "{\"total\":" + itemsJson.length + ",\"start\":1,\"display\":" + itemsJson.length
            + ",\"items\":[" + String.join(",", itemsJson) + "]}";
    }

    private static String naverItem(String title, String category, String mapx, String mapy) {
        return """
            {
              "title": "%s", "link": "", "category": "%s", "description": "", "telephone": "",
              "address": "경상북도 경주시 황남동", "roadAddress": "경북 경주시 포석로 1080",
              "mapx": "%s", "mapy": "%s"
            }
            """.formatted(title, category, mapx, mapy);
    }

    private static String emptyTourBody() {
        return "{\"response\":{\"header\":{\"resultCode\":\"0000\",\"resultMsg\":\"OK\"},"
            + "\"body\":{\"items\":\"\",\"numOfRows\":0,\"pageNo\":1,\"totalCount\":0}}}";
    }

    private static String tourBody(String... itemsJson) {
        return "{\"response\":{\"header\":{\"resultCode\":\"0000\",\"resultMsg\":\"OK\"},"
            + "\"body\":{\"items\":{\"item\":[" + String.join(",", itemsJson) + "]},"
            + "\"numOfRows\":" + itemsJson.length + ",\"pageNo\":1,"
            + "\"totalCount\":" + itemsJson.length + "}}}";
    }

    private static String tourItem(String title, String cat3, String mapx, String mapy,
        String dist) {
        return """
            {
              "contentid": "126508", "contenttypeid": "12", "title": "%s",
              "cat1": "A02", "cat2": "A0201", "cat3": "%s",
              "addr1": "경북 경주시 골굴로 101", "addr2": "",
              "mapx": "%s", "mapy": "%s", "dist": "%s", "firstimage": ""
            }
            """.formatted(title, cat3, mapx, mapy, dist);
    }
}
