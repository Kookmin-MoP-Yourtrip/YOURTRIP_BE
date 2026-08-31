package backend.yourtrip.global.naver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 네이버 지역검색 응답 원문 (NAVER API HUB {@code GET /search/v1/local}).
 *
 * <p><b>이 레코드는 응답을 그대로 담기만 한다.</b> {@code <b>} 태그 스트립과 좌표 변환은
 * {@code NaverPlaceMapper}가 순수 함수로 처리한다 — 그래야 외부 호출 없이 단위 테스트할 수 있다.
 *
 * <p>필드 구성은 4-2 실호출로 확정했다([STEP-4](docs/tasks/ai-course-create/steps/STEP-4-candidate-sources.md) 판정 3).
 * 설계 가정과 어긋난 것 하나를 여기 적어둔다 — <b>{@code total}은 전체 매칭 수가 아니라 반환
 * 건수다.</b> 항상 {@code display}와 같으므로 인기도 신호로 쓸 수 없다(9-2가 기대한 값이 아니다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverLocalResponse(
    String lastBuildDate,
    int total,
    int start,
    int display,
    List<Item> items) {

    /**
     * 지역검색 항목 하나.
     *
     * <p>실측으로 확인된 성질 셋:
     * <ul>
     *   <li>{@code title}에 검색어 매칭 {@code <b>} 태그가 섞여 온다 — {@code 두낫디스터브 <b>경주</b>본점}</li>
     *   <li>{@code category}는 계층({@code 음식점>카페,디저트})일 수도, 단일 토큰({@code 브런치카페})일
     *       수도 있다 — 4-4 매핑이 구분자 없는 경우를 반드시 다뤄야 한다</li>
     *   <li>{@code description}·{@code telephone}은 사실상 항상 빈 값이고, {@code link}는 네이버
     *       플레이스 URL이 아니라 업소 자체 URL(인스타그램 등)이거나 빈 값이다 — SEEDED 후보에
     *       URL이 없다는 전제가 확인됐고, 그것이 {@code PlaceUrlEnricher}(5-10)가 필요한 이유다</li>
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
        String title,
        String link,
        String category,
        String description,
        String telephone,
        String address,
        String roadAddress,
        String mapx,
        String mapy) {

    }
}
