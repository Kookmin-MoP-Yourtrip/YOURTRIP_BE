package backend.yourtrip.global.naver;

import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.naver.dto.NaverLocalResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 네이버 지역검색 클라이언트 — 후보 공급의 <b>인기 축</b>(ROADMAP 4-1).
 *
 * <p><b>V1에서 네이버 의존은 이 클라이언트 하나다.</b> 블로그 검색(9단계)은 만들지 않는다.
 *
 * <p>역할은 {@code "{area} {searchHint}"}를 {@code sort=comment}로 물어 리뷰 수 상위 5건을 받는
 * 것이다. 카카오에 인기도 정렬이 없어 밀집 지역에서 후보가 임의로 잘리는 문제를 이 시더가 푼다
 * (설계의 "시더가 필요한 이유").
 *
 * <p><b>응답을 그라운딩 데이터로 그대로 쓴다.</b> 좌표·주소·카테고리가 응답에 있으므로 상호명으로
 * 카카오에 다시 물어 "공식화"하지 않는다 — 그건 후보가 전부 파라메트릭이라 좌표를 얻을 곳이
 * 카카오뿐이던 시절의 잔재다.
 *
 * <p><b>예외를 던지지 않는다.</b> 실패는 {@link NaverLocalResult.Failed}로 돌려준다 — 이유는
 * {@link NaverLocalResult}의 설명 참고.
 *
 * <p><b>모든 호출이 {@link NaverRateLimiter}를 거친다</b>(이슈 #185). 초당 한도는 이 API 의 성질이라
 * 호출부(시더·재질의)가 아니라 여기서 지킨다 — 그래야 새 호출 경로가 생겨도 한도를 빠져나가지 못한다.
 */
@Component
@Slf4j
public class NaverLocalClient {

    /**
     * 4-2 실호출로 확정한 경로. <b>레거시 {@code /v1/search/local.json}은 404다</b> — API HUB로
     * 이관되며 확장자가 사라지고 세그먼트 순서가 뒤집혔다.
     */
    private static final String LOCAL_PATH = "/search/v1/local";

    /**
     * 리뷰 수 순. <b>이 값이 이 클라이언트의 존재 이유</b>이므로 파라미터로 열지 않고 고정한다 —
     * 호출부가 {@code accuracy}로 바꿀 수 있으면 인기 축이라는 계약이 조용히 깨진다.
     */
    private static final String SORT_BY_COMMENT = "comment";

    /**
     * 지역검색이 돌려주는 최대 건수. 10을 요청해도 5건이고, {@code start}는 <b>거부가 아니라
     * 무시</b>돼 몇을 넣든 같은 5건이 온다(4-2 실측). 그래서 {@code start}를 아예 노출하지 않는다 —
     * 노출하면 5-8이 페이징으로 풀을 넓히려다 같은 후보를 중복으로 세는 조용한 버그가 된다.
     */
    public static final int MAX_DISPLAY = 5;

    /** 429 본문의 {@code errorCode} 중 초당 제한을 뜻하는 값. 근거는 {@link #classifyTooManyRequests}. */
    private static final Set<String> RATE_LIMIT_CODES = Set.of("410", "420");

    /** 오류 본문 해석 전용. 성공 응답은 WebClient 디코더가 읽으므로 여기서는 쓰지 않는다. */
    private static final ObjectMapper ERROR_BODY_READER = new ObjectMapper();

    /**
     * <b>제한기 앞에서 기다린 시간</b> (이슈 #185). {@code result=rejected}는 대기 상한을 넘겨 HTTP 를
     * 보내지 않고 포기한 호출이다. 대기 분포가 곧 "버스트 제어가 후보 공급을 얼마나 늘렸는가"의 답이다.
     */
    public static final String THROTTLE_WAIT = "naver.local.throttle.wait";

    static final String THROTTLE_ACQUIRED = "acquired";
    static final String THROTTLE_REJECTED = "rejected";

    private final WebClient naverWebClient;
    private final NaverRateLimiter rateLimiter;
    private final Timer acquiredWait;
    private final Timer rejectedWait;

    /**
     * 생성자가 둘이라 주입할 쪽을 명시한다. 표시하지 않으면 Spring 이 기본 생성자를 찾다가 컨텍스트가
     * 깨진다({@code LlmRetryExecutor}가 겪은 전례).
     */
    @Autowired
    public NaverLocalClient(WebClient naverWebClient, NaverRateLimiter naverRateLimiter,
        MeterRegistry meterRegistry) {
        this.naverWebClient = naverWebClient;
        this.rateLimiter = naverRateLimiter;
        // 0으로 미리 등록한다 — "거절 0건"과 "시계열 없음"을 갈라야 전후 비교가 된다.
        this.acquiredWait = throttleTimer(meterRegistry, THROTTLE_ACQUIRED);
        this.rejectedWait = throttleTimer(meterRegistry, THROTTLE_REJECTED);
    }

    /**
     * 제한 없이 조립한다. Spring 컨텍스트 없이 클라이언트를 만드는 스텁 테스트·실호출 프로브용이다 —
     * 그쪽은 호출을 순차로 보내 초당 한도에 닿지 않는다.
     */
    public NaverLocalClient(WebClient naverWebClient) {
        this(naverWebClient, NaverRateLimiter.unlimited(), new SimpleMeterRegistry());
    }

    private static Timer throttleTimer(MeterRegistry registry, String result) {
        return Timer.builder(THROTTLE_WAIT)
            .tag("result", result)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofMillis(1))
            .maximumExpectedValue(Duration.ofSeconds(5))
            .register(registry);
    }

    /**
     * 지역검색 1회.
     *
     * @param query   {@code "{area} {searchHint}"} 또는 스타일 modifier가 붙은 변주
     * @param display 요청 건수. {@link #MAX_DISPLAY}를 넘겨도 5건으로 잘린다
     */
    public NaverLocalResult search(String query, int display) {
        if (query == null || query.isBlank()) {
            return new NaverLocalResult.Empty();
        }
        int size = Math.min(Math.max(display, 1), MAX_DISPLAY);
        NaverLocalResult throttled = awaitTurn(query);
        if (throttled != null) {
            return throttled;
        }
        try {
            NaverLocalResponse response = naverWebClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path(LOCAL_PATH)
                    .queryParam("query", query)
                    .queryParam("display", size)
                    .queryParam("sort", SORT_BY_COMMENT)
                    .build())
                .retrieve()
                .bodyToMono(NaverLocalResponse.class)
                // 타임아웃은 NaverConfig의 HttpClient(connect 2초 / response 3초)가 담당한다.
                // block(Duration)으로 제한하면 초과 시 IllegalStateException 이 던져져 아래
                // WebClientException catch 를 빠져나간다(카카오에서 실제로 겪은 결함이다).
                .block();

            List<NaverPlace> places = NaverPlaceMapper.toPlaces(response);
            if (places.isEmpty()) {
                log.debug("네이버 지역검색 결과 0건: query={}", query);
            }
            return NaverLocalResult.of(places);
        } catch (WebClientResponseException e) {
            ApiFailureCause cause = classify(e);
            log.warn("네이버 지역검색 실패({}): query={}, status={}, body={}",
                cause, query, e.getStatusCode(), e.getResponseBodyAsString());
            return new NaverLocalResult.Failed(cause, e.getStatusCode().toString());
        } catch (WebClientException e) {
            // 타임아웃·커넥션 실패·풀 고갈은 WebClientRequestException 으로 올라온다.
            log.warn("네이버 지역검색 호출 실패: query={}, error={}", query, e.getMessage());
            return new NaverLocalResult.Failed(
                ApiFailureCause.TRANSPORT_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            // 200인데 본문이 스키마와 다른 경우(역직렬화 실패). 후보 공급이 죽어도 코스는 살아야 한다.
            log.warn("네이버 지역검색 응답 해석 실패: query={}, error={}", query, e.getMessage());
            return new NaverLocalResult.Failed(ApiFailureCause.MALFORMED, e.getMessage());
        }
    }

    /**
     * 제한기에서 차례를 기다린다. 차례를 얻으면 {@code null}, 못 얻으면 호출부가 그대로 돌려줄 실패다.
     *
     * <p>거절을 {@link ApiFailureCause#RATE_LIMITED}로 돌려주는 이유: 결과가 429 를 맞은 것과 같다 —
     * 그 질의의 후보만 빠지고 나머지는 fail-open 으로 계속된다. 다른 점은 <b>네이버에 닿지 않았다</b>는
     * 것뿐이라, 그 구분은 {@link #THROTTLE_WAIT}{@code {result=rejected}}가 맡는다.
     */
    private NaverLocalResult awaitTurn(String query) {
        long startedAt = System.nanoTime();
        try {
            long waited = rateLimiter.acquire();
            if (waited < 0) {
                rejectedWait.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
                log.warn("네이버 지역검색 호출 속도 상한으로 포기: query={}", query);
                return new NaverLocalResult.Failed(ApiFailureCause.RATE_LIMITED, "local throttle");
            }
            acquiredWait.record(waited, TimeUnit.NANOSECONDS);
            return null;
        } catch (InterruptedException e) {
            // 요청 마감으로 끊긴 것이다. 플래그를 되살려 위쪽이 인터럽트를 알게 한다(#176 의 원칙).
            Thread.currentThread().interrupt();
            return new NaverLocalResult.Failed(ApiFailureCause.TRANSPORT_ERROR,
                "interrupted while throttled");
        }
    }

    private static ApiFailureCause classify(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429) {
            return classifyTooManyRequests(e.getResponseBodyAsString());
        }
        if (status == 401 || status == 403) {
            return ApiFailureCause.UNAUTHORIZED;
        }
        return ApiFailureCause.HTTP_ERROR;
    }

    /**
     * 429 를 <b>본문의 {@code errorCode}로</b> 가른다(이슈 #179). 상태 코드만으로는 초당 제한과
     * 월 한도 소진이 같은 429 라 구별되지 않는다.
     *
     * <p>코드 표는 NCP API 공통 오류 코드 문서를 따른다 — {@code 400} Quota Exceeded,
     * {@code 410} Throttle Limited, {@code 420} Rate Limited. 실측에서 받은 429 는 전부
     * {@code 420}이었다({@code {"errorCode":"420","message":"Rate Limited"}}). 문서 예시는
     * {@code {"error":{...}}}로 한 겹 감싼 형태라 둘 다 읽는다.
     *
     * <p><b>코드를 못 읽으면 {@code QUOTA_EXCEEDED}로 둔다.</b> 이 분류 전의 동작 그대로다 —
     * 정체를 모르는 429 를 초당 제한으로 보면 월 한도가 끝났을 때 모든 호출이 헛된 재시도를 한 번씩
     * 더 하고, 그 대기만큼 후보 공급이 늘어진다.
     */
    static ApiFailureCause classifyTooManyRequests(String body) {
        String errorCode = errorCode(body);
        if (RATE_LIMIT_CODES.contains(errorCode)) {
            return ApiFailureCause.RATE_LIMITED;
        }
        return ApiFailureCause.QUOTA_EXCEEDED;
    }

    private static String errorCode(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = ERROR_BODY_READER.readTree(body);
            JsonNode code = root.has("errorCode") ? root.get("errorCode")
                : root.path("error").path("errorCode");
            return code.asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
