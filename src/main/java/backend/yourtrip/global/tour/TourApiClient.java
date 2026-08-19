package backend.yourtrip.global.tour;

import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.tour.config.TourApiConfig;
import backend.yourtrip.global.tour.dto.TourApiResponse;
import backend.yourtrip.global.tour.dto.TourApiResponse.Header;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 한국관광공사 TourAPI 클라이언트 (ROADMAP 4-7). 관광 슬롯의 <b>커버리지와 분류</b> 축을 맡는다.
 *
 * <h2>왜 {@code locationBasedList2}인가</h2>
 * 지역으로 조회하는 방식은 {@code areaBasedList2}(시도·시군구 코드)와 이것(좌표 + 반경) 둘인데
 * 설계가 후자를 택했다 — 코드표·이름 별칭 매칭이 사라지고, day별 권역 필터가 조회 자체에 내장된다.
 * 좌표는 {@code AreaGeocoder}(4-8)가 만든다.
 *
 * <h2>반경은 튜닝 파라미터가 아니다</h2>
 * {@link #RADIUS_METERS}는 <b>최대 고정 울타리</b>이고 실질 필터는 <b>거리순 + {@code numOfRows}
 * cap</b>이다. 반경을 좁히면 "몇 km가 맞는가"라는 답 없는 튜닝 변수가 생기는데, 거리순 정렬은
 * 정할 숫자가 없다 — 가까운 것부터 세면 그만이다. 4-7 실호출에서 경주 반경 20km 안에 관광지가
 * 93건이었고 가까운 50건이 4.5km 안에 들어왔다.
 *
 * <h2>실패 판정이 다른 두 클라이언트와 다르다</h2>
 * 공공데이터포털은 <b>인증 실패를 HTTP 403 + JSON 본문</b>({@code OpenAPI_ServiceResponse})으로
 * 주고, 서비스 자체의 오류는 <b>HTTP 200 + {@code resultCode}</b>로 준다. 그래서 상태코드만 보면
 * 실패를 성공으로 읽는다 — {@link #search}가 두 채널을 모두 본다.
 */
@Component
@Slf4j
public class TourApiClient {

    private static final String LOCATION_BASED_PATH = "/locationBasedList2";

    /** 설계가 정한 최대 울타리. 튜닝값이 아니다(위 javadoc 참고). */
    public static final int RADIUS_METERS = 20_000;

    /** 거리순. TourAPI에는 인기도 정렬이 없어 선택지가 제목·수정일·생성일·거리순뿐이다. */
    private static final String ARRANGE_BY_DISTANCE = "E";

    /**
     * 한 번에 받는 최대 건수. 실질 필터가 이 cap이므로 <b>반경보다 이 값이 중요하다.</b>
     * 설계 기준 50이고, 4-7 실호출에서 50 요청 시 정확히 50건이 왔다.
     */
    public static final int MAX_ROWS = 50;

    /** 관광지 / 문화시설 / 레포츠. 축제(15)는 날짜 매칭이 필요해 V1에서 뺐다. */
    public static final int CONTENT_TYPE_ATTRACTION = 12;
    public static final int CONTENT_TYPE_CULTURE = 14;
    public static final int CONTENT_TYPE_LEISURE = 28;

    private final WebClient tourApiWebClient;
    private final String serviceKey;

    public TourApiClient(WebClient tourApiWebClient,
        @Value("${tour.service-key:}") String serviceKey) {
        this.tourApiWebClient = tourApiWebClient;
        // Encoding / Decoding 어느 형태로 발급받았든 여기서 하나로 맞춘다.
        this.serviceKey = TourApiConfig.normalizeServiceKey(serviceKey);
    }

    /**
     * 좌표 주변의 관광 항목을 거리순으로 가져온다.
     *
     * @param latitude      WGS84 위도. 요청 파라미터 이름은 {@code mapY}다
     * @param longitude     WGS84 경도. 요청 파라미터 이름은 {@code mapX}다
     * @param contentTypeId {@link #CONTENT_TYPE_ATTRACTION} 등
     * @param numOfRows     최대 {@link #MAX_ROWS}로 잘린다
     * @return 실패해도 예외를 던지지 않는다(fail-open)
     */
    public TourApiResult search(double latitude, double longitude, int contentTypeId,
        int numOfRows) {
        int rows = Math.min(Math.max(numOfRows, 1), MAX_ROWS);
        try {
            TourApiResponse response = tourApiWebClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path(LOCATION_BASED_PATH)
                    // 인코딩 모드가 NONE 이라 여기 넣는 값은 그대로 URI 가 된다.
                    // serviceKey 가 이미 퍼센트 인코딩돼 있어서 그래야 한다(TourApiConfig 참고).
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("MobileOS", "ETC")
                    .queryParam("MobileApp", "YOURTRIP")
                    .queryParam("_type", "json")
                    .queryParam("mapX", longitude)
                    .queryParam("mapY", latitude)
                    .queryParam("radius", RADIUS_METERS)
                    .queryParam("contentTypeId", contentTypeId)
                    .queryParam("arrange", ARRANGE_BY_DISTANCE)
                    .queryParam("numOfRows", rows)
                    .queryParam("pageNo", 1)
                    .build())
                .retrieve()
                .bodyToMono(TourApiResponse.class)
                // 타임아웃은 TourApiConfig의 HttpClient(connect 2초 / response 3초)가 담당한다.
                // block(Duration)으로 제한하면 초과 시 IllegalStateException 이 던져져
                // 아래 WebClientException catch 를 빠져나간다(카카오에서 실제로 겪은 결함이다).
                .block();

            Header header = header(response);
            if (header != null && !header.isOk()) {
                // HTTP 200인데 본문이 실패인 경우. 상태코드만 봤다면 성공으로 읽었을 응답이다.
                ApiFailureCause cause = classifyBody(header.resultCode(), header.resultMsg());
                log.warn("TourAPI 본문 오류({}): resultCode={}, resultMsg={}",
                    cause, header.resultCode(), header.resultMsg());
                return new TourApiResult.Failed(cause,
                    header.resultCode() + " " + header.resultMsg());
            }

            List<TourPlace> places = TourPlaceMapper.toPlaces(response);
            if (places.isEmpty()) {
                log.debug("TourAPI 결과 0건: lat={}, lon={}, contentTypeId={}",
                    latitude, longitude, contentTypeId);
            }
            return TourApiResult.of(places);
        } catch (WebClientResponseException e) {
            ApiFailureCause cause = classifyStatus(e);
            log.warn("TourAPI 호출 실패({}): status={}, body={}",
                cause, e.getStatusCode(), e.getResponseBodyAsString());
            return new TourApiResult.Failed(cause, e.getStatusCode().toString());
        } catch (WebClientException e) {
            log.warn("TourAPI 전송 실패: lat={}, lon={}, error={}",
                latitude, longitude, e.getMessage());
            return new TourApiResult.Failed(ApiFailureCause.TRANSPORT_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            // 200인데 본문이 스키마와 다른 경우. 후보 공급이 죽어도 코스는 살아야 한다.
            log.warn("TourAPI 응답 해석 실패: lat={}, lon={}, error={}",
                latitude, longitude, e.getMessage());
            return new TourApiResult.Failed(ApiFailureCause.MALFORMED, e.getMessage());
        }
    }

    private static Header header(TourApiResponse response) {
        if (response == null || response.response() == null) {
            return null;
        }
        return response.response().header();
    }

    /**
     * HTTP 상태로 판정한다. 인증 실패가 <b>403</b>으로 오는 것을 4-7 실호출로 확인했다
     * (본문은 {@code SERVICE_KEY_IS_NOT_REGISTERED_ERROR}).
     */
    private static ApiFailureCause classifyStatus(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429) {
            return ApiFailureCause.QUOTA_EXCEEDED;
        }
        if (status == 401 || status == 403) {
            // 403 본문이 한도 초과일 수도 있어 메시지를 한 번 더 본다.
            return classifyBody(null, e.getResponseBodyAsString()) == ApiFailureCause.QUOTA_EXCEEDED
                ? ApiFailureCause.QUOTA_EXCEEDED
                : ApiFailureCause.UNAUTHORIZED;
        }
        return ApiFailureCause.HTTP_ERROR;
    }

    /**
     * 본문에 담긴 오류를 사유로 옮긴다.
     *
     * <p><b>코드가 아니라 메시지 문자열도 함께 보는 이유</b>: 공공데이터포털은 같은 오류를 게이트웨이
     * 응답({@code returnReasonCode})과 서비스 응답({@code resultCode}) 두 형식으로 주고 숫자 코드가
     * 서로 다르다. 메시지 상수({@code LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR} 등)는 두
     * 형식에서 같으므로, 코드로 먼저 맞춰 보고 안 맞으면 메시지로 판정한다.
     */
    private static ApiFailureCause classifyBody(String resultCode, String message) {
        if ("22".equals(resultCode)) {
            return ApiFailureCause.QUOTA_EXCEEDED;
        }
        if ("20".equals(resultCode) || "30".equals(resultCode) || "31".equals(resultCode)
            || "32".equals(resultCode)) {
            return ApiFailureCause.UNAUTHORIZED;
        }
        String upper = message == null ? "" : message.toUpperCase(Locale.ROOT);
        if (upper.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS")) {
            return ApiFailureCause.QUOTA_EXCEEDED;
        }
        if (upper.contains("SERVICE_KEY_IS_NOT_REGISTERED")
            || upper.contains("SERVICE_ACCESS_DENIED")
            || upper.contains("DEADLINE_HAS_EXPIRED")
            || upper.contains("UNREGISTERED_IP")) {
            return ApiFailureCause.UNAUTHORIZED;
        }
        return ApiFailureCause.HTTP_ERROR;
    }
}
