package backend.yourtrip.global.naver;

import backend.yourtrip.global.naver.dto.NaverLocalResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Component
@RequiredArgsConstructor
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

    private final WebClient naverWebClient;

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
            NaverLocalResult.Cause cause = classify(e);
            log.warn("네이버 지역검색 실패({}): query={}, status={}, body={}",
                cause, query, e.getStatusCode(), e.getResponseBodyAsString());
            return new NaverLocalResult.Failed(cause, e.getStatusCode().toString());
        } catch (WebClientException e) {
            // 타임아웃·커넥션 실패·풀 고갈은 WebClientRequestException 으로 올라온다.
            log.warn("네이버 지역검색 호출 실패: query={}, error={}", query, e.getMessage());
            return new NaverLocalResult.Failed(
                NaverLocalResult.Cause.TRANSPORT_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            // 200인데 본문이 스키마와 다른 경우(역직렬화 실패). 후보 공급이 죽어도 코스는 살아야 한다.
            log.warn("네이버 지역검색 응답 해석 실패: query={}, error={}", query, e.getMessage());
            return new NaverLocalResult.Failed(NaverLocalResult.Cause.MALFORMED, e.getMessage());
        }
    }

    private static NaverLocalResult.Cause classify(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429) {
            return NaverLocalResult.Cause.QUOTA_EXCEEDED;
        }
        if (status == 401 || status == 403) {
            return NaverLocalResult.Cause.UNAUTHORIZED;
        }
        return NaverLocalResult.Cause.HTTP_ERROR;
    }
}
