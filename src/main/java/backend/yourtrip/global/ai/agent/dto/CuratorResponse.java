package backend.yourtrip.global.ai.agent.dto;

import java.util.List;

/**
 * Curator LLM 응답을 그대로 받는 DTO (ROADMAP 6-4). <b>{@code CuratedDay}와 일부러 분리했다.</b>
 *
 * <h2>서버가 이미 아는 값은 받지 않는다 (#194)</h2>
 * 출력 토큰이 곧 응답 시간이라, 서버가 아는 값을 모델에게 되돌려 적게 하면 그만큼 LLM 슬롯을 쥔다.
 * 그래서 다음 넷을 응답에서 뺐다.
 * <ul>
 *   <li>{@code day} — 응답을 day 에 맞추는 것은 호출 순서다. 어디에서도 읽지 않았다</li>
 *   <li>{@code source} — 출처는 목록 항목이 정한다(검증기가 덮어썼다). 남는 정보는 "목록에서
 *       골랐는가" 하나이고, {@code listIndex}의 null 여부와 같다</li>
 *   <li>{@code slotType} — 자리 종류의 정본은 Planner 다. 이 값은 대조에만 쓰였다</li>
 *   <li>목록 선택의 {@code placeName} — 이름·좌표·주소는 목록 항목에서 승계한다. 이 값은
 *       {@code listIndex}의 검산에만 쓰였다</li>
 * </ul>
 * 뒤의 둘은 검산 장치였지만, 번호만 받으면 <b>목록 밖 이름을 목록 선택으로 위장할 통로 자체가
 * 사라진다.</b> 잘못 적은 번호도 같은 종류 목록의 실존 장소를 가리키므로 피해가 "덜 어울리는
 * 실존 장소"로 묶인다. 근거와 실측은 {@code docs/tasks/llm-performance/steps/STEP-curator-output.md}.
 *
 * <h2>좌표·id·URL 필드가 없는 것이 핵심이다</h2>
 * "{@code SEEDED}·{@code LISTED}는 재검증을 생략한다"의 전제는 목록 항목의 좌표·주소를 <b>코드가</b>
 * 승계하는 것이지 LLM 이 옮겨 적는 것이 아니다. 스키마에 그 필드를 두는 순간 모델이 값을 지어낼
 * 자리가 생기고, <b>그 값은 아무도 검증하지 않는다.</b>
 */
public record CuratorResponse(List<Slot> slots) {

    /**
     * @param slotIndex 채울 자리의 번호. 프롬프트가 준 값을 그대로 돌려받는다. 배열 위치로 대체하지
     *                  않는 이유는 모델이 자리 하나를 빠뜨리면 뒤의 자리가 전부 밀리기 때문이다
     */
    public record Slot(Integer slotIndex, List<Choice> choices) {
    }

    /**
     * @param listIndex 목록에서 고른 위치(0부터). 목록 밖 제안이면 null 이다.
     *                  <b>상호명이 아니라 인덱스로 참조하게 하는 이유</b>는, 선별 과제라도 LLM 출력인
     *                  이상 "목록에서 골랐다"고 주장하며 목록에 없는 이름을 내놓을 수 있기 때문이다
     * @param placeName 목록 밖 제안의 상호명. 그때만 카카오 검색어가 된다. 목록 선택에서는 null 이
     *                  정상이며, 적혀 있으면 번호가 범위를 벗어났을 때 강등할 검색어로만 쓴다
     */
    public record Choice(Integer listIndex, String placeName) {
    }
}
